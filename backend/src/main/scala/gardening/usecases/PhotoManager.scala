package gardening.usecases

import cats.syntax.either.*
import gardening.domain.*
import gardening.domain.plants.*
import gardening.ports.{PhotoStore, PhotoContentStore, PhotoWriteJournal}
import gardening.capabilities.{IdGenerator, Clock, Logger}

import language.experimental.captureChecking

import java.util.UUID
import scala.util.chaining.scalaUtilChainingOps

trait PhotoManager:
  def addPhoto(plant: PlantId, content: PhotoContent, idempotencyKey: String): AddPhotoResult
  def removePhoto(photo: PhotoId): RemovePhotoResult
  def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult
  def getPhotoContent(photo: PhotoId, variant: PhotoVariant): PhotoReadResult

object PhotoManager:

  def make(using
      store: PhotoStore^,
      contentStore: PhotoContentStore^,
      journal: PhotoWriteJournal^,
      thumbnail: PhotoThumbnailGenerator^,
      idGen: IdGenerator^,
      clock: Clock^
  )(using log: Logger^): PhotoManager^{store, contentStore, journal, thumbnail, idGen, clock, log} =
    new LivePhotoManager

  private class LivePhotoManager(using
      store: PhotoStore^,
      contentStore: PhotoContentStore^,
      journal: PhotoWriteJournal^,
      thumbnail: PhotoThumbnailGenerator^,
      idGen: IdGenerator^,
      clock: Clock^
  )(using log: Logger^) extends PhotoManager:

    override def addPhoto(plant: PlantId, content: PhotoContent, idempotencyKey: String): AddPhotoResult =
      val outcome =
        for
          _       <- rejectExistingIntent(idempotencyKey)
          derived <- deriveThumbnail(content)
          photo   <- PlantPhoto(PhotoId(UUID.fromString(idGen.nextId())), plant, clock.now()).asRight
          _       <- recordIntent(idempotencyKey, photo)
          _       <- writeContent(photo, content, derived, idempotencyKey)
          added   <- persistMetadata(photo, content, idempotencyKey)
        yield added
      outcome.merge

    private def rejectExistingIntent(key: String): Either[AddPhotoResult, Unit] =
      journal.findByKey(key) match
        case PhotoJournalFindResult.FindFailed(reason) =>
          AddPhotoResult.AddFailed(reason).tap(_ => log.error("add photo", reason)).asLeft
        case PhotoJournalFindResult.Found(op: PhotoWriteIntent.Add) =>
          op.status match
            case PhotoWriteIntentStatus.Done =>
              val added = PlantPhoto(op.photoId, op.plantId, op.capturedAt)
              AddPhotoResult.Added(added).tap(_ => log.info(s"photo add retried idempotencyKey=$key")).asLeft
            case PhotoWriteIntentStatus.Pending =>
              val inProgress = RuntimeException(s"photo upload already in progress for idempotency key $key")
              AddPhotoResult.AddFailed(inProgress).tap(_ => log.error("add photo", inProgress)).asLeft
        case PhotoJournalFindResult.Found(_: PhotoWriteIntent.Remove) =>
          val wrapped = RuntimeException(s"idempotency key $key collides with a remove photo write intent")
          AddPhotoResult.AddFailed(wrapped).tap(_ => log.error("add photo", wrapped)).asLeft
        case PhotoJournalFindResult.NotFound => ().asRight

    private def deriveThumbnail(content: PhotoContent): Either[AddPhotoResult, PhotoContent] =
      thumbnail.derive(content) match
        case ThumbnailDerivationResult.DerivationFailed(reason) =>
          AddPhotoResult.AddFailed(reason).tap(_ => log.error("add photo", reason)).asLeft
        case ThumbnailDerivationResult.Derived(derivedThumbnail) => derivedThumbnail.asRight

    private def recordIntent(key: String, photo: PlantPhoto): Either[AddPhotoResult, Unit] =
      journal.recordAdd(key, photo.plantId, photo.capturedAt, photo.id) match
        case PhotoJournalWriteResult.RecordFailed(reason) =>
          AddPhotoResult.AddFailed(reason).tap(_ => log.error("add photo", reason)).asLeft
        case PhotoJournalWriteResult.Recorded => ().asRight

    private def writeContent(photo: PlantPhoto, content: PhotoContent, derived: PhotoContent, key: String): Either[AddPhotoResult, Unit] =
      contentStore.put(photo.id, content, derived) match
        case PhotoWriteResult.WriteFailed(reason) =>
          logJournalFailure("discard photo write intent")(journal.discard(key))
          AddPhotoResult.AddFailed(reason).tap(_ => log.error("add photo", reason)).asLeft
        case PhotoWriteResult.Written => ().asRight

    private def persistMetadata(photo: PlantPhoto, content: PhotoContent, key: String): Either[AddPhotoResult, AddPhotoResult] =
      store.addPhoto(photo) match
        case result @ AddPhotoResult.Added(added) =>
          logJournalFailure("mark photo write intent done")(journal.markDone(key))
          log.info(s"photo added $added mediaType=${content.mediaType}")
          result.asRight
        case AddPhotoResult.PlantMissing =>
          logJournalFailure("discard photo write intent")(journal.discard(key))
          compensateContentDeleteAfterMissingPlant(photo.id).asLeft
        case AddPhotoResult.AddFailed(reason) =>
          logJournalFailure("discard photo write intent")(journal.discard(key))
          compensateContentDeleteAfterAddFailed(photo.id, reason).asLeft

    override def removePhoto(photo: PhotoId): RemovePhotoResult =
      val outcome =
        for
          _       <- recordRemoveIntent(photo)
          removed <- removeMetadata(photo)
          result  <- deleteContent(photo, removed)
        yield result
      outcome.merge

    private def recordRemoveIntent(photo: PhotoId): Either[RemovePhotoResult, Unit] =
      journal.recordRemove(photo) match
        case PhotoJournalWriteResult.RecordFailed(reason) =>
          RemovePhotoResult.RemoveFailed(reason).tap(_ => log.error("remove photo", reason)).asLeft
        case PhotoJournalWriteResult.Recorded => ().asRight

    private def removeMetadata(photo: PhotoId): Either[RemovePhotoResult, PlantPhoto] =
      store.removePhoto(photo) match
        case RemovePhotoResult.PhotoMissing =>
          logJournalFailure("discard photo write intent")(journal.discard(photo.value.toString))
          RemovePhotoResult.PhotoMissing.asLeft
        case RemovePhotoResult.RemoveFailed(reason) =>
          logJournalFailure("discard photo write intent")(journal.discard(photo.value.toString))
          RemovePhotoResult.RemoveFailed(reason).tap(_ => log.error("remove photo", reason)).asLeft
        case RemovePhotoResult.Removed(removed) => removed.asRight

    private def deleteContent(photo: PhotoId, removed: PlantPhoto): Either[RemovePhotoResult, RemovePhotoResult] =
      contentStore.delete(removed.id) match
        case PhotoWriteResult.Written =>
          logJournalFailure("discard photo write intent")(journal.discard(photo.value.toString))
          log.info(s"photo removed $removed")
          RemovePhotoResult.Removed(removed).asRight
        case PhotoWriteResult.WriteFailed(reason) =>
          logJournalFailure("discard photo write intent")(journal.discard(photo.value.toString))
          compensateMetadataRestore(removed, reason).asLeft

    override def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult =
      store.getPhotos(plant, window).tap:
        case GetPhotosResult.ReadFailed(reason) => log.error("get photos", reason)
        case _                                  => ()

    override def getPhotoContent(photo: PhotoId, variant: PhotoVariant): PhotoReadResult =
      contentStore.get(photo, variant)

    private def logJournalFailure(operation: String)(result: PhotoJournalWriteResult): Unit = result match
      case PhotoJournalWriteResult.RecordFailed(reason) => log.error(operation, reason)
      case PhotoJournalWriteResult.Recorded             => ()

    private def compensateContentDeleteAfterMissingPlant(photoId: PhotoId) =
      contentStore.delete(photoId) match
        case PhotoWriteResult.Written                         => AddPhotoResult.PlantMissing
        case PhotoWriteResult.WriteFailed(compensationReason) =>
          val cause   = RuntimeException("plant missing while adding photo")
          val wrapped = RuntimeException("photo content persisted but metadata write failed and compensation failed", cause)
          wrapped.addSuppressed(compensationReason)
          AddPhotoResult.AddFailed(wrapped).tap(_ => log.error("add photo", wrapped))

    private def compensateContentDeleteAfterAddFailed(photoId: PhotoId, reason: Throwable) =
      contentStore.delete(photoId) match
        case PhotoWriteResult.Written =>
          AddPhotoResult.AddFailed(reason).tap(_ => log.error("add photo", reason))
        case PhotoWriteResult.WriteFailed(compensationReason) =>
          val wrapped = RuntimeException("photo content persisted but metadata write failed and compensation failed", reason)
          wrapped.addSuppressed(compensationReason)
          AddPhotoResult.AddFailed(wrapped).tap(_ => log.error("add photo", wrapped))

    private def compensateMetadataRestore(photo: PlantPhoto, contentDeleteReason: Throwable) =
      store.addPhoto(photo) match
        case AddPhotoResult.Added(_) =>
          RemovePhotoResult.RemoveFailed(contentDeleteReason).tap(_ => log.error("remove photo", contentDeleteReason))
        case AddPhotoResult.PlantMissing =>
          val compensationCause = RuntimeException("plant missing while restoring photo metadata")
          val wrapped           = RuntimeException("photo metadata removed but content deletion failed and compensation failed", contentDeleteReason)
          wrapped.addSuppressed(compensationCause)
          RemovePhotoResult.RemoveFailed(wrapped).tap(_ => log.error("remove photo", wrapped))
        case AddPhotoResult.AddFailed(compensationCause) =>
          val wrapped = RuntimeException("photo metadata removed but content deletion failed and compensation failed", contentDeleteReason)
          wrapped.addSuppressed(compensationCause)
          RemovePhotoResult.RemoveFailed(wrapped).tap(_ => log.error("remove photo", wrapped))
