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

class PlantJournalStoreSeamIntegrationTest extends FunSuite:

  private val date = Instant.parse("2026-01-01T00:00:00Z")

  private val care = OperationDetails.Care(
    actions = Set(ActionType.Watered, ActionType.Fertilized),
    pesticides = Set.empty,
    moisture = MoistureLevel.Wet,
    maybeNote = Note("a little dry").some
  )

  test("should return no plants when none have been seeded"):
    withStore: (_, store) =>
      assertEquals(store.getPlants, GetPlantsResult.Read(Vector.empty))

  test("should return an active plant with its persisted substrate"):
    withStore: (dataSource, store) =>
      seedPlant(
        dataSource,
        id = "p1",
        maybeNickname = "Fig".some,
        substrate = List(TestNomenclatureIds.Perlite -> 100)
      )

      store.getPlants match
        case GetPlantsResult.Read(Vector(plant)) =>
          assertEquals(plant.id, PlantId("p1"))
          assertEquals(plant.details.maybeNickname, Nickname("Fig").some)
          assertEquals(plant.details.substrate.parts, List(SubstratePart(TestNomenclatureIds.Perlite, share = 100)))
          assertEquals(store.getPlant(PlantId("p1")), GetPlantResult.Read(plant))
          assertEquals(store.getPlant(PlantId("missing")), GetPlantResult.RecordMissing)
        case other => fail(s"expected one plant, got $other")

  test("should exclude archived plants"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "active-1", status = PlantStatus.Active)
      seedPlant(dataSource, id = "archived-1", status = PlantStatus.Archived)

      store.getPlants match
        case GetPlantsResult.Read(plants) => assertEquals(plants.map(_.id), Vector(PlantId("active-1")))
        case other                        => fail(s"expected Read, got $other")

  test("should fail when stored plant data is corrupt"):
    withStore: (dataSource, store) =>
      seedPlant(
        dataSource,
        id = "duplicate-components",
        substrate = List(TestNomenclatureIds.Perlite -> 60, TestNomenclatureIds.Perlite -> 60)
      )

      val allPlants = intercept[DatabaseCorruption](store.getPlants)
      assertEquals(allPlants.err.getMessage, "invalid stored substrate: DuplicateComponent")

      val plant = intercept[DatabaseCorruption](store.getPlant(PlantId("duplicate-components")))
      assertEquals(plant.err.getMessage, "invalid stored substrate: DuplicateComponent")

  test("should round-trip a care operation without changing plant substrate"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val operation = Operation(
        OperationId("o1"),
        PlantId("p1"),
        date,
        care.copy(pesticides = Set(TestNomenclatureIds.Vertab, TestNomenclatureIds.NeemOil))
      )

      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(readOperationKind(dataSource, operation.id.value), "Care")
      assertEquals(store.getOperations(PlantId("p1")), GetOperationsResult.Read(Vector(operation)))
      store.getPlants match
        case GetPlantsResult.Read(Vector(plant)) =>
          assertEquals(plant.details.substrate.parts, List(SubstratePart(TestNomenclatureIds.Perlite, share = 100)))
        case other => fail(s"expected one plant, got $other")

  test("should round-trip a care observation without actions"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val observation = Operation(
        OperationId("o1"),
        PlantId("p1"),
        date,
        OperationDetails.Care(actions = Set.empty, pesticides = Set.empty, moisture = care.moisture, maybeNote = none)
      )

      assertEquals(store.addOperation(observation), LogOperationResult.Logged(observation.id))
      assertEquals(store.getOperations(PlantId("p1")), GetOperationsResult.Read(Vector(observation)))

  test("should persist a repot without changing the plant"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val substrate = substrateOf(TestNomenclatureIds.Sand3to5 -> 100)
      val operation = Operation(
        OperationId("o1"),
        PlantId("p1"),
        date,
        OperationDetails.Repot(
          substrate,
          maybeNote = Note("new mix").some
        )
      )

      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))
      assertEquals(readOperationKind(dataSource, operation.id.value), "Repot")
      assertEquals(store.getOperation(operation.id), GetOperationResult.Read(operation))
      store.getPlants match
        case GetPlantsResult.Read(Vector(plant)) =>
          assertEquals(plant.details.substrate.parts, List(SubstratePart(TestNomenclatureIds.Perlite, share = 100)))
        case other => fail(s"expected one plant, got $other")

  test("should require operation timestamps to be unique only within a plant"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      seedPlant(dataSource, id = "p2")
      val firstPlantOperation  = Operation(OperationId("p1-o1"), PlantId("p1"), date, care)
      val secondPlantOperation = Operation(OperationId("p2-o1"), PlantId("p2"), date, care)
      val timestampCollision   = Operation(OperationId("p1-o2"), PlantId("p1"), date, care)

      assertEquals(store.addOperation(firstPlantOperation), LogOperationResult.Logged(firstPlantOperation.id))
      assertEquals(store.addOperation(secondPlantOperation), LogOperationResult.Logged(secondPlantOperation.id))
      store.addOperation(timestampCollision) match
        case LogOperationResult.LoggingFailed(reason) =>
          assert(reason.getMessage.contains("UNIQUE constraint failed: operation.plant_id, operation.date"))
        case other => fail(s"expected LoggingFailed, got $other")

  test("should reject unknown operation kinds and malformed JSON payloads"):
    withStore: (dataSource, _) =>
      seedPlant(dataSource, id = "p1")

      val unknownKind = intercept[java.sql.SQLException]:
        insertOperation(dataSource, "unknown-kind", "p1", date.toString, "Unknown", "{}")
      val malformedCarePayload = intercept[java.sql.SQLException]:
        insertOperation(dataSource, "malformed-payload", "p1", date.toString, "Care", "not-json")
      val malformedRepotPayload = intercept[java.sql.SQLException]:
        insertOperation(dataSource, "malformed-repot", "p1", date.plusNanos(1).toString, "Repot", "not-json")

      assert(unknownKind.getMessage.contains("CHECK constraint failed"))
      assert(malformedCarePayload.getMessage.contains("CHECK constraint failed"))
      assert(malformedRepotPayload.getMessage.contains("CHECK constraint failed"))

  test("should update every editable plant detail"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val updatedPlant = Plant(
        PlantId("p1"),
        PlantDetails(
          species = Species("Monstera deliciosa"),
          maybeNickname = Nickname("Monty").some,
          location = Location("Living room"),
          substrate = substrateOf(TestNomenclatureIds.Leca -> 100),
          status = PlantStatus.Archived
        )
      )

      assertEquals(store.updatePlant(updatedPlant), UpdatePlantResult.Updated)
      assertEquals(store.getPlant(updatedPlant.id), GetPlantResult.Read(updatedPlant))
      assertEquals(store.getPlants, GetPlantsResult.Read(Vector.empty))

  test("should return no operation for an unknown id"):
    withStore: (_, store) =>
      assertEquals(store.getOperation(OperationId("missing")), GetOperationResult.RecordMissing)
      assertEquals(store.getOperations(PlantId("missing")), GetOperationsResult.Read(Vector.empty))

  test("should fail when stored operation data is corrupt"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val repot = Operation(
        OperationId("o1"),
        PlantId("p1"),
        date,
        OperationDetails.Repot(substrateOf(TestNomenclatureIds.Perlite -> 100), maybeNote = none)
      )
      assertEquals(store.addOperation(repot), LogOperationResult.Logged(repot.id))
      updateOperationPayload(
        dataSource,
        id = "o1",
        payload =
          s"""{"substrate":[{"component":"${TestNomenclatureIds.Perlite.value}","share":60},{"component":"${TestNomenclatureIds.Perlite.value}","share":60}]}"""
      )

      val operations = intercept[DatabaseCorruption](store.getOperations(PlantId("p1")))
      assertEquals(operations.err.getMessage, "invalid stored substrate: DuplicateComponent")

      val operation = intercept[DatabaseCorruption](store.getOperation(repot.id))
      assertEquals(operation.err.getMessage, "invalid stored substrate: DuplicateComponent")

  test("should amend a repot without changing the plant"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val oldSubstrate = substrateOf(TestNomenclatureIds.Sand3to5 -> 100)
      val operation    = Operation(
        OperationId("o1"),
        PlantId("p1"),
        date,
        OperationDetails.Repot(oldSubstrate, maybeNote = none)
      )
      val newSubstrate = substrateOf(TestNomenclatureIds.Leca -> 100)
      val amended      = OperationDetails.Repot(newSubstrate, maybeNote = none)
      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(store.updateOperation(operation.id, amended), EditOperationResult.Edited(operation.copy(details = amended)))
      store.getPlants match
        case GetPlantsResult.Read(Vector(plant)) =>
          assertEquals(plant.details.substrate.parts, List(SubstratePart(TestNomenclatureIds.Perlite, share = 100)))
        case other => fail(s"expected one plant, got $other")

  test("should remove and restore operations for compensation"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val original = Operation(OperationId("o1"), PlantId("p1"), date, care)
      val amended  = OperationDetails.Care(actions = Set.empty, pesticides = Set.empty, moisture = MoistureLevel.Dry, maybeNote = none)
      assertEquals(store.addOperation(original), LogOperationResult.Logged(original.id))
      assertEquals(store.updateOperation(original.id, amended), EditOperationResult.Edited(original.copy(details = amended)))

      assertEquals(store.restoreOperation(original), OperationCompensationResult.Compensated)
      assertEquals(store.getOperation(original.id), GetOperationResult.Read(original))
      assertEquals(store.removeOperation(original.id), OperationCompensationResult.Compensated)
      assertEquals(store.getOperation(original.id), GetOperationResult.RecordMissing)
      assertEquals(store.removeOperation(original.id), OperationCompensationResult.Compensated)

  test("should report missing compensation targets"):
    withStore: (_, store) =>
      val missing = Operation(OperationId("missing"), PlantId("p1"), date, care)
      store.restoreOperation(missing) match
        case OperationCompensationResult.CompensationFailed(reason) =>
          assertEquals(reason.getMessage, "operation not found while restoring: missing")
        case other => fail(s"expected CompensationFailed, got $other")
      store.updatePlant(plant(id = PlantId("missing"))) match
        case UpdatePlantResult.UpdateFailed(reason) => assertEquals(reason.getMessage, "plant not found while updating: missing")
        case other                                  => fail(s"expected UpdateFailed, got $other")

  test("should fail when edited operation metadata is corrupt"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      insertOperation(
        dataSource,
        id = "o1",
        plantId = "p1",
        storedDate = "today",
        kind = "Care",
        payload = """{"actions":[],"moisture":"Wet","note":null}"""
      )

      val failure = intercept[DatabaseCorruption](store.updateOperation(OperationId("o1"), care))
      assertEquals(failure.err.getMessage, "invalid stored operation date: today")

  test("should report a logging failure when the plant does not exist"):
    withStore: (_, store) =>
      store.addOperation(Operation(OperationId("o1"), PlantId("no-such-plant"), date, care)) match
        case LogOperationResult.LoggingFailed(_) => ()
        case other                               => fail(s"expected LoggingFailed, got $other")

  test("should report a missing operation when editing an unknown id"):
    withStore: (_, store) =>
      assertEquals(store.updateOperation(OperationId("nope"), care), EditOperationResult.OperationMissing)

  test("should edit an existing care operation without changing plant substrate"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care)
      val amended   = OperationDetails.Care(
        actions = care.actions,
        pesticides = care.pesticides,
        moisture = MoistureLevel.Dry,
        maybeNote = none
      )
      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(store.updateOperation(operation.id, amended), EditOperationResult.Edited(operation.copy(details = amended)))
      store.getPlants match
        case GetPlantsResult.Read(Vector(plant)) =>
          assertEquals(plant.details.substrate.parts, List(SubstratePart(TestNomenclatureIds.Perlite, share = 100)))
        case other => fail(s"expected one plant, got $other")

  test("should report an edit failure when the database is read-only"):
    withStore: (dataSource, store) =>
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care)
      assertEquals(store.addOperation(operation), LogOperationResult.Logged(operation.id))
      val readOnlyStore = SqlitePlantJournalStore.make(Transactor(dataSource, connectionConfig = makeReadOnly))

      readOnlyStore.updateOperation(operation.id, care) match
        case EditOperationResult.EditFailed(_) => ()
        case other                             => fail(s"expected EditFailed, got $other")
      List(readOnlyStore.removeOperation(operation.id), readOnlyStore.restoreOperation(operation)).foreach:
        case OperationCompensationResult.CompensationFailed(_) => ()
        case other                                             => fail(s"expected CompensationFailed, got $other")
      readOnlyStore.updatePlant(plant()) match
        case UpdatePlantResult.UpdateFailed(_) => ()
        case other                             => fail(s"expected UpdateFailed, got $other")

  test("should return read failures when the journal schema is unavailable"):
    val connection = Sqlite.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    try
      val store = SqlitePlantJournalStore.make(connection.transactor)
      List(
        store.getPlants,
        store.getOperations(PlantId("p1"))
      ).foreach:
        case GetPlantsResult.ReadFailed(_) | GetOperationsResult.ReadFailed(_) => ()
        case other                                                             => fail(s"expected ReadFailed, got $other")
      store.getPlant(PlantId("p1")) match
        case GetPlantResult.ReadFailed(_) => ()
        case other                        => fail(s"expected ReadFailed, got $other")
      store.getOperation(OperationId("o1")) match
        case GetOperationResult.ReadFailed(_) => ()
        case other                            => fail(s"expected ReadFailed, got $other")
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
      maybeNickname: Option[String] = none,
      location: String = "Balcony",
      status: PlantStatus = PlantStatus.Active,
      substrate: List[(SubstrateComponentId, Int)] = List(TestNomenclatureIds.Perlite -> 100)
  ): Unit =
    val connection = dataSource.getConnection()
    try
      val statement =
        connection.prepareStatement(
          "insert into plant (id, species, nickname, location, status, substrate) values (?, ?, ?, ?, ?, ?)"
        )
      statement.setString(1, id)
      statement.setString(2, species)
      maybeNickname match
        case Some(nickname) => statement.setString(3, nickname)
        case None           => statement.setNull(3, java.sql.Types.VARCHAR)
      statement.setString(4, location)
      statement.setString(5, status.toString)
      statement.setString(
        6,
        substrate.map((component, share) => s"""{"component":"${component.value}","share":$share}""").mkString("[", ",", "]")
      )
      val _ = statement.executeUpdate()
      statement.close()
    finally connection.close()

  private def substrateOf(parts: (SubstrateComponentId, Percentage)*): Substrate =
    Substrate
      .of(parts.map((component, share) => SubstratePart(component, share)).toList)
      .getOrElse(fail("invalid test substrate"))

  private def plant(id: PlantId = PlantId("p1")): Plant =
    Plant(
      id,
      PlantDetails(
        species = Species("Ficus lyrata"),
        maybeNickname = none,
        location = Location("Balcony"),
        substrate = substrateOf(TestNomenclatureIds.Perlite -> 100),
        status = PlantStatus.Active
      )
    )

  private def updateOperationPayload(dataSource: DataSource, id: String, payload: String): Unit =
    execute(dataSource, "update operation set payload = ? where id = ?", payload, id)

  private def insertOperation(
      dataSource: DataSource,
      id: String,
      plantId: String,
      storedDate: String,
      kind: String,
      payload: String
  ): Unit =
    execute(
      dataSource,
      "insert into operation (id, plant_id, date, kind, payload) values (?, ?, ?, ?, ?)",
      id,
      plantId,
      storedDate,
      kind,
      payload
    )

  private def readOperationKind(dataSource: DataSource, id: String): String =
    queryString(dataSource, "select kind from operation where id = ?", id)

  private def execute(dataSource: DataSource, sql: String, parameters: String*): Unit =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement(sql)
      try
        parameters.zipWithIndex.foreach((parameter, index) => statement.setString(index + 1, parameter))
        val _ = statement.executeUpdate()
      finally statement.close()
    finally connection.close()

  private def queryString(dataSource: DataSource, sql: String, parameters: String*): String =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement(sql)
      try
        parameters.zipWithIndex.foreach((parameter, index) => statement.setString(index + 1, parameter))
        val result = statement.executeQuery()
        try
          assert(result.next(), s"expected query to return a row: $sql")
          result.getString(1)
        finally result.close()
      finally statement.close()
    finally connection.close()
