package gardening.usecases

import gardening.domain.*
import gardening.domain.plants.*
import gardening.domain.plants.PhotoMediaType.*
import gardening.ports.{PlantStore, PhotoContentStore}
import gardening.capabilities.TestImplicits
import io.github.iltotore.iron.autoRefine
import scodec.bits.ByteVector

import language.experimental.captureChecking

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class PhotoManagerComponentTest extends munit.FunSuite with TestImplicits:

  private val date              = Instant.parse("2026-01-01T00:00:00Z")
  private val plantId           = PlantId("p1")
  private val photoUuid         = UUID.fromString("00000000-0000-4000-8002-000000000001")
  private val photo             = PlantPhoto(PhotoId(photoUuid), plantId, date)
  private val photoContent      = PhotoContent(ByteVector(Array[Byte](1, 2, 3)), Jpeg)
  private val expectedThumbnail = PhotoContent(ByteVector(Array[Byte](9, 9, 9)), Jpeg)
  private val firstPhotoPage    = PhotoWindow(offset = 0, size = 3)

  test("should store photo content then metadata and return the added photo with its server-assigned timestamp"):
    val captureInstant = date.plusSeconds(5)
    val expectedPhoto  = PlantPhoto(PhotoId(photoUuid), plantId, captureInstant)
    val refs           = Refs()

    val result = buildManager(
      refs,
      addPhotoResult = AddPhotoResult.Added(expectedPhoto),
      captureTime = captureInstant
    ).addPhoto(plantId, photoContent)

    assertEquals(result, AddPhotoResult.Added(expectedPhoto))
    assertEquals(refs.putPhotoContents.get(), Vector((PhotoId(photoUuid), photoContent, expectedThumbnail)))
    assertEquals(refs.addedPhotos.get(), Vector(expectedPhoto))

  test("should fail the upload without writing anything when a thumbnail cannot be derived"):
    val reason = RuntimeException("no image reader available for this content")
    val refs   = Refs()

    val result = buildManager(refs, thumbnailResult = ThumbnailDerivationResult.DerivationFailed(reason)).addPhoto(plantId, photoContent)

    assertEquals(result, AddPhotoResult.AddFailed(reason))
    assertEquals(refs.putPhotoContents.get(), Vector.empty)
    assertEquals(refs.addedPhotos.get(), Vector.empty)

  test("should surface a content write failure without touching metadata"):
    val cause = RuntimeException("disk full")
    val refs  = Refs()

    val result = buildManager(refs, putContentResult = PhotoWriteResult.WriteFailed(cause)).addPhoto(plantId, photoContent)

    assertEquals(result, AddPhotoResult.AddFailed(cause))
    assertEquals(refs.addedPhotos.get(), Vector.empty)

  test("should compensate by deleting content when metadata write fails"):
    val cause = RuntimeException("metadata store down")
    val refs  = Refs()

    val result = buildManager(refs, addPhotoResult = AddPhotoResult.AddFailed(cause)).addPhoto(plantId, photoContent)

    assertEquals(result, AddPhotoResult.AddFailed(cause))
    assertEquals(refs.deletedPhotoContentIds.get(), Vector(PhotoId(photoUuid)))

  test("should compensate by deleting content when plant is missing during metadata write"):
    val refs = Refs()

    val result = buildManager(refs, addPhotoResult = AddPhotoResult.PlantMissing).addPhoto(plantId, photoContent)

    assertEquals(result, AddPhotoResult.PlantMissing)
    assertEquals(refs.deletedPhotoContentIds.get(), Vector(PhotoId(photoUuid)))

  test("should report both failures when metadata write fails and compensation content delete also fails"):
    val primary      = RuntimeException("metadata write failed")
    val compensation = RuntimeException("content delete failed too")
    val refs         = Refs()

    buildManager(
      refs,
      addPhotoResult = AddPhotoResult.AddFailed(primary),
      deleteContentResult = PhotoWriteResult.WriteFailed(compensation)
    ).addPhoto(plantId, photoContent) match
      case AddPhotoResult.AddFailed(reason) =>
        val actualSuppressed = reason.getSuppressed.toList
        assertEquals(reason.getCause, primary)
        assertEquals(actualSuppressed, List(compensation))
      case other => fail(s"expected AddFailed, got $other")

  test("should report both failures when plant is missing and compensation content delete also fails"):
    val compensation = RuntimeException("content delete failed too")
    val refs         = Refs()

    buildManager(
      refs,
      addPhotoResult = AddPhotoResult.PlantMissing,
      deleteContentResult = PhotoWriteResult.WriteFailed(compensation)
    ).addPhoto(plantId, photoContent) match
      case AddPhotoResult.AddFailed(reason) =>
        val actualSuppressed = reason.getSuppressed.toList
        assert(reason.getCause.getMessage.contains("plant missing while adding photo"))
        assertEquals(actualSuppressed, List(compensation))
      case other => fail(s"expected AddFailed, got $other")

  test("should delete content after metadata removal and return the removed photo"):
    val refs = Refs()

    val result = buildManager(refs).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.Removed(photo))
    assertEquals(refs.removedPhotoIds.get(), Vector(photo.id))
    assertEquals(refs.deletedPhotoContentIds.get(), Vector(photo.id))

  test("should surface PhotoMissing without touching the content store"):
    val refs = Refs()

    val result = buildManager(refs, removePhotoResult = RemovePhotoResult.PhotoMissing).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.PhotoMissing)
    assertEquals(refs.deletedPhotoContentIds.get(), Vector.empty)

  test("should surface RemoveFailed without touching the content store"):
    val cause = RuntimeException("store down")
    val refs  = Refs()

    val result = buildManager(refs, removePhotoResult = RemovePhotoResult.RemoveFailed(cause)).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.RemoveFailed(cause))
    assertEquals(refs.deletedPhotoContentIds.get(), Vector.empty)

  test("should restore metadata and surface the content delete reason when content deletion fails"):
    val contentDeleteCause = RuntimeException("content store down")
    val refs               = Refs()

    val result = buildManager(refs, deleteContentResult = PhotoWriteResult.WriteFailed(contentDeleteCause)).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.RemoveFailed(contentDeleteCause))
    assertEquals(refs.addedPhotos.get(), Vector(photo))

  test("should report both failures when content deletion fails and metadata restore also fails"):
    val contentDeleteCause = RuntimeException("content store down")
    val compensationCause  = RuntimeException("metadata restore failed")
    val refs               = Refs()

    buildManager(
      refs,
      deleteContentResult = PhotoWriteResult.WriteFailed(contentDeleteCause),
      addPhotoResult = AddPhotoResult.AddFailed(compensationCause)
    ).removePhoto(photo.id) match
      case RemovePhotoResult.RemoveFailed(reason) =>
        val actualSuppressed = reason.getSuppressed.toList
        assertEquals(reason.getCause, contentDeleteCause)
        assertEquals(actualSuppressed, List(compensationCause))
      case other => fail(s"expected RemoveFailed, got $other")

  test("should report both failures when content deletion fails and plant is missing during metadata restore"):
    val contentDeleteCause = RuntimeException("content store down")
    val refs               = Refs()

    buildManager(
      refs,
      deleteContentResult = PhotoWriteResult.WriteFailed(contentDeleteCause),
      addPhotoResult = AddPhotoResult.PlantMissing
    ).removePhoto(photo.id) match
      case RemovePhotoResult.RemoveFailed(reason) =>
        val actualSuppressedHead = reason.getSuppressed.head.getMessage
        assertEquals(reason.getCause, contentDeleteCause)
        assert(actualSuppressedHead.contains("plant missing while restoring photo metadata"))
      case other => fail(s"expected RemoveFailed, got $other")

  test("should delegate photo listing to the store and return the page"):
    val page = PhotoPage(Vector(photo), hasNextPage = false)
    val refs = Refs()

    val result = buildManager(refs, getPhotosResult = GetPhotosResult.Read(page)).getPhotos(plantId, firstPhotoPage)

    assertEquals(result, GetPhotosResult.Read(page))
    assertEquals(refs.requestedPhotoWindows.get(), Vector(plantId -> firstPhotoPage))

  test("should surface a photo listing failure from the store"):
    val cause = RuntimeException("store unavailable")

    val result = buildManager(getPhotosResult = GetPhotosResult.ReadFailed(cause)).getPhotos(plantId, firstPhotoPage)

    assertEquals(result, GetPhotosResult.ReadFailed(cause))

  test("should pass photo content reads through to the content store, thumbnail variant included"):
    val refs         = Refs()
    val originalRead = buildManager(refs).getPhotoContent(photo.id, PhotoVariant.Original)
    val missingRead  = buildManager(getContentResult = PhotoReadResult.ContentMissing).getPhotoContent(photo.id, PhotoVariant.Thumbnail)

    assertEquals(originalRead, PhotoReadResult.Read(photoContent))
    assertEquals(missingRead, PhotoReadResult.ContentMissing)
    assertEquals(refs.requestedContentVariants.get(), Vector(PhotoVariant.Original))

  final private case class Refs():
    val addedPhotos: AtomicReference[Vector[PlantPhoto]]                                 = AtomicReference(Vector.empty)
    val removedPhotoIds: AtomicReference[Vector[PhotoId]]                                = AtomicReference(Vector.empty)
    val requestedPhotoWindows: AtomicReference[Vector[(PlantId, PhotoWindow)]]           = AtomicReference(Vector.empty)
    val putPhotoContents: AtomicReference[Vector[(PhotoId, PhotoContent, PhotoContent)]] = AtomicReference(Vector.empty)
    val deletedPhotoContentIds: AtomicReference[Vector[PhotoId]]                         = AtomicReference(Vector.empty)
    val requestedContentVariants: AtomicReference[Vector[PhotoVariant]]                  = AtomicReference(Vector.empty)

  private def buildManager(
      refs: Refs = Refs(),
      addPhotoResult: AddPhotoResult = AddPhotoResult.Added(photo),
      removePhotoResult: RemovePhotoResult = RemovePhotoResult.Removed(photo),
      getPhotosResult: GetPhotosResult = GetPhotosResult.Read(PhotoPage(Vector.empty, hasNextPage = false)),
      putContentResult: PhotoWriteResult = PhotoWriteResult.Written,
      deleteContentResult: PhotoWriteResult = PhotoWriteResult.Written,
      getContentResult: PhotoReadResult = PhotoReadResult.Read(photoContent),
      thumbnailResult: ThumbnailDerivationResult = ThumbnailDerivationResult.Derived(expectedThumbnail),
      captureTime: Instant = date
  ) =
    val store = new PlantStore:
      override def addPlant(plant: Plant): AddPlantResult          = fail("photos must not add plants")
      override def getPlants(status: PlantStatus): GetPlantsResult = fail("photos must not read plants")
      override def getArchivedCount: ArchivedCountResult           = fail("photos must not count plants")
      override def getPlant(plant: PlantId): GetPlantResult        = fail("photos must not read a single plant")
      override def updatePlant(plant: Plant): UpdatePlantResult    = fail("photos must not update plants")
      override def addPhoto(photo: PlantPhoto): AddPhotoResult     =
        refs.addedPhotos.updateAndGet(_ :+ photo).pipe(_ => addPhotoResult)
      override def removePhoto(photo: PhotoId): RemovePhotoResult =
        refs.removedPhotoIds.updateAndGet(_ :+ photo).pipe(_ => removePhotoResult)
      override def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult =
        refs.requestedPhotoWindows.updateAndGet(_ :+ (plant -> window)).pipe(_ => getPhotosResult)
    val contentStore = new PhotoContentStore:
      override def put(photo: PhotoId, original: PhotoContent, thumbnail: PhotoContent): PhotoWriteResult =
        refs.putPhotoContents.updateAndGet(_ :+ (photo, original, thumbnail)).pipe(_ => putContentResult)
      override def get(photo: PhotoId, variant: PhotoVariant): PhotoReadResult =
        refs.requestedContentVariants.updateAndGet(_ :+ variant).pipe(_ => getContentResult)
      override def delete(photo: PhotoId): PhotoWriteResult =
        refs.deletedPhotoContentIds.updateAndGet(_ :+ photo).pipe(_ => deleteContentResult)
    val thumbnail = new PhotoThumbnail:
      override def derive(original: PhotoContent): ThumbnailDerivationResult = thumbnailResult
    PhotoManager.make(using store, contentStore, thumbnail, () => photoUuid.toString, () => captureTime)
