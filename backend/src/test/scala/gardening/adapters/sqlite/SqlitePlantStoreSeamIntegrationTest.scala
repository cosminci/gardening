package gardening.adapters.sqlite

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.domain.operations.*
import gardening.domain.plants.*
import gardening.ports.{PlantStore, PlantAttentionStore, OperationStore}
import gardening.adapters.sqlite.SqliteHelpers.{execute, makeReadOnly, perliteId, seedPlant}
import com.augustnagro.magnum.Transactor
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import org.flywaydb.core.Flyway

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqlitePlantStoreSeamIntegrationTest extends FunSuite:

  private val date       = Instant.parse("2026-01-01T00:00:00Z")
  private val lecaId     = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000007"))
  private val fullWindow = OperationWindow(offset = 0, size = 10)

  private val perliteSubstrate    = Substrate.of(List(SubstratePart(perliteId, share = 100))).getOrElse(fail("invalid perlite substrate"))
  private val lecaSubstrate       = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid LECA substrate"))
  private val defaultPlantDetails = PlantDetails(Species("Ficus lyrata"), none, Location("Balcony"), perliteSubstrate, PlantStatus.Active)
  private val care = OperationDetails.Care(Set(ActionType.Watered, ActionType.Fertilized), Set.empty, MoistureLevel.Wet, Note("a little dry").some)

  test("should persist a new active plant without operations"):
    Using.resource(storeResource): resource =>
      val plantStore     = resource.plantStore
      val operationStore = resource.operationStore
      val created        = Plant(PlantId("new"), defaultPlantDetails)

      val result     = plantStore.addPlant(created)
      val plants     = plantStore.getPlants(PlantStatus.Active)
      val operations = operationStore.getOperations(created.id, fullWindow)

      assertEquals(result, AddPlantResult.Added)
      assertEquals(plants, GetPlantsResult.Read(Vector(created)))
      assertEquals(operations, GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false)))

  test("should return a plant with its persisted substrate"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val plantStore = resource.plantStore
      seedPlant(dataSource, id = "p1", maybeNickname = "Fig".some, substrate = List(perliteId -> 100))

      val expectedDetails = PlantDetails(Species("Ficus lyrata"), Nickname("Fig").some, Location("Balcony"), perliteSubstrate, PlantStatus.Active)

      val found   = plantStore.getPlant(PlantId("p1"))
      val missing = plantStore.getPlant(PlantId("missing"))

      assertEquals(found, GetPlantResult.Read(Plant(PlantId("p1"), expectedDetails)))
      assertEquals(missing, GetPlantResult.RecordMissing)

  test("should list plants by status without decoding records in the other view"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val plantStore = resource.plantStore
      seedPlant(dataSource, id = "active")
      seedPlant(dataSource, id = "archived", status = PlantStatus.Archived)

      val activePlant   = Plant(PlantId("active"), defaultPlantDetails)
      val archivedPlant = Plant(PlantId("archived"), defaultPlantDetails.copy(status = PlantStatus.Archived))

      val activeBefore   = plantStore.getPlants(PlantStatus.Active)
      val archivedBefore = plantStore.getPlants(PlantStatus.Archived)
      execute(dataSource, "update plant set substrate = '[]' where id = ?", "archived")

      val activeAfter   = plantStore.getPlants(PlantStatus.Active)
      val archivedAfter = plantStore.getPlants(PlantStatus.Archived)

      assertEquals(activeBefore, GetPlantsResult.Read(Vector(activePlant)))
      assertEquals(archivedBefore, GetPlantsResult.Read(Vector(archivedPlant)))
      assertEquals(activeAfter, GetPlantsResult.Read(Vector(activePlant)))
      archivedAfter match
        case GetPlantsResult.ReadFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored substrate: DecodingFailure at : Empty")
        case other => fail(s"expected ReadFailed, got $other")

  test("should count zero archived plants in an empty store"):
    Using.resource(storeResource): resource =>
      val plantStore = resource.plantStore
      val result     = plantStore.getArchivedCount

      assertEquals(result, ArchivedCountResult.Counted(0))

  test("should count archived plants without decoding their details"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "active")
      seedPlant(dataSource, id = "archived", status = PlantStatus.Archived)
      execute(dataSource, "update plant set substrate = '[]' where id = ?", "archived")

      val plantStore  = resource.plantStore
      val countResult = plantStore.getArchivedCount
      val plantResult = plantStore.getPlants(PlantStatus.Active)

      assertEquals(countResult, ArchivedCountResult.Counted(1))
      assertEquals(plantResult, GetPlantsResult.Read(Vector(Plant(PlantId("active"), defaultPlantDetails))))

  test("should persist an active plant's archived status and retain readable operation history"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "p1")
      val plantId        = PlantId("p1")
      val operation      = Operation(OperationId("o1"), plantId, date, care)
      val plantStore     = resource.plantStore
      val operationStore = resource.operationStore
      val firstLog       = operationStore.addOperation(operation)

      val archivedPlant = Plant(plantId, defaultPlantDetails.copy(status = PlantStatus.Archived))
      val archived      = plantStore.updatePlant(archivedPlant)
      val missing       = plantStore.updatePlant(archivedPlant.copy(id = PlantId("unknown")))
      val staleUpdate   = plantStore.updatePlant(Plant(plantId, defaultPlantDetails))
      val archivedCount = plantStore.getArchivedCount
      val history       = operationStore.getOperations(plantId, fullWindow)
      val activePlants  = plantStore.getPlants(PlantStatus.Active)

      val expectedHistory = GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false))
      assertEquals(firstLog, AddOperationResult.Logged(operation.id))
      assertEquals(archived, UpdatePlantResult.Updated)
      missing match
        case UpdatePlantResult.UpdateFailed(_) => ()
        case other                             => fail(s"expected UpdateFailed, got $other")
      staleUpdate match
        case UpdatePlantResult.UpdateFailed(_) => ()
        case other                             => fail(s"expected UpdateFailed, got $other")
      assertEquals(archivedCount, ArchivedCountResult.Counted(1))
      assertEquals(history, expectedHistory)
      assertEquals(activePlants, GetPlantsResult.Read(Vector.empty))
      assertEquals(plantStore.getPlant(plantId), GetPlantResult.Read(archivedPlant))

  test("should report corrupt stored plant details as a read failure"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val plantStore = resource.plantStore
      seedPlant(dataSource, id = "duplicate-components", substrate = List(perliteId -> 60, perliteId -> 60))

      val result = plantStore.getPlant(PlantId("duplicate-components"))

      result match
        case GetPlantResult.ReadFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored substrate: DecodingFailure at : DuplicateComponent")
        case other => fail(s"expected ReadFailed, got $other")

  test("should update every editable plant detail"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "p1")
      val updatedSubstrate = lecaSubstrate
      val updatedNickname  = Nickname("Monty").some
      val updatedDetails   =
        PlantDetails(Species("Monstera deliciosa"), updatedNickname, Location("Living room"), updatedSubstrate, PlantStatus.Active)
      val updatedPlant = Plant(PlantId("p1"), updatedDetails)

      val plantStore = resource.plantStore
      val updated    = plantStore.updatePlant(updatedPlant)
      val stored     = plantStore.getPlant(updatedPlant.id)
      val activeList = plantStore.getPlants(PlantStatus.Active)

      assertEquals(updated, UpdatePlantResult.Updated)
      assertEquals(stored, GetPlantResult.Read(updatedPlant))
      assertEquals(activeList, GetPlantsResult.Read(Vector(updatedPlant)))

  test("should preserve archived status when editing details and reject stale active updates"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "p1", status = PlantStatus.Archived)
      val activePlant   = Plant(PlantId("p1"), defaultPlantDetails)
      val archivedPlant = activePlant.copy(details = activePlant.details.copy(substrate = lecaSubstrate, status = PlantStatus.Archived))

      val plantStore     = resource.plantStore
      val rejectedUpdate = plantStore.updatePlant(activePlant)
      val updated        = plantStore.updatePlant(archivedPlant)
      val stored         = plantStore.getPlant(archivedPlant.id)

      rejectedUpdate match
        case UpdatePlantResult.UpdateFailed(reason) => assertEquals(reason.getMessage, "plant not found while updating: p1")
        case other                                  => fail(s"expected UpdateFailed, got $other")
      assertEquals(updated, UpdatePlantResult.Updated)
      assertEquals(stored, GetPlantResult.Read(archivedPlant))

  test("should report a missing plant when updating an unknown id"):
    Using.resource(storeResource): resource =>
      resource.plantStore.updatePlant(Plant(PlantId("missing"), defaultPlantDetails)) match
        case UpdatePlantResult.UpdateFailed(reason) => assertEquals(reason.getMessage, "plant not found while updating: missing")
        case other                                  => fail(s"expected UpdateFailed, got $other")

  test("should report a write failure for plants when the database is read-only"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "p1")
      val readOnlyStore = SqlitePlantStore.make(Transactor(dataSource, connectionConfig = makeReadOnly))

      readOnlyStore.updatePlant(Plant(PlantId("p1"), defaultPlantDetails)) match
        case UpdatePlantResult.UpdateFailed(_) => ()
        case other                             => fail(s"expected UpdateFailed, got $other")

  test("should return read failures for plants, attention samples, and archived count when the schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val plantStore = SqlitePlantStore.make(connection.transactor)
      plantStore.addPlant(Plant(PlantId("new"), defaultPlantDetails)) match
        case AddPlantResult.AddFailed(_) => ()
        case other                       => fail(s"expected AddFailed, got $other")
      plantStore.getPlant(PlantId("p1")) match
        case GetPlantResult.ReadFailed(_) => ()
        case other                        => fail(s"expected ReadFailed, got $other")
      plantStore.getPlants(PlantStatus.Active) match
        case GetPlantsResult.ReadFailed(_) => ()
        case other                         => fail(s"expected ReadFailed, got $other")
      plantStore.getAttentionSamples(size = 20) match
        case GetAttentionSamplesResult.ReadFailed(_) => ()
        case other                                   => fail(s"expected ReadFailed, got $other")
      plantStore.getArchivedCount match
        case ArchivedCountResult.ReadFailed(_) => ()
        case other                             => fail(s"expected ReadFailed, got $other")

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
