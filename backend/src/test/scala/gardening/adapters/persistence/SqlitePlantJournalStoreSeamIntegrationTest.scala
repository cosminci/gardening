package gardening.adapters.persistence

import cats.syntax.option.*
import gardening.domain.*
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import org.flywaydb.core.Flyway

import com.augustnagro.magnum.Transactor
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.util.chaining.scalaUtilChainingOps

class SqlitePlantJournalStoreSeamIntegrationTest extends FunSuite:

  private val care = OperationDetails.Care(
    date = Instant.parse("2026-01-01T00:00:00Z"),
    actions = Set(ActionType.Watered, ActionType.Fertilized),
    moisture = MoistureLevel.Wet,
    maybeNote = Note("a little dry").some
  )

  test("should return no plants when none have been seeded"):
    withStore: (_, store) =>
      assertEquals(store.getPlants, JournalReadResult.Read(Vector.empty))

  test("should return an active plant with its persisted substrate"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1", substrate = List(SubstrateComponent.Perlite -> 100))
      updatePlantNickname(dataSource, id = "p1", nickname = "Fig")

      store.getPlants match
        case JournalReadResult.Read(Vector(plant)) =>
          assertEquals(plant.id, PlantId("p1"))
          assertEquals(plant.maybeNickname, Nickname("Fig").some)
          assertEquals(plant.substrate.parts, List(SubstratePart(SubstrateComponent.Perlite, share = 100)))
          assertEquals(store.getPlant(PlantId("p1")), JournalReadResult.Read(plant))
          assertEquals(store.getPlant(PlantId("missing")), JournalReadResult.RecordMissing)
        case other => fail(s"expected one plant, got $other")

  test("should exclude archived plants"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "active-1", status = PlantStatus.Active)
      seedPlant(dataSource, id = "archived-1", status = PlantStatus.Archived)

      store.getPlants match
        case JournalReadResult.Read(plants) => assertEquals(plants.map(_.id), Vector(PlantId("active-1")))
        case other                          => fail(s"expected Read, got $other")

  test("should report every corrupted plant row"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1", substrate = List(SubstrateComponent.Perlite -> 60, SubstrateComponent.Perlite -> 60))
      seedPlant(dataSource, id = "p2")
      seedPlant(dataSource, id = "p3")
      seedPlant(dataSource, id = "p4")
      updatePlantSubstrate(dataSource, id = "p2", substrate = "Unknown:100")
      updatePlantSubstrate(dataSource, id = "p3", substrate = "Perlite")
      updatePlantSubstrate(dataSource, id = "p4", substrate = "Unknown:100")
      updatePlantStatusIgnoringConstraints(dataSource, id = "p4", status = "Unknown")

      store.getPlants match
        case JournalReadResult.Corrupted(details) =>
          val expected: Set[(JournalRecord, String)] = Set(
            JournalRecord.Plant(PlantId("p1")) -> "invalid stored substrate: DuplicateComponent",
            JournalRecord.Plant(PlantId("p2")) -> "invalid stored substrate: Malformed",
            JournalRecord.Plant(PlantId("p3")) -> "invalid stored substrate: Malformed",
            JournalRecord.Plant(PlantId("p4")) -> "invalid stored substrate: Malformed",
            JournalRecord.Plant(PlantId("p4")) -> "invalid stored plant status: Unknown"
          )
          assertEquals(details.length, 5)
          assertEquals(details.toList.map(detail => detail.record -> detail.reason.getMessage).toSet, expected)
        case other => fail(s"expected Corrupted, got $other")

      store.getPlant(PlantId("p1")) match
        case JournalReadResult.Corrupted(details) =>
          assertEquals(
            details.toList.map(detail => detail.record -> detail.reason.getMessage),
            List(JournalRecord.Plant(PlantId("p1")) -> "invalid stored substrate: DuplicateComponent")
          )
        case other => fail(s"expected Corrupted, got $other")

      store.getPlant(PlantId("p4")) match
        case JournalReadResult.Corrupted(details) =>
          assertEquals(
            details.toList.map(detail => detail.record -> detail.reason.getMessage),
            List(
              JournalRecord.Plant(PlantId("p4")) -> "invalid stored substrate: Malformed",
              JournalRecord.Plant(PlantId("p4")) -> "invalid stored plant status: Unknown"
            )
          )
        case other => fail(s"expected Corrupted, got $other")

  test("should round-trip a care operation without changing plant substrate"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), care)

      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(readOperationKind(dataSource, operation.id.value), "Care")
      assertEquals(store.getOperations(PlantId("p1")), JournalReadResult.Read(Vector(operation)))
      store.getPlants match
        case JournalReadResult.Read(Vector(plant)) =>
          assertEquals(plant.substrate.parts, List(SubstratePart(SubstrateComponent.Perlite, share = 100)))
        case other => fail(s"expected one plant, got $other")

  test("should round-trip a care observation without actions"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val observation = Operation(
        OperationId("o1"),
        PlantId("p1"),
        OperationDetails.Care(date = care.date, actions = Set.empty, moisture = care.moisture, maybeNote = None)
      )

      assertEquals(store.addOperation(observation), LogOperationResult.Logged(observation.id))
      assertEquals(store.getOperations(PlantId("p1")), JournalReadResult.Read(Vector(observation)))

  test("should persist a repot without changing the plant"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val substrate = substrateOf(SubstrateComponent.Sand3to5 -> 100)
      val operation = Operation(
        OperationId("o1"),
        PlantId("p1"),
        OperationDetails.Repot(
          date = Instant.parse("2026-02-01T00:00:00Z"),
          substrate,
          maybeNote = Note("new mix").some
        )
      )

      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))
      assertEquals(readOperationKind(dataSource, operation.id.value), "Repot")
      assertEquals(store.getOperation(operation.id), JournalReadResult.Read(operation))
      store.getPlants match
        case JournalReadResult.Read(Vector(plant)) =>
          assertEquals(plant.substrate.parts, List(SubstratePart(SubstrateComponent.Perlite, share = 100)))
        case other => fail(s"expected one plant, got $other")

      store.updatePlant(
        Plant(
          id = PlantId("p1"),
          species = Species("Ficus lyrata"),
          maybeNickname = None,
          location = Location("Balcony"),
          substrate = substrate,
          status = PlantStatus.Active
        )
      )

      store.getPlants match
        case JournalReadResult.Read(Vector(plant)) =>
          assertEquals(plant.substrate.parts, List(SubstratePart(SubstrateComponent.Sand3to5, share = 100)))
        case other => fail(s"expected one plant, got $other")

  test("should return no operation for an unknown id"):
    withStore: (_, store) =>
      assertEquals(store.getOperation(OperationId("missing")), JournalReadResult.RecordMissing)
      assertEquals(store.getOperations(PlantId("missing")), JournalReadResult.Read(Vector.empty))

  test("should report every corrupted operation row"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val repot = Operation(
        OperationId("o1"),
        PlantId("p1"),
        OperationDetails.Repot(
          date = Instant.parse("2026-01-01T00:00:00Z"),
          substrateOf(SubstrateComponent.Perlite -> 100),
          maybeNote = None
        )
      )
      val careOperation = Operation(OperationId("o2"), PlantId("p1"), care)
      assertEquals(store.addOperation(repot), LogOperationResult.Logged(repot.id))
      assertEquals(store.addOperation(careOperation), LogOperationResult.Logged(careOperation.id))
      updateOperationDetails(
        dataSource,
        id = "o1",
        details =
          """{"kind":"Repot","date":"2026-01-01T00:00:00Z","substrate":[{"component":"Perlite","share":60},{"component":"Perlite","share":60}]}"""
      )
      updateOperationDetailsIgnoringConstraints(dataSource, id = "o2", details = """{"kind":"Unknown"}""")

      store.getOperations(PlantId("p1")) match
        case JournalReadResult.Corrupted(details) =>
          val expected: Set[(JournalRecord, String)] = Set(
            JournalRecord.Operation(OperationId("o1")) -> "invalid stored substrate: DuplicateComponent",
            JournalRecord.Operation(OperationId("o2")) -> "invalid stored operation details"
          )
          assertEquals(details.length, 2)
          assertEquals(details.toList.map(detail => detail.record -> detail.reason.getMessage).toSet, expected)
        case other => fail(s"expected Corrupted, got $other")

      store.getOperation(repot.id) match
        case JournalReadResult.Corrupted(details) =>
          assertEquals(
            details.toList.map(detail => detail.record -> detail.reason.getMessage),
            List(JournalRecord.Operation(OperationId("o1")) -> "invalid stored substrate: DuplicateComponent")
          )
        case other => fail(s"expected Corrupted, got $other")

      store.getOperation(careOperation.id) match
        case JournalReadResult.Corrupted(details) =>
          assertEquals(
            details.toList.map(detail => detail.record -> detail.reason.getMessage),
            List(JournalRecord.Operation(OperationId("o2")) -> "invalid stored operation details")
          )
        case other => fail(s"expected Corrupted, got $other")

  test("should report malformed stored operation details through the store"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val invalidRows = List(
        ("malformed-json", "{", List("invalid stored operation details")),
        ("missing-kind", "{}", List("invalid stored operation details")),
        ("unknown-kind", """{"kind":"Unknown"}""", List("invalid stored operation details")),
        ("missing-care-fields", """{"kind":"Care"}""", List.fill(3)("invalid stored operation details")),
        ("missing-repot-fields", """{"kind":"Repot"}""", List.fill(2)("invalid stored operation details")),
        (
          "invalid-date",
          """{"kind":"Care","date":"today","actions":[],"moisture":"Wet","note":null}""",
          List("invalid stored operation details")
        ),
        (
          "invalid-action",
          """{"kind":"Care","date":"2026-01-01T00:00:00Z","actions":["Unknown"],"moisture":"Wet","note":null}""",
          List("invalid stored operation details")
        ),
        (
          "invalid-moisture",
          """{"kind":"Care","date":"2026-01-01T00:00:00Z","actions":[],"moisture":"Unknown","note":null}""",
          List("invalid stored operation details")
        ),
        (
          "invalid-component",
          """{"kind":"Repot","date":"2026-01-01T00:00:00Z","substrate":[{"component":"Unknown","share":50}],"note":null}""",
          List("invalid stored substrate: Malformed")
        ),
        (
          "invalid-share",
          """{"kind":"Repot","date":"2026-01-01T00:00:00Z","substrate":[{"component":"Perlite","share":0}],"note":null}""",
          List("invalid stored substrate: Malformed")
        ),
        (
          "independent-failures",
          """{"kind":"Care","date":"today","actions":["Unknown"],"moisture":"Unknown","note":null}""",
          List.fill(3)("invalid stored operation details")
        )
      )

      invalidRows.foreach: (id, encoded, expectedReasons) =>
        insertOperationDetailsIgnoringConstraints(dataSource, id, plantId = "p1", encoded)

        store.getOperation(OperationId(id)) match
          case JournalReadResult.Corrupted(details) =>
            val record = JournalRecord.Operation(OperationId(id))
            assertEquals(details.toList.map(detail => detail.record -> detail.reason.getMessage), expectedReasons.map(record -> _))
          case other => fail(s"expected Corrupted for $id, got $other")

  test("should amend a repot without changing the plant"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val oldSubstrate = substrateOf(SubstrateComponent.Sand3to5 -> 100)
      val operation    = Operation(
        OperationId("o1"),
        PlantId("p1"),
        OperationDetails.Repot(date = Instant.parse("2026-01-01T00:00:00Z"), oldSubstrate, maybeNote = None)
      )
      val newSubstrate = substrateOf(SubstrateComponent.Leca -> 100)
      val amended      = OperationDetails.Repot(date = Instant.parse("2026-02-01T00:00:00Z"), newSubstrate, maybeNote = None)
      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(store.updateOperation(operation.id, amended), EditOperationResult.Edited(operation.copy(details = amended)))
      store.getPlants match
        case JournalReadResult.Read(Vector(plant)) =>
          assertEquals(plant.substrate.parts, List(SubstratePart(SubstrateComponent.Perlite, share = 100)))
        case other => fail(s"expected one plant, got $other")

  test("should report a logging failure when the plant does not exist"):
    withStore: (_, store) =>
      store.addOperation(Operation(OperationId("o1"), PlantId("no-such-plant"), care)) match
        case LogOperationResult.LoggingFailed(_) => ()
        case other                               => fail(s"expected LoggingFailed, got $other")

  test("should report a missing operation when editing an unknown id"):
    withStore: (_, store) =>
      assertEquals(store.updateOperation(OperationId("nope"), care), EditOperationResult.OperationMissing)

  test("should edit an existing care operation without changing plant substrate"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), care)
      val amended   = OperationDetails.Care(
        date = care.date,
        actions = care.actions,
        moisture = MoistureLevel.Dry,
        maybeNote = None
      )
      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(store.updateOperation(operation.id, amended), EditOperationResult.Edited(operation.copy(details = amended)))
      store.getPlants match
        case JournalReadResult.Read(Vector(plant)) =>
          assertEquals(plant.substrate.parts, List(SubstratePart(SubstrateComponent.Perlite, share = 100)))
        case other => fail(s"expected one plant, got $other")

  test("should report an edit failure when the database is read-only"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), care)
      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))
      val readOnlyStore = SqlitePlantJournalStore.make(Transactor(dataSource, connectionConfig = makeReadOnly))

      readOnlyStore.updateOperation(operation.id, care) match
        case EditOperationResult.EditFailed(_) => ()
        case other                             => fail(s"expected EditFailed, got $other")

  test("should return read failures when the journal schema is unavailable"):
    val connection = Sqlite.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    try
      val store = SqlitePlantJournalStore.make(connection.transactor)
      List(
        store.getPlants,
        store.getPlant(PlantId("p1")),
        store.getOperations(PlantId("p1")),
        store.getOperation(OperationId("o1"))
      ).foreach:
        case JournalReadResult.ReadFailed(_) => ()
        case other                           => fail(s"expected ReadFailed, got $other")
    finally connection.close()

  private def withStore(test: (DataSource, PlantJournalStore) => Unit): Unit =
    val connection = Sqlite.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    try
      val _ = Flyway.configure().dataSource(connection.dataSource).load().migrate()
      test(connection.dataSource, SqlitePlantJournalStore.make(connection.transactor))
    finally connection.close()

  private def makeReadOnly(connection: Connection): Unit =
    val statement = connection.createStatement()
    val _         = statement.execute("PRAGMA query_only = ON")
    statement.close()

  private def seedPlant(
      dataSource: DataSource,
      id: String,
      species: String = "Ficus lyrata",
      location: String = "Balcony",
      status: PlantStatus = PlantStatus.Active,
      substrate: List[(SubstrateComponent, Int)] = List(SubstrateComponent.Perlite -> 100)
  ): Unit =
    val connection = dataSource.getConnection()
    try
      val statement =
        connection.prepareStatement("insert into plant (id, species, location, status, substrate) values (?, ?, ?, ?, ?)")
      statement.setString(1, id)
      statement.setString(2, species)
      statement.setString(3, location)
      statement.setString(4, status.toString)
      statement.setString(5, substrate.map((component, share) => s"$component:$share").mkString(","))
      val _ = statement.executeUpdate()
      statement.close()
    finally connection.close()

  private def substrateOf(parts: (SubstrateComponent, Percentage)*): Substrate =
    Substrate
      .of(parts.map((component, share) => SubstratePart(component, share)).toList)
      .getOrElse(fail("invalid test substrate"))

  private def updatePlantSubstrate(dataSource: DataSource, id: String, substrate: String): Unit =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement("update plant set substrate = ? where id = ?")
      statement.setString(1, substrate)
      statement.setString(2, id)
      val _ = statement.executeUpdate()
      statement.close()
    finally connection.close()

  private def updatePlantNickname(dataSource: DataSource, id: String, nickname: String): Unit =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement("update plant set nickname = ? where id = ?")
      statement.setString(1, nickname)
      statement.setString(2, id)
      val _ = statement.executeUpdate()
      statement.close()
    finally connection.close()

  private def updatePlantStatusIgnoringConstraints(dataSource: DataSource, id: String, status: String): Unit =
    val connection = dataSource.getConnection()
    try
      val pragma    = connection.createStatement()
      val _         = pragma.execute("pragma ignore_check_constraints = on")
      val statement = connection.prepareStatement("update plant set status = ? where id = ?")
      statement.setString(1, status)
      statement.setString(2, id)
      val _ = statement.executeUpdate()
      statement.close()
      pragma.close()
    finally connection.close()

  private def updateOperationDetails(dataSource: DataSource, id: String, details: String): Unit =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement("update operation set details = ? where id = ?")
      statement.setString(1, details)
      statement.setString(2, id)
      val _ = statement.executeUpdate()
      statement.close()
    finally connection.close()

  private def updateOperationDetailsIgnoringConstraints(dataSource: DataSource, id: String, details: String): Unit =
    val connection = dataSource.getConnection()
    try
      val pragma    = connection.createStatement()
      val _         = pragma.execute("pragma ignore_check_constraints = on")
      val statement = connection.prepareStatement("update operation set details = ? where id = ?")
      statement.setString(1, details)
      statement.setString(2, id)
      val _ = statement.executeUpdate()
      statement.close()
      pragma.close()
    finally connection.close()

  private def insertOperationDetailsIgnoringConstraints(
      dataSource: DataSource,
      id: String,
      plantId: String,
      details: String
  ): Unit =
    val connection = dataSource.getConnection()
    try
      val pragma    = connection.createStatement()
      val _         = pragma.execute("pragma ignore_check_constraints = on")
      val statement = connection.prepareStatement("insert into operation (id, plant_id, details) values (?, ?, ?)")
      statement.setString(1, id)
      statement.setString(2, plantId)
      statement.setString(3, details)
      val _ = statement.executeUpdate()
      statement.close()
      pragma.close()
    finally connection.close()

  private def readOperationKind(dataSource: DataSource, id: String): String =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement("select json_extract(details, '$.kind') from operation where id = ?")
      statement.setString(1, id)
      val result = statement.executeQuery()
      result.getString(1).tap: _ =>
        result.close()
        statement.close()
    finally connection.close()
