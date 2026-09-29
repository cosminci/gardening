package gardening.usecases

import gardening.domain.*
import gardening.domain.plants.*
import gardening.domain.plants.PhotoMediaType.*
import gardening.ports.{PhotoStore, PhotoContentStore, PhotoWriteJournal}
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
  private val idempotencyKey    = "idem-1"

  test("should store photo content then metadata and return the added photo with its server-assigned timestamp"):
    val captureInstant = date.plusSeconds(5)
    val expectedPhoto  = PlantPhoto(PhotoId(photoUuid), plantId, captureInstant)
    val refs           = Refs()

    val result = buildManager(
      refs,
      addPhotoResult = AddPhotoResult.Added(expectedPhoto),
      captureTime = captureInstant
    ).addPhoto(plantId, photoContent, idempotencyKey)

    assertEquals(result, AddPhotoResult.Added(expectedPhoto))
    assertEquals(refs.putPhotoContents.get(), Vector((PhotoId(photoUuid), photoContent, expectedThumbnail)))
    assertEquals(refs.addedPhotos.get(), Vector(expectedPhoto))
    assertEquals(refs.recordedAdds.get(), Vector((idempotencyKey, plantId, captureInstant)))
    assertEquals(refs.attachedPhotos.get(), Vector((idempotencyKey, PhotoId(photoUuid))))
    assertEquals(refs.markedDoneKeys.get(), Vector(idempotencyKey))
    assertEquals(refs.discardedKeys.get(), Vector.empty)

  test("should fail the upload without writing anything when a thumbnail cannot be derived"):
    val reason = RuntimeException("no image reader available for this content")
    val refs   = Refs()

    val result =
      buildManager(refs, thumbnailResult = ThumbnailDerivationResult.DerivationFailed(reason)).addPhoto(plantId, photoContent, idempotencyKey)

    assertEquals(result, AddPhotoResult.AddFailed(reason))
    assertEquals(refs.putPhotoContents.get(), Vector.empty)
    assertEquals(refs.addedPhotos.get(), Vector.empty)
    assertEquals(refs.recordedAdds.get(), Vector.empty)

  test("should surface a content write failure and discard the in-flight record without touching metadata"):
    val cause = RuntimeException("disk full")
    val refs  = Refs()

    val result = buildManager(refs, putContentResult = PhotoWriteResult.WriteFailed(cause)).addPhoto(plantId, photoContent, idempotencyKey)

    assertEquals(result, AddPhotoResult.AddFailed(cause))
    assertEquals(refs.addedPhotos.get(), Vector.empty)
    assertEquals(refs.discardedKeys.get(), Vector(idempotencyKey))

  test("should compensate by deleting content and discarding the in-flight record when metadata write fails"):
    val cause = RuntimeException("metadata store down")
    val refs  = Refs()

    val result = buildManager(refs, addPhotoResult = AddPhotoResult.AddFailed(cause)).addPhoto(plantId, photoContent, idempotencyKey)

    assertEquals(result, AddPhotoResult.AddFailed(cause))
    assertEquals(refs.deletedPhotoContentIds.get(), Vector(PhotoId(photoUuid)))
    assertEquals(refs.discardedKeys.get(), Vector(idempotencyKey))

  test("should compensate by deleting content and discarding the in-flight record when plant is missing during metadata write"):
    val refs = Refs()

    val result = buildManager(refs, addPhotoResult = AddPhotoResult.PlantMissing).addPhoto(plantId, photoContent, idempotencyKey)

    assertEquals(result, AddPhotoResult.PlantMissing)
    assertEquals(refs.deletedPhotoContentIds.get(), Vector(PhotoId(photoUuid)))
    assertEquals(refs.discardedKeys.get(), Vector(idempotencyKey))

  test("should report both failures when metadata write fails and compensation content delete also fails"):
    val primary      = RuntimeException("metadata write failed")
    val compensation = RuntimeException("content delete failed too")
    val refs         = Refs()

    buildManager(
      refs,
      addPhotoResult = AddPhotoResult.AddFailed(primary),
      deleteContentResult = PhotoWriteResult.WriteFailed(compensation)
    ).addPhoto(plantId, photoContent, idempotencyKey) match
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
    ).addPhoto(plantId, photoContent, idempotencyKey) match
      case AddPhotoResult.AddFailed(reason) =>
        val actualSuppressed = reason.getSuppressed.toList
        assert(reason.getCause.getMessage.contains("plant missing while adding photo"))
        assertEquals(actualSuppressed, List(compensation))
      case other => fail(s"expected AddFailed, got $other")

  test("should return the existing photo without repeating any writes when retried with a completed idempotency key"):
    val doneIntent = PhotoWriteIntent(idempotencyKey, PhotoWriteOperation.Add, PhotoWriteIntentStatus.Done, Some(photo.id), Some(plantId), Some(date))
    val refs       = Refs()

    val result = buildManager(refs, findByKeyResult = PhotoJournalFindResult.Found(doneIntent)).addPhoto(plantId, photoContent, idempotencyKey)

    assertEquals(result, AddPhotoResult.Added(photo))
    assertEquals(refs.putPhotoContents.get(), Vector.empty)
    assertEquals(refs.addedPhotos.get(), Vector.empty)
    assertEquals(refs.recordedAdds.get(), Vector.empty)

  test("should fail the upload as a corrupt journal entry when a done record is missing its metadata"):
    val corruptIntent = PhotoWriteIntent(idempotencyKey, PhotoWriteOperation.Add, PhotoWriteIntentStatus.Done, None, Some(plantId), Some(date))
    val refs          = Refs()

    val result = buildManager(refs, findByKeyResult = PhotoJournalFindResult.Found(corruptIntent)).addPhoto(plantId, photoContent, idempotencyKey)

    assertEquals(result.getClass, classOf[AddPhotoResult.AddFailed])
    assertEquals(refs.putPhotoContents.get(), Vector.empty)

  test("should fail the upload when a duplicate idempotency key is still in flight"):
    val pendingIntent = PhotoWriteIntent(idempotencyKey, PhotoWriteOperation.Add, PhotoWriteIntentStatus.Pending, None, Some(plantId), Some(date))
    val refs          = Refs()

    val result = buildManager(refs, findByKeyResult = PhotoJournalFindResult.Found(pendingIntent)).addPhoto(plantId, photoContent, idempotencyKey)

    assertEquals(result.getClass, classOf[AddPhotoResult.AddFailed])
    assertEquals(refs.putPhotoContents.get(), Vector.empty)

  test("should fail the upload when the journal cannot be read"):
    val cause = RuntimeException("journal unavailable")
    val refs  = Refs()

    val result = buildManager(refs, findByKeyResult = PhotoJournalFindResult.FindFailed(cause)).addPhoto(plantId, photoContent, idempotencyKey)

    assertEquals(result, AddPhotoResult.AddFailed(cause))
    assertEquals(refs.putPhotoContents.get(), Vector.empty)

  test("should fail the upload without deriving a thumbnail when recording the in-flight write fails"):
    val cause = RuntimeException("journal write failed")
    val refs  = Refs()

    val result = buildManager(refs, recordAddResult = PhotoJournalWriteResult.RecordFailed(cause)).addPhoto(plantId, photoContent, idempotencyKey)

    assertEquals(result, AddPhotoResult.AddFailed(cause))
    assertEquals(refs.putPhotoContents.get(), Vector.empty)

  test("should fail the upload without writing content when attaching the photo id fails"):
    val cause = RuntimeException("journal write failed")
    val refs  = Refs()

    val result = buildManager(refs, attachPhotoResult = PhotoJournalWriteResult.RecordFailed(cause)).addPhoto(plantId, photoContent, idempotencyKey)

    assertEquals(result, AddPhotoResult.AddFailed(cause))
    assertEquals(refs.putPhotoContents.get(), Vector.empty)

  test("should still return the added photo when marking the in-flight write done fails"):
    val refs = Refs()

    val result =
      buildManager(refs, markDoneResult = PhotoJournalWriteResult.RecordFailed(RuntimeException("journal down"))).addPhoto(
        plantId,
        photoContent,
        idempotencyKey
      )

    assertEquals(result, AddPhotoResult.Added(photo))

  test("should record an in-flight remove before deleting metadata, and discard it after a successful removal"):
    val refs = Refs()

    val result = buildManager(refs).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.Removed(photo))
    assertEquals(refs.removedPhotoIds.get(), Vector(photo.id))
    assertEquals(refs.deletedPhotoContentIds.get(), Vector(photo.id))
    assertEquals(refs.recordedRemoves.get(), Vector(photo.id))
    assertEquals(refs.discardedKeys.get(), Vector(photo.id.value.toString))

  test("should surface PhotoMissing, discard the in-flight record, and not touch the content store"):
    val refs = Refs()

    val result = buildManager(refs, removePhotoResult = RemovePhotoResult.PhotoMissing).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.PhotoMissing)
    assertEquals(refs.deletedPhotoContentIds.get(), Vector.empty)
    assertEquals(refs.discardedKeys.get(), Vector(photo.id.value.toString))

  test("should surface RemoveFailed, discard the in-flight record, and not touch the content store"):
    val cause = RuntimeException("store down")
    val refs  = Refs()

    val result = buildManager(refs, removePhotoResult = RemovePhotoResult.RemoveFailed(cause)).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.RemoveFailed(cause))
    assertEquals(refs.deletedPhotoContentIds.get(), Vector.empty)
    assertEquals(refs.discardedKeys.get(), Vector(photo.id.value.toString))

  test("should fail the removal without touching the store when recording the in-flight write fails"):
    val cause = RuntimeException("journal write failed")
    val refs  = Refs()

    val result = buildManager(refs, recordRemoveResult = PhotoJournalWriteResult.RecordFailed(cause)).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.RemoveFailed(cause))
    assertEquals(refs.removedPhotoIds.get(), Vector.empty)

  test("should restore metadata and surface the content delete reason when content deletion fails"):
    val contentDeleteCause = RuntimeException("content store down")
    val refs               = Refs()

    val result = buildManager(refs, deleteContentResult = PhotoWriteResult.WriteFailed(contentDeleteCause)).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.RemoveFailed(contentDeleteCause))
    assertEquals(refs.addedPhotos.get(), Vector(photo))
    assertEquals(refs.discardedKeys.get(), Vector(photo.id.value.toString))

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
        assert(actualSuppressedHead.contains("plant missing while restoring photo metadata"), actualSuppressedHead)
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
    val recordedAdds: AtomicReference[Vector[(String, PlantId, Instant)]]                = AtomicReference(Vector.empty)
    val recordedRemoves: AtomicReference[Vector[PhotoId]]                                = AtomicReference(Vector.empty)
    val attachedPhotos: AtomicReference[Vector[(String, PhotoId)]]                       = AtomicReference(Vector.empty)
    val markedDoneKeys: AtomicReference[Vector[String]]                                  = AtomicReference(Vector.empty)
    val discardedKeys: AtomicReference[Vector[String]]                                   = AtomicReference(Vector.empty)

  private def buildManager(
      refs: Refs = Refs(),
      addPhotoResult: AddPhotoResult = AddPhotoResult.Added(photo),
      removePhotoResult: RemovePhotoResult = RemovePhotoResult.Removed(photo),
      getPhotosResult: GetPhotosResult = GetPhotosResult.Read(PhotoPage(Vector.empty, hasNextPage = false)),
      putContentResult: PhotoWriteResult = PhotoWriteResult.Written,
      deleteContentResult: PhotoWriteResult = PhotoWriteResult.Written,
      getContentResult: PhotoReadResult = PhotoReadResult.Read(photoContent),
      thumbnailResult: ThumbnailDerivationResult = ThumbnailDerivationResult.Derived(expectedThumbnail),
      captureTime: Instant = date,
      findByKeyResult: PhotoJournalFindResult = PhotoJournalFindResult.NotFound,
      recordAddResult: PhotoJournalWriteResult = PhotoJournalWriteResult.Recorded,
      recordRemoveResult: PhotoJournalWriteResult = PhotoJournalWriteResult.Recorded,
      attachPhotoResult: PhotoJournalWriteResult = PhotoJournalWriteResult.Recorded,
      markDoneResult: PhotoJournalWriteResult = PhotoJournalWriteResult.Recorded,
      discardResult: PhotoJournalWriteResult = PhotoJournalWriteResult.Recorded
  ) =
    val store = new PhotoStore:
      override def addPhoto(photo: PlantPhoto): AddPhotoResult =
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
    val journal = new PhotoWriteJournal:
      override def recordAdd(idempotencyKey: String, plant: PlantId, capturedAt: Instant): PhotoJournalWriteResult =
        refs.recordedAdds.updateAndGet(_ :+ (idempotencyKey, plant, capturedAt)).pipe(_ => recordAddResult)
      override def recordRemove(photo: PhotoId): PhotoJournalWriteResult =
        refs.recordedRemoves.updateAndGet(_ :+ photo).pipe(_ => recordRemoveResult)
      override def attachPhoto(idempotencyKey: String, photo: PhotoId): PhotoJournalWriteResult =
        refs.attachedPhotos.updateAndGet(_ :+ (idempotencyKey, photo)).pipe(_ => attachPhotoResult)
      override def markDone(key: String): PhotoJournalWriteResult =
        refs.markedDoneKeys.updateAndGet(_ :+ key).pipe(_ => markDoneResult)
      override def discard(key: String): PhotoJournalWriteResult =
        refs.discardedKeys.updateAndGet(_ :+ key).pipe(_ => discardResult)
      override def findByKey(key: String): PhotoJournalFindResult = findByKeyResult
      override def list(): PhotoJournalListResult                 = PhotoJournalListResult.Listed(Vector.empty)
    val thumbnail = new PhotoThumbnail:
      override def derive(original: PhotoContent): ThumbnailDerivationResult = thumbnailResult
    PhotoManager.make(using store, contentStore, journal, thumbnail, () => photoUuid.toString, () => captureTime)
