package gardening.adapters.sqlite

import gardening.domain.*
import gardening.domain.plants.*
import gardening.ports.PhotoStore
import gardening.adapters.sqlite.SqliteHelpers.{execute, makeReadOnly, seedPlant}
import com.augustnagro.magnum.Transactor
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import org.flywaydb.core.Flyway

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqlitePhotoStoreSeamIntegrationTest extends FunSuite:

  private val date            = Instant.parse("2026-01-01T00:00:00Z")
  private val fullPhotoWindow = PhotoWindow(offset = 0, size = 10)
  private val photoId         = PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000001"))

  test("should support photos against a freshly migrated plant schema"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val _ = Flyway.configure().dataSource(connection.dataSource).load().migrate()
      seedPlant(connection.dataSource, id = "p1")

      val photo      = PlantPhoto(photoId, PlantId("p1"), date)
      val photoStore = SqlitePhotoStore.make(connection.transactor)
      assertEquals(photoStore.addPhoto(photo), AddPhotoResult.Added(photo))
      assertEquals(photoStore.getPhotos(PlantId("p1"), fullPhotoWindow), GetPhotosResult.Read(PhotoPage(Vector(photo), hasNextPage = false)))

  test("should report a write failure when the database is read-only"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      seedPlant(dataSource, id = "p1")
      val readOnlyStore = SqlitePhotoStore.make(Transactor(dataSource, connectionConfig = makeReadOnly))

      readOnlyStore.addPhoto(PlantPhoto(photoId, PlantId("p1"), date)) match
        case AddPhotoResult.AddFailed(_) => ()
        case other                       => fail(s"expected AddFailed, got $other")
      readOnlyStore.removePhoto(photoId) match
        case RemovePhotoResult.RemoveFailed(_) => ()
        case other                             => fail(s"expected RemoveFailed, got $other")

  test("should return a read failure when the schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val photoStore = SqlitePhotoStore.make(connection.transactor)
      photoStore.getPhotos(PlantId("p1"), fullPhotoWindow) match
        case GetPhotosResult.ReadFailed(_) => ()
        case other                         => fail(s"expected ReadFailed, got $other")

  test("should add a photo for a plant and retrieve it"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val photoStore = resource.photoStore
      seedPlant(dataSource, id = "p1")
      val photo = PlantPhoto(photoId, PlantId("p1"), date)

      val addResult = photoStore.addPhoto(photo)
      val getResult = photoStore.getPhotos(PlantId("p1"), fullPhotoWindow)

      assertEquals(addResult, AddPhotoResult.Added(photo))
      assertEquals(getResult, GetPhotosResult.Read(PhotoPage(Vector(photo), hasNextPage = false)))

  test("should return PlantMissing when adding a photo for an unknown plant"):
    Using.resource(storeResource): resource =>
      val photoStore = resource.photoStore
      val photo      = PlantPhoto(photoId, PlantId("no-such-plant"), date)

      assertEquals(photoStore.addPhoto(photo), AddPhotoResult.PlantMissing)

  test("should remove a photo and return the removed record"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val photoStore = resource.photoStore
      seedPlant(dataSource, id = "p1")
      val photo = PlantPhoto(photoId, PlantId("p1"), date)
      assertEquals(photoStore.addPhoto(photo), AddPhotoResult.Added(photo))

      val removed   = photoStore.removePhoto(photoId)
      val afterList = photoStore.getPhotos(PlantId("p1"), fullPhotoWindow)

      assertEquals(removed, RemovePhotoResult.Removed(photo))
      assertEquals(afterList, GetPhotosResult.Read(PhotoPage(Vector.empty, hasNextPage = false)))

  test("should return PhotoMissing when removing an unknown photo"):
    Using.resource(storeResource): resource =>
      assertEquals(resource.photoStore.removePhoto(photoId), RemovePhotoResult.PhotoMissing)

  test("should page photos by captured_at descending with an id tie-breaker"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val photoStore = resource.photoStore
      seedPlant(dataSource, id = "p1")
      val earliest = PlantPhoto(PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000001")), PlantId("p1"), date)
      val middle1  = PlantPhoto(PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000002")), PlantId("p1"), date.plusMillis(100))
      val middle2  = PlantPhoto(PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000003")), PlantId("p1"), date.plusMillis(100))
      val latest   = PlantPhoto(PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000004")), PlantId("p1"), date.plusMillis(200))
      Vector(earliest, middle1, middle2, latest).foreach(photoStore.addPhoto)

      val firstPage  = photoStore.getPhotos(PlantId("p1"), PhotoWindow(offset = 0, size = 3))
      val secondPage = photoStore.getPhotos(PlantId("p1"), PhotoWindow(offset = 3, size = 3))

      // order: latest (desc time), middle2 then middle1 (same time, desc id), earliest
      assertEquals(firstPage, GetPhotosResult.Read(PhotoPage(Vector(latest, middle2, middle1), hasNextPage = true)))
      assertEquals(secondPage, GetPhotosResult.Read(PhotoPage(Vector(earliest), hasNextPage = false)))

  test("should report corrupt stored photo data as a read failure"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val photoStore = resource.photoStore
      seedPlant(dataSource, id = "p1")
      val photo = PlantPhoto(photoId, PlantId("p1"), date)
      assertEquals(photoStore.addPhoto(photo), AddPhotoResult.Added(photo))
      execute(dataSource, "update plant_photo set captured_at = ? where id = ?", "not-an-instant", photoId.value.toString)

      photoStore.getPhotos(PlantId("p1"), fullPhotoWindow) match
        case GetPhotosResult.ReadFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored photo capturedAt: not-an-instant")
        case other => fail(s"expected ReadFailed(DatabaseCorruption), got $other")
      photoStore.removePhoto(photoId) match
        case RemovePhotoResult.RemoveFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored photo capturedAt: not-an-instant")
        case other => fail(s"expected RemoveFailed(DatabaseCorruption), got $other")

  test("should report a corrupt stored photo id as a read failure"):
    Using.resource(storeResource): resource =>
      val dataSource = resource.dataSource
      val photoStore = resource.photoStore
      seedPlant(dataSource, id = "p1")
      val invalidId = "z" * 36
      val command   = "insert into plant_photo (id, plant_id, captured_at) values (?, ?, ?)"
      execute(dataSource, command, invalidId, "p1", date.toString)

      photoStore.getPhotos(PlantId("p1"), fullPhotoWindow) match
        case GetPhotosResult.ReadFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, s"invalid stored photo id: $invalidId")
        case other => fail(s"expected ReadFailed(DatabaseCorruption), got $other")

  test("should return an empty photo list for an unknown plant"):
    Using.resource(storeResource): resource =>
      val result = resource.photoStore.getPhotos(PlantId("missing"), fullPhotoWindow)
      assertEquals(result, GetPhotosResult.Read(PhotoPage(Vector.empty, hasNextPage = false)))

  final private case class StoreResource(connection: SqliteConnection, dataSource: DataSource, photoStore: PhotoStore) extends AutoCloseable:
    override def close(): Unit = connection.close()

  private def storeResource =
    val connection = Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    val _          = Flyway.configure().dataSource(connection.dataSource).load().migrate()
    StoreResource(connection, connection.dataSource, SqlitePhotoStore.make(connection.transactor))
