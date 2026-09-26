package gardening.adapters.persistence

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.domain.operations.*
import gardening.domain.plants.*
import com.augustnagro.magnum.Transactor
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqlitePlantStoreSeamIntegrationTest extends FunSuite:

  private val date            = Instant.parse("2026-01-01T00:00:00Z")
  private val perliteId       = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val lecaId          = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000007"))
  private val fullWindow      = OperationWindow(offset = 0, size = 10)
  private val fullPhotoWindow = PhotoWindow(offset = 0, size = 10)
  private val photoId         = PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000001"))

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
      val expectedResult  = GetPlantResult.Read(Plant(PlantId("p1"), expectedDetails))

      assertEquals(plantStore.getPlant(PlantId("p1")), expectedResult)
      assertEquals(plantStore.getPlant(PlantId("missing")), GetPlantResult.RecordMissing)

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
      assertEquals(firstLog, LogOperationResult.Logged(operation.id))
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

  test("should apply the plant and photo schema migrations"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val migration = Flyway.configure().dataSource(connection.dataSource).load()
      val _         = migration.migrate()
      seedPlant(connection.dataSource, id = "p1")

      val photo      = PlantPhoto(photoId, PlantId("p1"), date)
      val plantStore = SqlitePlantStore.make(connection.transactor)
      assertEquals(
        migration.info().applied().toVector.map(_.getVersion),
        Vector(
          MigrationVersion.fromVersion("1"),
          MigrationVersion.fromVersion("2"),
          MigrationVersion.fromVersion("3"),
          MigrationVersion.fromVersion("4"),
          MigrationVersion.fromVersion("5")
        )
      )
      assertEquals(plantStore.addPhoto(photo), AddPhotoResult.Added(photo))
      assertEquals(plantStore.getPhotos(PlantId("p1"), fullPhotoWindow), GetPhotosResult.Read(PhotoPage(Vector(photo), hasNextPage = false)))

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

  test("should report write failures for plants and photos when the database is read-only"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "p1")
      val readOnlyStore = SqlitePlantStore.make(Transactor(dataSource, connectionConfig = makeReadOnly))

      readOnlyStore.updatePlant(Plant(PlantId("p1"), defaultPlantDetails)) match
        case UpdatePlantResult.UpdateFailed(_) => ()
        case other                             => fail(s"expected UpdateFailed, got $other")
      readOnlyStore.addPhoto(PlantPhoto(photoId, PlantId("p1"), date)) match
        case AddPhotoResult.AddFailed(_) => ()
        case other                       => fail(s"expected AddFailed, got $other")
      readOnlyStore.removePhoto(photoId) match
        case RemovePhotoResult.RemoveFailed(_) => ()
        case other                             => fail(s"expected RemoveFailed, got $other")

  test("should return read failures for plants, attention samples, and photos when the schema is unavailable"):
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
      plantStore.getPhotos(PlantId("p1"), fullPhotoWindow) match
        case GetPhotosResult.ReadFailed(_) => ()
        case other                         => fail(s"expected ReadFailed, got $other")

  test("should add a photo for a plant and retrieve it"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val plantStore = resource.plantStore
      seedPlant(dataSource, id = "p1")
      val photo = PlantPhoto(photoId, PlantId("p1"), date)

      val addResult = plantStore.addPhoto(photo)
      val getResult = plantStore.getPhotos(PlantId("p1"), fullPhotoWindow)

      assertEquals(addResult, AddPhotoResult.Added(photo))
      assertEquals(getResult, GetPhotosResult.Read(PhotoPage(Vector(photo), hasNextPage = false)))

  test("should return PlantMissing when adding a photo for an unknown plant"):
    Using.resource(storeResource): resource =>
      val plantStore = resource.plantStore
      val photo      = PlantPhoto(photoId, PlantId("no-such-plant"), date)

      assertEquals(plantStore.addPhoto(photo), AddPhotoResult.PlantMissing)

  test("should remove a photo and return the removed record"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val plantStore = resource.plantStore
      seedPlant(dataSource, id = "p1")
      val photo = PlantPhoto(photoId, PlantId("p1"), date)
      assertEquals(plantStore.addPhoto(photo), AddPhotoResult.Added(photo))

      val removed   = plantStore.removePhoto(photoId)
      val afterList = plantStore.getPhotos(PlantId("p1"), fullPhotoWindow)

      assertEquals(removed, RemovePhotoResult.Removed(photo))
      assertEquals(afterList, GetPhotosResult.Read(PhotoPage(Vector.empty, hasNextPage = false)))

  test("should return PhotoMissing when removing an unknown photo"):
    Using.resource(storeResource): resource =>
      assertEquals(resource.plantStore.removePhoto(photoId), RemovePhotoResult.PhotoMissing)

  test("should page photos by captured_at descending with an id tie-breaker"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val plantStore = resource.plantStore
      seedPlant(dataSource, id = "p1")
      val earliest = PlantPhoto(PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000001")), PlantId("p1"), date)
      val middle1  = PlantPhoto(PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000002")), PlantId("p1"), date.plusMillis(100))
      val middle2  = PlantPhoto(PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000003")), PlantId("p1"), date.plusMillis(100))
      val latest   = PlantPhoto(PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000004")), PlantId("p1"), date.plusMillis(200))
      Vector(earliest, middle1, middle2, latest).foreach(plantStore.addPhoto)

      val firstPage  = plantStore.getPhotos(PlantId("p1"), PhotoWindow(offset = 0, size = 3))
      val secondPage = plantStore.getPhotos(PlantId("p1"), PhotoWindow(offset = 3, size = 3))

      // order: latest (desc time), middle2 then middle1 (same time, desc id), earliest
      assertEquals(firstPage, GetPhotosResult.Read(PhotoPage(Vector(latest, middle2, middle1), hasNextPage = true)))
      assertEquals(secondPage, GetPhotosResult.Read(PhotoPage(Vector(earliest), hasNextPage = false)))

  test("should report corrupt stored photo data as a read failure"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val plantStore = resource.plantStore
      seedPlant(dataSource, id = "p1")
      val photo = PlantPhoto(photoId, PlantId("p1"), date)
      assertEquals(plantStore.addPhoto(photo), AddPhotoResult.Added(photo))
      execute(dataSource, "update plant_photo set captured_at = ? where id = ?", "not-an-instant", photoId.value.toString)

      plantStore.getPhotos(PlantId("p1"), fullPhotoWindow) match
        case GetPhotosResult.ReadFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored photo capturedAt: not-an-instant")
        case other => fail(s"expected ReadFailed(DatabaseCorruption), got $other")
      plantStore.removePhoto(photoId) match
        case RemovePhotoResult.RemoveFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored photo capturedAt: not-an-instant")
        case other => fail(s"expected RemoveFailed(DatabaseCorruption), got $other")

  test("should report a corrupt stored photo id as a read failure"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val plantStore = resource.plantStore
      seedPlant(dataSource, id = "p1")
      val invalidId = "z" * 36
      execute(
        dataSource,
        "insert into plant_photo (id, plant_id, captured_at) values (?, ?, ?)",
        invalidId,
        "p1",
        date.toString
      )

      plantStore.getPhotos(PlantId("p1"), fullPhotoWindow) match
        case GetPhotosResult.ReadFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, s"invalid stored photo id: $invalidId")
        case other => fail(s"expected ReadFailed(DatabaseCorruption), got $other")

  test("should return an empty photo list for an unknown plant"):
    Using.resource(storeResource): resource =>
      val result = resource.plantStore.getPhotos(PlantId("missing"), fullPhotoWindow)
      assertEquals(result, GetPhotosResult.Read(PhotoPage(Vector.empty, hasNextPage = false)))

  test("should report an archived count read failure when the schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val plantStore  = SqlitePlantStore.make(connection.transactor)
      val countResult = plantStore.getArchivedCount

      countResult match
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

  private def makeReadOnly(connection: Connection) =
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
      substrate: List[(SubstrateComponentId, Int)] = List(perliteId -> 100)
  ) =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement("insert into plant (id, species, nickname, location, status, substrate) values (?, ?, ?, ?, ?, ?)")
      statement.setString(1, id)
      statement.setString(2, species)
      maybeNickname.fold(statement.setNull(3, java.sql.Types.VARCHAR))(nickname => statement.setString(3, nickname))
      statement.setString(4, location)
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
