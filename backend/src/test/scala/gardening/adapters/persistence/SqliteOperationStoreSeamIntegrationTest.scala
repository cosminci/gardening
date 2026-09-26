package gardening.adapters.persistence

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.operations.*
import gardening.domain.plants.*
import com.augustnagro.magnum.Transactor
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import org.flywaydb.core.Flyway

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqliteOperationStoreSeamIntegrationTest extends FunSuite:

  private val date       = Instant.parse("2026-01-01T00:00:00Z")
  private val perliteId  = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val sand3to5Id = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000005"))
  private val lecaId     = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000007"))
  private val vertabId   = PesticideId(UUID.fromString("00000000-0000-4000-8001-000000000003"))
  private val neemOilId  = PesticideId(UUID.fromString("00000000-0000-4000-8001-000000000007"))
  private val fullWindow = OperationWindow(offset = 0, size = 10)

  private val perliteSubstrate    = Substrate.of(List(SubstratePart(perliteId, share = 100))).getOrElse(fail("invalid perlite substrate"))
  private val sand3to5Substrate   = Substrate.of(List(SubstratePart(sand3to5Id, share = 100))).getOrElse(fail("invalid sand substrate"))
  private val lecaSubstrate       = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid LECA substrate"))
  private val defaultPlantDetails = PlantDetails(Species("Ficus lyrata"), none, Location("Balcony"), perliteSubstrate, PlantStatus.Active)
  private val care = OperationDetails.Care(Set(ActionType.Watered, ActionType.Fertilized), Set.empty, MoistureLevel.Wet, Note("a little dry").some)

  test("should reject new care and repot operations for an archived plant"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1", status = PlantStatus.Archived)
      val plantId    = PlantId("p1")
      val laterCare  = Operation(OperationId("o2"), plantId, date, care)
      val laterRepot = Operation(OperationId("o3"), plantId, date, OperationDetails.Repot(lecaSubstrate, none))

      val rejectedCare  = operationStore.addOperation(laterCare)
      val rejectedRepot = operationStore.addOperation(laterRepot)

      assertEquals(rejectedCare, LogOperationResult.PlantArchived)
      assertEquals(rejectedRepot, LogOperationResult.PlantArchived)

  test("should round-trip a care operation without changing plant substrate"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val plantStore     = resource.plantStore
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care.copy(pesticides = Set(vertabId, neemOilId)))

      assertEquals(operationStore.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(queryString(dataSource, "select kind from operation where id = ?", operation.id.value), "Care")
      assertEquals(
        operationStore.getOperations(PlantId("p1"), fullWindow),
        GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false))
      )
      assertEquals(plantStore.getPlant(PlantId("p1")), GetPlantResult.Read(Plant(PlantId("p1"), defaultPlantDetails)))

  test("should page operations by timestamp descending with an identifier tie-breaker"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val first      = Operation(OperationId("o1"), PlantId("p1"), date, care)
      val second     = Operation(OperationId("o2"), PlantId("p1"), date.plusMillis(100), care)
      val third      = Operation(OperationId("o3"), PlantId("p1"), date.plusNanos(100_500_000), care)
      val fourth     = Operation(OperationId("o4"), PlantId("p1"), date.plusNanos(100_500_000), care)
      val operations = Vector(first, second, third, fourth)
      val logged     = operations.map(operationStore.addOperation)

      val firstPage  = operationStore.getOperations(PlantId("p1"), OperationWindow(offset = 0, size = 3))
      val secondPage = operationStore.getOperations(PlantId("p1"), OperationWindow(offset = 3, size = 3))
      val finalPage  = operationStore.getOperations(PlantId("p1"), OperationWindow(offset = 4, size = 3))

      val expectedFirst  = GetOperationsResult.Read(OperationPage(Vector(fourth, third, second), hasNextPage = true))
      val expectedSecond = GetOperationsResult.Read(OperationPage(Vector(first), hasNextPage = false))
      val expectedFinal  = GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false))
      assertEquals(logged, operations.map(operation => LogOperationResult.Logged(operation.id)))
      assertEquals(firstPage, expectedFirst)
      assertEquals(secondPage, expectedSecond)
      assertEquals(finalPage, expectedFinal)

  test("should read earliest and latest dates across all archived operations without decoding history"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val plantStore     = resource.plantStore
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "archived")
      seedPlant(dataSource, id = "other")
      val archivedId = PlantId("archived")
      val earliest   = date.minusSeconds(60)
      val latest     = date.plusSeconds(60)
      val recorded   = Vector.tabulate(12)(index =>
        Operation(OperationId(s"a-$index"), archivedId, date.plusSeconds(index.toLong), care)
      )
      val first          = Operation(OperationId("first"), archivedId, earliest, care)
      val last           = Operation(OperationId("last"), archivedId, latest, care)
      val otherOperation = Operation(OperationId("other"), PlantId("other"), date.plusSeconds(9999), care)
      val operations     = recorded :+ first :+ last
      val logged         = operations.map(operationStore.addOperation)
      val otherLog       = operationStore.addOperation(otherOperation)
      val archived       = plantStore.updatePlant(Plant(archivedId, defaultPlantDetails.copy(status = PlantStatus.Archived)))
      execute(dataSource, "update operation set payload = '{\"malformed\":true}' where id = ?", "a-5")

      val actual  = operationStore.getOperationDateRange(archivedId)
      val missing = operationStore.getOperationDateRange(PlantId("missing"))

      val expected = GetOperationDateRangeResult.Read(OperationDateRange.Recorded(earliest, latest))
      assertEquals(logged, operations.map(operation => LogOperationResult.Logged(operation.id)))
      assertEquals(otherLog, LogOperationResult.Logged(otherOperation.id))
      assertEquals(archived, UpdatePlantResult.Updated)
      assertEquals(actual, expected)
      assertEquals(missing, GetOperationDateRangeResult.PlantMissing)

  test("should return an empty date range for a plant without operations"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "archived", status = PlantStatus.Archived)

      val result = resource.operationStore.getOperationDateRange(PlantId("archived"))

      assertEquals(result, GetOperationDateRangeResult.Read(OperationDateRange.Empty))

  test("should show the same first and last date for one recorded operation"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val plantStore     = resource.plantStore
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "single")
      val single = Operation(OperationId("single"), PlantId("single"), date, care)

      val logged   = operationStore.addOperation(single)
      val archived = plantStore.updatePlant(Plant(single.plantId, defaultPlantDetails.copy(status = PlantStatus.Archived)))
      val actual   = operationStore.getOperationDateRange(single.plantId)

      val expected = GetOperationDateRangeResult.Read(OperationDateRange.Recorded(date, date))
      assertEquals(logged, LogOperationResult.Logged(single.id))
      assertEquals(archived, UpdatePlantResult.Updated)
      assertEquals(actual, expected)

  test("should order archived care dates by instant across timestamp precisions"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val plantStore     = resource.plantStore
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "archived")
      val plantId = PlantId("archived")
      val first   = Operation(OperationId("first"), plantId, date, care)
      val last    = Operation(OperationId("last"), plantId, date, care)

      val firstLog = operationStore.addOperation(first)
      val lastLog  = operationStore.addOperation(last)
      val archived = plantStore.updatePlant(Plant(plantId, defaultPlantDetails.copy(status = PlantStatus.Archived)))
      execute(dataSource, "update operation set date = '2026-01-01T00:00:00Z' where id = ?", first.id.value)
      execute(dataSource, "update operation set date = '2026-01-01T00:00:00.001Z' where id = ?", last.id.value)
      val actual = operationStore.getOperationDateRange(plantId)

      val expected = GetOperationDateRangeResult.Read(
        OperationDateRange.Recorded(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00.001Z"))
      )
      assertEquals(firstLog, LogOperationResult.Logged(first.id))
      assertEquals(lastLog, LogOperationResult.Logged(last.id))
      assertEquals(archived, UpdatePlantResult.Updated)
      assertEquals(actual, expected)

  test("should report a malformed operation date instead of presenting a partial range"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val operationStore = resource.operationStore
      val plantId        = PlantId("malformed")
      val first          = Operation(OperationId("first"), plantId, date.minusSeconds(60), care)
      val middle         = Operation(OperationId("middle"), plantId, date, care)
      val last           = Operation(OperationId("last"), plantId, date.plusSeconds(60), care)
      val operations     = Vector(first, middle, last)
      seedPlant(dataSource, id = plantId.value)

      val logged = operations.map(operationStore.addOperation)
      execute(dataSource, "update operation set date = '2026-01-01T00:00:00BAD' where id = ?", middle.id.value)
      val actual = operationStore.getOperationDateRange(plantId)

      assertEquals(logged, operations.map(operation => LogOperationResult.Logged(operation.id)))
      actual match
        case GetOperationDateRangeResult.ReadFailed(reason) =>
          assertEquals(reason.getMessage, "invalid stored operation date: 2026-01-01T00:00:00BAD")
        case other => fail(s"expected ReadFailed, got $other")

  test("should support operations against a freshly migrated operation schema"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val _ = Flyway.configure().dataSource(connection.dataSource).load().migrate()
      seedPlant(connection.dataSource, id = "p1")

      val details        = OperationDetails.Care(Set.empty, Set.empty, MoistureLevel.Wet, none)
      val older          = Operation(OperationId("o1"), PlantId("p1"), date, details)
      val newer          = Operation(OperationId("o2"), PlantId("p1"), date.plusMillis(100), details)
      val operationStore = SqliteOperationStore.make(connection.transactor)
      assertEquals(operationStore.addOperation(older), LogOperationResult.Logged(older.id))
      assertEquals(operationStore.addOperation(newer), LogOperationResult.Logged(newer.id))
      assertEquals(
        operationStore.getOperations(PlantId("p1"), fullWindow),
        GetOperationsResult.Read(OperationPage(Vector(newer, older), hasNextPage = false))
      )

  test("should round-trip a care observation without actions"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val observation = Operation(OperationId("o1"), PlantId("p1"), date, OperationDetails.Care(Set.empty, Set.empty, care.moisture, none))

      assertEquals(operationStore.addOperation(observation), LogOperationResult.Logged(observation.id))
      assertEquals(
        operationStore.getOperations(PlantId("p1"), fullWindow),
        GetOperationsResult.Read(OperationPage(Vector(observation), hasNextPage = false))
      )

  test("should persist a repot without changing the plant"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val plantStore     = resource.plantStore
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, OperationDetails.Repot(sand3to5Substrate, Note("new mix").some))

      assertEquals(operationStore.addOperation(operation), LogOperationResult.Logged(operation.id))
      assertEquals(queryString(dataSource, "select kind from operation where id = ?", operation.id.value), "Repot")
      assertEquals(operationStore.getOperation(operation.id), GetOperationResult.Read(operation))
      assertEquals(plantStore.getPlant(PlantId("p1")), GetPlantResult.Read(Plant(PlantId("p1"), defaultPlantDetails)))

  test("should reject unknown operation kinds and malformed JSON payloads"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "p1")

      val unknownKind = intercept[java.sql.SQLException]:
        execute(
          dataSource,
          "insert into operation (id, plant_id, date, kind, payload) values (?, ?, ?, ?, ?)",
          "unknown-kind",
          "p1",
          date.toString,
          "Unknown",
          "{}"
        )
      val malformedCarePayload = intercept[java.sql.SQLException]:
        execute(
          dataSource,
          "insert into operation (id, plant_id, date, kind, payload) values (?, ?, ?, ?, ?)",
          "malformed-payload",
          "p1",
          date.toString,
          "Care",
          "not-json"
        )
      val malformedRepotPayload = intercept[java.sql.SQLException]:
        execute(
          dataSource,
          "insert into operation (id, plant_id, date, kind, payload) values (?, ?, ?, ?, ?)",
          "malformed-repot",
          "p1",
          date.plusNanos(1).toString,
          "Repot",
          "not-json"
        )

      assert(unknownKind.getMessage.contains("CHECK constraint failed"))
      assert(malformedCarePayload.getMessage.contains("CHECK constraint failed"))
      assert(malformedRepotPayload.getMessage.contains("CHECK constraint failed"))

  test("should return no operation for an unknown id"):
    Using.resource(storeResource): resource =>
      val operationStore = resource.operationStore
      assertEquals(operationStore.getOperation(OperationId("missing")), GetOperationResult.RecordMissing)
      assertEquals(
        operationStore.getOperations(PlantId("missing"), fullWindow),
        GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false))
      )

  test("should fail when stored operation data is corrupt"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val repot = Operation(OperationId("o1"), PlantId("p1"), date, OperationDetails.Repot(perliteSubstrate, none))
      assertEquals(operationStore.addOperation(repot), LogOperationResult.Logged(repot.id))
      val repotPayload = s"""{"substrate":[{"component":"${perliteId.value}","share":60},{"component":"${perliteId.value}","share":60}]}"""
      execute(dataSource, "update operation set payload = ? where id = ?", repotPayload, "o1")

      val pageCorruption = intercept[DatabaseCorruption](operationStore.getOperations(PlantId("p1"), fullWindow))
      assertEquals(pageCorruption.err.getMessage, "invalid stored operation payload: DecodingFailure at .substrate: DuplicateComponent")

      val singleCorruption = intercept[DatabaseCorruption](operationStore.getOperation(repot.id))
      assertEquals(singleCorruption.err.getMessage, "invalid stored operation payload: DecodingFailure at .substrate: DuplicateComponent")

      val careOperation = Operation(OperationId("o2"), PlantId("p1"), date.plusNanos(1), care)
      assertEquals(operationStore.addOperation(careOperation), LogOperationResult.Logged(careOperation.id))
      val carePayload = """{"actions":["Unknown"],"pesticides":[],"moisture":"Wet","note":null}"""
      execute(dataSource, "update operation set payload = ? where id = ?", carePayload, careOperation.id.value)

      val invalidCare = intercept[DatabaseCorruption](operationStore.getOperation(careOperation.id))
      assertEquals(invalidCare.err.getMessage, "invalid stored operation payload: DecodingFailure at .actions[0]: invalid action: Unknown")

  test("should amend a repot without changing the plant"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val plantStore     = resource.plantStore
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val oldSubstrate = sand3to5Substrate
      val operation    = Operation(OperationId("o1"), PlantId("p1"), date, OperationDetails.Repot(oldSubstrate, none))
      val newSubstrate = lecaSubstrate
      val amended      = OperationDetails.Repot(newSubstrate, maybeNote = none)
      assertEquals(operationStore.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(operationStore.updateOperation(operation.id, amended), EditOperationResult.Edited(operation.copy(details = amended)))
      assertEquals(plantStore.getPlant(PlantId("p1")), GetPlantResult.Read(Plant(PlantId("p1"), defaultPlantDetails)))

  test("should remove and restore operations for compensation"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val original = Operation(OperationId("o1"), PlantId("p1"), date, care)
      val amended  = OperationDetails.Care(Set.empty, Set.empty, MoistureLevel.Dry, none)
      assertEquals(operationStore.addOperation(original), LogOperationResult.Logged(original.id))
      assertEquals(operationStore.updateOperation(original.id, amended), EditOperationResult.Edited(original.copy(details = amended)))

      assertEquals(operationStore.restoreOperation(original), OperationCompensationResult.Compensated)
      assertEquals(operationStore.getOperation(original.id), GetOperationResult.Read(original))
      assertEquals(operationStore.removeOperation(original.id), OperationCompensationResult.Compensated)
      assertEquals(operationStore.getOperation(original.id), GetOperationResult.RecordMissing)
      assertEquals(operationStore.removeOperation(original.id), OperationCompensationResult.Compensated)

  test("should report a missing operation when restoring for compensation"):
    Using.resource(storeResource): resource =>
      val missing = Operation(OperationId("missing"), PlantId("p1"), date, care)
      resource.operationStore.restoreOperation(missing) match
        case OperationCompensationResult.CompensationFailed(reason) => assertEquals(reason.getMessage, "operation not found while restoring: missing")
        case other                                                  => fail(s"expected CompensationFailed, got $other")

  test("should fail when edited operation metadata is corrupt"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val payload = """{"actions":[],"moisture":"Wet","note":null}"""
      execute(dataSource, "insert into operation (id, plant_id, date, kind, payload) values (?, ?, ?, ?, ?)", "o1", "p1", "today", "Care", payload)

      val failure = intercept[DatabaseCorruption](operationStore.updateOperation(OperationId("o1"), care))
      assertEquals(failure.err.getMessage, "invalid stored operation date: today")

  test("should reject logging an operation for an unknown plant"):
    Using.resource(storeResource): resource =>
      val missing = Operation(OperationId("o1"), PlantId("no-such-plant"), date, care)

      val result = resource.operationStore.addOperation(missing)

      assertEquals(result, LogOperationResult.PlantMissing)

  test("should report a logging failure when the write is rejected"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care)

      val firstWrite     = operationStore.addOperation(operation)
      val duplicateWrite = operationStore.addOperation(operation)

      assertEquals(firstWrite, LogOperationResult.Logged(operation.id))
      duplicateWrite match
        case LogOperationResult.LoggingFailed(_) => ()
        case other                               => fail(s"expected LoggingFailed, got $other")

  test("should report a missing operation when editing an unknown id"):
    Using.resource(storeResource): resource =>
      assertEquals(resource.operationStore.updateOperation(OperationId("nope"), care), EditOperationResult.OperationMissing)

  test("should edit an existing care operation without changing plant substrate"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val plantStore     = resource.plantStore
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care)
      val amended   = OperationDetails.Care(care.actions, care.pesticides, MoistureLevel.Dry, none)
      assertEquals(operationStore.addOperation(operation), LogOperationResult.Logged(operation.id))

      assertEquals(operationStore.updateOperation(operation.id, amended), EditOperationResult.Edited(operation.copy(details = amended)))
      assertEquals(plantStore.getPlant(PlantId("p1")), GetPlantResult.Read(Plant(PlantId("p1"), defaultPlantDetails)))

  test("should report write failures for operations when the database is read-only"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val operation = Operation(OperationId("o1"), PlantId("p1"), date, care)
      assertEquals(operationStore.addOperation(operation), LogOperationResult.Logged(operation.id))
      val readOnlyStore = SqliteOperationStore.make(Transactor(dataSource, connectionConfig = makeReadOnly))

      readOnlyStore.updateOperation(operation.id, care) match
        case EditOperationResult.EditFailed(_) => ()
        case other                             => fail(s"expected EditFailed, got $other")
      List(readOnlyStore.removeOperation(operation.id), readOnlyStore.restoreOperation(operation)).foreach:
        case OperationCompensationResult.CompensationFailed(_) => ()
        case other                                             => fail(s"expected CompensationFailed, got $other")

  test("should return read failures for operations when the schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val operationStore = SqliteOperationStore.make(connection.transactor)
      operationStore.getOperations(PlantId("p1"), fullWindow) match
        case GetOperationsResult.ReadFailed(_) => ()
        case other                             => fail(s"expected ReadFailed, got $other")
      operationStore.getOperation(OperationId("o1")) match
        case GetOperationResult.ReadFailed(_) => ()
        case other                            => fail(s"expected ReadFailed, got $other")

  test("should report a date-range read failure when the schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val operationStore = SqliteOperationStore.make(connection.transactor)
      operationStore.getOperationDateRange(PlantId("p1")) match
        case GetOperationDateRangeResult.ReadFailed(_) => ()
        case other                                     => fail(s"expected ReadFailed, got $other")

  final private case class StoreResource(
      connection: SqliteConnection,
      dataSource: DataSource,
      plantStore: PlantStore,
      operationStore: OperationStore
  ) extends AutoCloseable:
    override def close(): Unit = connection.close()

  private def storeResource =
    val connection = Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    val _          = Flyway.configure().dataSource(connection.dataSource).load().migrate()
    StoreResource(connection, connection.dataSource, SqlitePlantStore.make(connection.transactor), SqliteOperationStore.make(connection.transactor))

  private def makeReadOnly(connection: Connection) =
    val statement = connection.createStatement()
    val _         = statement.execute("PRAGMA query_only = ON")
    statement.close()

  private def seedPlant(
      dataSource: DataSource,
      id: String,
      status: PlantStatus = PlantStatus.Active,
      substrate: List[(SubstrateComponentId, Int)] = List(perliteId -> 100)
  ) =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement("insert into plant (id, species, nickname, location, status, substrate) values (?, ?, ?, ?, ?, ?)")
      statement.setString(1, id)
      statement.setString(2, "Ficus lyrata")
      statement.setNull(3, java.sql.Types.VARCHAR)
      statement.setString(4, "Balcony")
      statement.setString(5, status.toString)
      val substrateJson = substrate.map((component, share) => s"""{"component":"${component.value}","share":$share}""").mkString("[", ",", "]")
      statement.setString(6, substrateJson)
      val _ = statement.executeUpdate()
      statement.close()
    finally connection.close()

  private def execute(dataSource: DataSource, sql: String, parameters: String*) =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement(sql)
      try
        parameters.zipWithIndex.foreach((parameter, index) => statement.setString(index + 1, parameter))
        val _ = statement.executeUpdate()
      finally statement.close()
    finally connection.close()

  private def queryString(dataSource: DataSource, sql: String, parameters: String*) =
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
