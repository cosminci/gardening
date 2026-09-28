package gardening.usecases

import gardening.domain.*
import gardening.domain.plants.*
import gardening.ports.{PhotoStore, PhotoContentStore}
import gardening.capabilities.{IdGenerator, Clock, Logger}

import language.experimental.captureChecking

import java.util.UUID
import scala.util.chaining.scalaUtilChainingOps

trait PhotoManager:
  def addPhoto(plant: PlantId, content: PhotoContent): AddPhotoResult
  def removePhoto(photo: PhotoId): RemovePhotoResult
  def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult
  def getPhotoContent(photo: PhotoId, variant: PhotoVariant): PhotoReadResult

object PhotoManager:

  def make(using
      store: PhotoStore^,
      contentStore: PhotoContentStore^,
      thumbnail: PhotoThumbnail^,
      idGen: IdGenerator^,
      clock: Clock^
  )(using log: Logger^): PhotoManager^{store, contentStore, thumbnail, idGen, clock, log} =
    new LivePhotos

  private class LivePhotos(using
      store: PhotoStore^,
      contentStore: PhotoContentStore^,
      thumbnail: PhotoThumbnail^,
      idGen: IdGenerator^,
      clock: Clock^
  )(using log: Logger^) extends PhotoManager:

    override def addPhoto(plant: PlantId, content: PhotoContent): AddPhotoResult =
      thumbnail.derive(content) match
        case ThumbnailDerivationResult.DerivationFailed(reason) =>
          AddPhotoResult.AddFailed(reason).tap(_ => log.error("add photo", reason))
        case ThumbnailDerivationResult.Derived(derivedThumbnail) =>
          val photo = PlantPhoto(PhotoId(UUID.fromString(idGen.nextId())), plant, clock.now())
          contentStore.put(photo.id, content, derivedThumbnail) match
            case PhotoWriteResult.WriteFailed(reason) =>
              AddPhotoResult.AddFailed(reason).tap(_ => log.error("add photo", reason))
            case PhotoWriteResult.Written =>
              store.addPhoto(photo) match
                case result @ AddPhotoResult.Added(added) => log.info(s"photo added $added mediaType=${content.mediaType}").pipe(_ => result)
                case AddPhotoResult.PlantMissing          => compensateContentDeleteAfterMissingPlant(photo.id)
                case AddPhotoResult.AddFailed(reason)     => compensateContentDeleteAfterAddFailed(photo.id, reason)

    override def removePhoto(photo: PhotoId): RemovePhotoResult =
      store.removePhoto(photo) match
        case RemovePhotoResult.PhotoMissing         => RemovePhotoResult.PhotoMissing
        case RemovePhotoResult.RemoveFailed(reason) =>
          RemovePhotoResult.RemoveFailed(reason).tap(_ => log.error("remove photo", reason))
        case RemovePhotoResult.Removed(removed) =>
          contentStore.delete(removed.id) match
            case PhotoWriteResult.Written =>
              log.info(s"photo removed $removed")
              RemovePhotoResult.Removed(removed)
            case PhotoWriteResult.WriteFailed(reason) => compensateMetadataRestore(removed, reason)

    override def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult =
      store.getPhotos(plant, window).tap:
        case GetPhotosResult.ReadFailed(reason) => log.error("get photos", reason)
        case _                                  => ()

    override def getPhotoContent(photo: PhotoId, variant: PhotoVariant): PhotoReadResult = contentStore.get(photo, variant)

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
