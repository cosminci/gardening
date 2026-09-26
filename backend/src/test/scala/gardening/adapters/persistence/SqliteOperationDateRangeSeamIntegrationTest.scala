package gardening.adapters.persistence

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.operations.*
import gardening.domain.plants.*
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import org.flywaydb.core.Flyway

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqliteOperationDateRangeSeamIntegrationTest extends FunSuite:

  private val date      = Instant.parse("2026-01-01T00:00:00Z")
  private val perliteId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))

  private val perliteSubstrate    = Substrate.of(List(SubstratePart(perliteId, share = 100))).getOrElse(fail("invalid perlite substrate"))
  private val defaultPlantDetails = PlantDetails(Species("Ficus lyrata"), none, Location("Balcony"), perliteSubstrate, PlantStatus.Active)
  private val care = OperationDetails.Care(Set(ActionType.Watered, ActionType.Fertilized), Set.empty, MoistureLevel.Wet, Note("a little dry").some)

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
