package gardening.adapters.sqlite

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.domain.operations.*
import gardening.domain.plants.*
import gardening.ports.{PlantStore, PlantAttentionStore, OperationStore}
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import org.flywaydb.core.Flyway

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqlitePlantAttentionStoreSeamIntegrationTest extends FunSuite:

  private val date      = Instant.parse("2026-01-01T00:00:00Z")
  private val perliteId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))

  private val perliteSubstrate    = Substrate.of(List(SubstratePart(perliteId, share = 100))).getOrElse(fail("invalid perlite substrate"))
  private val defaultPlantDetails = PlantDetails(Species("Ficus lyrata"), none, Location("Balcony"), perliteSubstrate, PlantStatus.Active)
  private val care = OperationDetails.Care(Set(ActionType.Watered, ActionType.Fertilized), Set.empty, MoistureLevel.Wet, Note("a little dry").some)

  test("should return no attention samples when none have been seeded"):
    Using.resource(storeResource): resource =>
      assertEquals(resource.plantStore.getAttentionSamples(size = 20), GetAttentionSamplesResult.Read(Vector.empty))

  test("should read bounded watering dates for every active plant"):
    Using.resource(storeResource): resource =>
      val dataSource      = resource.dataSource
      val plantStore      = resource.plantStore
      val operationStore  = resource.operationStore
      val firstPlantId    = PlantId("p1")
      val secondPlantId   = PlantId("p2")
      val archivedPlantId = PlantId("archived")
      seedPlant(dataSource, id = firstPlantId.value)
      seedPlant(dataSource, id = secondPlantId.value)
      seedPlant(dataSource, id = archivedPlantId.value)
      seedPlant(dataSource, id = "no-waterings")

      val firstWaterings = Vector.tabulate(22): index =>
        Operation(OperationId(f"first-$index%02d"), firstPlantId, date.plusSeconds(index.toLong), care)
      val secondWaterings = Vector.tabulate(3): index =>
        Operation(OperationId(f"second-$index%02d"), secondPlantId, date, care)
      val archivedWatering = Operation(OperationId("archived-watering"), archivedPlantId, date, care)
      val nonWatering      = Operation(OperationId("care-only"), firstPlantId, date.plusSeconds(30), care.copy(actions = Set(ActionType.Pruned)))
      val operations       = firstWaterings ++ secondWaterings :+ archivedWatering :+ nonWatering
      val logged           = operations.map(operationStore.addOperation)
      val archived         = plantStore.updatePlant(Plant(archivedPlantId, defaultPlantDetails.copy(status = PlantStatus.Archived)))
      val actual           = plantStore.getAttentionSamples(size = 20)

      val firstWateringHistory  = WateringHistory.from(firstWaterings.reverse.take(20).map(_.date)).fold(message => fail(message), identity)
      val secondWateringHistory = WateringHistory.from(secondWaterings.reverse.map(_.date)).fold(message => fail(message), identity)
      val emptyWateringHistory  = WateringHistory.from(Vector.empty).fold(message => fail(message), identity)
      val expectedSamples       = Vector(
        PlantAttentionSample(firstPlantId, firstWateringHistory),
        PlantAttentionSample(secondPlantId, secondWateringHistory),
        PlantAttentionSample(PlantId("no-waterings"), emptyWateringHistory)
      )
      assertEquals(logged, operations.map(operation => LogOperationResult.Logged(operation.id)))
      assertEquals(archived, UpdatePlantResult.Updated)
      assertEquals(actual, GetAttentionSamplesResult.Read(expectedSamples))

  test("should report malformed watering dates through the attention read"):
    Using.resource(storeResource): resource =>
      val dataSource     = resource.dataSource
      val plantStore     = resource.plantStore
      val operationStore = resource.operationStore
      seedPlant(dataSource, id = "p1")
      val watering = Operation(OperationId("watering"), PlantId("p1"), date, care)
      assertEquals(operationStore.addOperation(watering), LogOperationResult.Logged(watering.id))
      execute(dataSource, "update operation set date = 'today' where id = ?", watering.id.value)

      plantStore.getAttentionSamples(size = 20) match
        case GetAttentionSamplesResult.ReadFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored operation date: today")
        case other => fail(s"expected ReadFailed, got $other")

  test("should exclude archived plants from the attention read"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val plantStore = resource.plantStore
      seedPlant(dataSource, id = "archived", status = PlantStatus.Archived)
      execute(dataSource, "update plant set substrate = '[]' where id = ?", "archived")

      assertEquals(plantStore.getAttentionSamples(size = 20), GetAttentionSamplesResult.Read(Vector.empty))

  final private case class StoreResource(
      connection: SqliteConnection,
      dataSource: DataSource,
      plantStore: PlantStore & PlantAttentionStore,
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
