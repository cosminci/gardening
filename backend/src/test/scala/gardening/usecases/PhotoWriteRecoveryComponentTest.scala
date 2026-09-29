package gardening.usecases

import gardening.domain.*
import gardening.domain.plants.*
import gardening.domain.plants.PhotoMediaType.*
import gardening.ports.{PhotoStore, PhotoContentStore, PhotoWriteJournal}
import gardening.capabilities.TestImplicits
import scodec.bits.ByteVector

import language.experimental.captureChecking

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class PhotoWriteRecoveryComponentTest extends munit.FunSuite with TestImplicits:

  private val date              = Instant.parse("2026-01-01T00:00:00Z")
  private val plantId           = PlantId("p1")
  private val photoId           = PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000001"))
  private val photo             = PlantPhoto(photoId, plantId, date)
  private val photoContent      = PhotoContent(ByteVector(Array[Byte](1, 2, 3)), Jpeg)
  private val expectedThumbnail = PhotoContent(ByteVector(Array[Byte](9, 9, 9)), Jpeg)
  private val key               = "idem-1"

  private val doneAdd       = PhotoWriteIntent(key, PhotoWriteOperation.Add, PhotoWriteIntentStatus.Done, Some(photoId), Some(plantId), Some(date))
  private val unattached    = PhotoWriteIntent(key, PhotoWriteOperation.Add, PhotoWriteIntentStatus.Pending, None, Some(plantId), Some(date))
  private val pendingAdd    = PhotoWriteIntent(key, PhotoWriteOperation.Add, PhotoWriteIntentStatus.Pending, Some(photoId), Some(plantId), Some(date))
  private val corruptAdd    = PhotoWriteIntent(key, PhotoWriteOperation.Add, PhotoWriteIntentStatus.Pending, Some(photoId), None, None)
  private val pendingRemove =
    PhotoWriteIntent(photoId.value.toString, PhotoWriteOperation.Remove, PhotoWriteIntentStatus.Pending, Some(photoId), None, None)
  private val removeWithoutPhotoId =
    PhotoWriteIntent("corrupt-remove", PhotoWriteOperation.Remove, PhotoWriteIntentStatus.Pending, None, None, None)

  test("should purge an already-done add intent without touching any store"):
    val refs = Refs()

    buildRecovery(refs, listResult = PhotoJournalListResult.Listed(Vector(doneAdd))).reconcile()

    assertEquals(refs.addedPhotos.get(), Vector.empty)
    assertEquals(refs.deletedContentIds.get(), Vector.empty)
    assertEquals(refs.discardedKeys.get(), Vector(key))
    assertEquals(refs.markedDoneKeys.get(), Vector.empty)

  test("should log and leave the intent pending when marking a finished add done fails"):
    val refs = Refs()

    buildRecovery(
      refs,
      listResult = PhotoJournalListResult.Listed(Vector(pendingAdd)),
      markDoneResult = PhotoJournalWriteResult.RecordFailed(RuntimeException("journal down"))
    ).reconcile()

    assertEquals(refs.addedPhotos.get(), Vector(photo))
    assertEquals(refs.discardedKeys.get(), Vector.empty)

  test("should log and leave a discard failure without raising"):
    val refs = Refs()

    buildRecovery(
      refs,
      listResult = PhotoJournalListResult.Listed(Vector(doneAdd)),
      discardResult = PhotoJournalWriteResult.RecordFailed(RuntimeException("journal down"))
    ).reconcile()

    assertEquals(refs.discardedKeys.get(), Vector(key))

  test("should discard a pending add that crashed before its photo id was attached"):
    val refs = Refs()

    buildRecovery(refs, listResult = PhotoJournalListResult.Listed(Vector(unattached))).reconcile()

    assertEquals(refs.addedPhotos.get(), Vector.empty)
    assertEquals(refs.discardedKeys.get(), Vector(key))

  test("should finish a pending add whose content fully landed, then mark it done"):
    val refs = Refs()

    buildRecovery(refs, listResult = PhotoJournalListResult.Listed(Vector(pendingAdd))).reconcile()

    assertEquals(refs.addedPhotos.get(), Vector(photo))
    assertEquals(refs.markedDoneKeys.get(), Vector(key))
    assertEquals(refs.discardedKeys.get(), Vector.empty)
    assertEquals(refs.deletedContentIds.get(), Vector.empty)

  test("should discard a pending add whose original never landed, after cleaning up any partial content"):
    val refs = Refs()

    buildRecovery(
      refs,
      listResult = PhotoJournalListResult.Listed(Vector(pendingAdd)),
      getOriginalResult = PhotoReadResult.ContentMissing
    ).reconcile()

    assertEquals(refs.addedPhotos.get(), Vector.empty)
    assertEquals(refs.deletedContentIds.get(), Vector(photoId))
    assertEquals(refs.discardedKeys.get(), Vector(key))

  test("should leave a pending add's intent when cleaning up its partial content fails"):
    val refs = Refs()

    buildRecovery(
      refs,
      listResult = PhotoJournalListResult.Listed(Vector(pendingAdd)),
      getOriginalResult = PhotoReadResult.ContentMissing,
      deleteContentResult = PhotoWriteResult.WriteFailed(RuntimeException("disk full"))
    ).reconcile()

    assertEquals(refs.deletedContentIds.get(), Vector(photoId))
    assertEquals(refs.discardedKeys.get(), Vector.empty)

  test("should discard a pending add whose thumbnail never landed, after cleaning up any partial content"):
    val refs = Refs()

    buildRecovery(
      refs,
      listResult = PhotoJournalListResult.Listed(Vector(pendingAdd)),
      getThumbnailResult = PhotoReadResult.ContentMissing
    ).reconcile()

    assertEquals(refs.addedPhotos.get(), Vector.empty)
    assertEquals(refs.deletedContentIds.get(), Vector(photoId))
    assertEquals(refs.discardedKeys.get(), Vector(key))

  test("should clean up and discard when finishing a landed add finds the plant now missing"):
    val refs = Refs()

    buildRecovery(refs, listResult = PhotoJournalListResult.Listed(Vector(pendingAdd)), addPhotoResult = AddPhotoResult.PlantMissing).reconcile()

    assertEquals(refs.deletedContentIds.get(), Vector(photoId))
    assertEquals(refs.discardedKeys.get(), Vector(key))
    assertEquals(refs.markedDoneKeys.get(), Vector.empty)

  test("should clean up and discard when finishing a landed add fails"):
    val refs = Refs()

    buildRecovery(
      refs,
      listResult = PhotoJournalListResult.Listed(Vector(pendingAdd)),
      addPhotoResult = AddPhotoResult.AddFailed(RuntimeException("store down"))
    ).reconcile()

    assertEquals(refs.deletedContentIds.get(), Vector(photoId))
    assertEquals(refs.discardedKeys.get(), Vector(key))

  test("should clean up and discard a pending add missing its plant or capturedAt without attempting to finish it"):
    val refs = Refs()

    buildRecovery(refs, listResult = PhotoJournalListResult.Listed(Vector(corruptAdd))).reconcile()

    assertEquals(refs.addedPhotos.get(), Vector.empty)
    assertEquals(refs.deletedContentIds.get(), Vector(photoId))
    assertEquals(refs.discardedKeys.get(), Vector(key))

  test("should finish an interrupted removal whose metadata delete never ran, then discard its intent"):
    val refs = Refs()

    buildRecovery(
      refs,
      listResult = PhotoJournalListResult.Listed(Vector(pendingRemove)),
      removePhotoResult = RemovePhotoResult.Removed(photo)
    ).reconcile()

    assertEquals(refs.removedPhotoIds.get(), Vector(photoId))
    assertEquals(refs.deletedContentIds.get(), Vector(photoId))
    assertEquals(refs.discardedKeys.get(), Vector(photoId.value.toString))

  test("should finish an interrupted removal whose metadata delete already landed, then discard its intent"):
    val refs = Refs()

    buildRecovery(
      refs,
      listResult = PhotoJournalListResult.Listed(Vector(pendingRemove)),
      removePhotoResult = RemovePhotoResult.PhotoMissing
    ).reconcile()

    assertEquals(refs.deletedContentIds.get(), Vector(photoId))
    assertEquals(refs.discardedKeys.get(), Vector(photoId.value.toString))

  test("should leave the intent for the next startup when the metadata delete retry fails"):
    val refs = Refs()

    buildRecovery(
      refs,
      listResult = PhotoJournalListResult.Listed(Vector(pendingRemove)),
      removePhotoResult = RemovePhotoResult.RemoveFailed(RuntimeException("store down"))
    ).reconcile()

    assertEquals(refs.deletedContentIds.get(), Vector.empty)
    assertEquals(refs.discardedKeys.get(), Vector.empty)

  test("should leave the intent for the next startup when the removal's content cleanup fails"):
    val refs = Refs()

    buildRecovery(
      refs,
      listResult = PhotoJournalListResult.Listed(Vector(pendingRemove)),
      removePhotoResult = RemovePhotoResult.Removed(photo),
      deleteContentResult = PhotoWriteResult.WriteFailed(RuntimeException("disk full"))
    ).reconcile()

    assertEquals(refs.discardedKeys.get(), Vector.empty)

  test("should discard a corrupt remove intent that never recorded a photo id"):
    val refs = Refs()

    buildRecovery(refs, listResult = PhotoJournalListResult.Listed(Vector(removeWithoutPhotoId))).reconcile()

    assertEquals(refs.removedPhotoIds.get(), Vector.empty)
    assertEquals(refs.discardedKeys.get(), Vector("corrupt-remove"))

  test("should reconcile every listed intent in one pass"):
    val refs = Refs()

    buildRecovery(refs, listResult = PhotoJournalListResult.Listed(Vector(doneAdd, pendingRemove))).reconcile()

    assertEquals(refs.discardedKeys.get().toSet, Set(key, photoId.value.toString))

  test("should do nothing when the journal cannot be listed"):
    val refs = Refs()

    buildRecovery(refs, listResult = PhotoJournalListResult.ListFailed(RuntimeException("journal unavailable"))).reconcile()

    assertEquals(refs.addedPhotos.get(), Vector.empty)
    assertEquals(refs.removedPhotoIds.get(), Vector.empty)
    assertEquals(refs.discardedKeys.get(), Vector.empty)

  final private case class Refs():
    val addedPhotos: AtomicReference[Vector[PlantPhoto]]    = AtomicReference(Vector.empty)
    val removedPhotoIds: AtomicReference[Vector[PhotoId]]   = AtomicReference(Vector.empty)
    val deletedContentIds: AtomicReference[Vector[PhotoId]] = AtomicReference(Vector.empty)
    val markedDoneKeys: AtomicReference[Vector[String]]     = AtomicReference(Vector.empty)
    val discardedKeys: AtomicReference[Vector[String]]      = AtomicReference(Vector.empty)

  private def buildRecovery(
      refs: Refs,
      listResult: PhotoJournalListResult,
      addPhotoResult: AddPhotoResult = AddPhotoResult.Added(photo),
      removePhotoResult: RemovePhotoResult = RemovePhotoResult.Removed(photo),
      getOriginalResult: PhotoReadResult = PhotoReadResult.Read(photoContent),
      getThumbnailResult: PhotoReadResult = PhotoReadResult.Read(expectedThumbnail),
      deleteContentResult: PhotoWriteResult = PhotoWriteResult.Written,
      markDoneResult: PhotoJournalWriteResult = PhotoJournalWriteResult.Recorded,
      discardResult: PhotoJournalWriteResult = PhotoJournalWriteResult.Recorded
  ) =
    val store = new PhotoStore:
      override def addPhoto(photo: PlantPhoto): AddPhotoResult    = refs.addedPhotos.updateAndGet(_ :+ photo).pipe(_ => addPhotoResult)
      override def removePhoto(photo: PhotoId): RemovePhotoResult =
        refs.removedPhotoIds.updateAndGet(_ :+ photo).pipe(_ => removePhotoResult)
      override def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult =
        GetPhotosResult.Read(PhotoPage(Vector.empty, hasNextPage = false))
    val contentStore = new PhotoContentStore:
      override def put(photo: PhotoId, original: PhotoContent, thumbnail: PhotoContent): PhotoWriteResult = PhotoWriteResult.Written
      override def get(photo: PhotoId, variant: PhotoVariant): PhotoReadResult                            = variant match
        case PhotoVariant.Original  => getOriginalResult
        case PhotoVariant.Thumbnail => getThumbnailResult
      override def delete(photo: PhotoId): PhotoWriteResult =
        refs.deletedContentIds.updateAndGet(_ :+ photo).pipe(_ => deleteContentResult)
    val journal = new PhotoWriteJournal:
      override def recordAdd(idempotencyKey: String, plant: PlantId, capturedAt: Instant): PhotoJournalWriteResult = PhotoJournalWriteResult.Recorded
      override def recordRemove(photo: PhotoId): PhotoJournalWriteResult                                           = PhotoJournalWriteResult.Recorded
      override def attachPhoto(idempotencyKey: String, photo: PhotoId): PhotoJournalWriteResult                    = PhotoJournalWriteResult.Recorded
      override def markDone(key: String): PhotoJournalWriteResult = refs.markedDoneKeys.updateAndGet(_ :+ key).pipe(_ => markDoneResult)
      override def discard(key: String): PhotoJournalWriteResult  = refs.discardedKeys.updateAndGet(_ :+ key).pipe(_ => discardResult)
      override def findByKey(key: String): PhotoJournalFindResult = PhotoJournalFindResult.NotFound
      override def list(): PhotoJournalListResult                 = listResult
    PhotoWriteRecovery.make(using store, contentStore, journal)
