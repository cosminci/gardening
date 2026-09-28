package gardening.usecases

import cats.syntax.either.*
import cats.syntax.eq.*
import gardening.domain.*
import gardening.domain.plants.*
import gardening.domain.substrate.GetSubstrateComponentsResult
import gardening.ports.{PlantStore, PhotoContentStore, PlantManagerMetricsApi, SubstrateStore}
import gardening.capabilities.{IdGenerator, Clock, PlantUpdateLock, Logger}
import monocle.syntax.all.*

import language.experimental.captureChecking

import java.util.UUID
import scala.util.chaining.scalaUtilChainingOps

trait PlantManager:
  def createPlant(species: Species, maybeNickname: Option[Nickname], location: Location, substrate: Substrate): CreatePlantResult
  def getPlants(status: PlantStatus): GetPlantsResult
  def getArchivedCount: ArchivedCountResult
  def editPlant(plant: PlantId, revise: PlantDetails => PlantDetails): EditPlantResult
  def addPhoto(plant: PlantId, content: PhotoContent): AddPhotoResult
  def removePhoto(photo: PhotoId): RemovePhotoResult
  def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult
  def getPhotoContent(photo: PhotoId, variant: PhotoVariant): PhotoReadResult

object PlantManager:

  def make(using
      store: PlantStore^,
      contentStore: PhotoContentStore^,
      thumbnail: PhotoThumbnail^,
      substrateStore: SubstrateStore^,
      idGen: IdGenerator^,
      clock: Clock^,
      lock: PlantUpdateLock^
  )(using
      log: Logger^,
      metrics: PlantManagerMetricsApi^
  ): PlantManager^{store, contentStore, thumbnail, substrateStore, idGen, clock, lock, log, metrics} =
    new LivePlants

  private class LivePlants(using
      store: PlantStore^,
      contentStore: PhotoContentStore^,
      thumbnail: PhotoThumbnail^,
      substrateStore: SubstrateStore^,
      idGen: IdGenerator^,
      clock: Clock^,
      lock: PlantUpdateLock^
  )(using log: Logger^, metrics: PlantManagerMetricsApi^) extends PlantManager:

    override def createPlant(species: Species, maybeNickname: Option[Nickname], location: Location, substrate: Substrate): CreatePlantResult =
      substrateStore.getSubstrateComponents match
        case GetSubstrateComponentsResult.ReadFailed(reason) =>
          CreatePlantResult.CatalogReadFailed(reason).tap(_ => log.error("create plant", reason))
        case GetSubstrateComponentsResult.Read(components) =>
          val known = components.filter(_.status === SubstrateComponentStatus.Active).map(_.id).toSet
          if !substrate.parts.forall(part => known.contains(part.componentId)) then CreatePlantResult.UnknownComponent
          else
            val plant = Plant(PlantId(idGen.nextId()), PlantDetails(species, maybeNickname, location, substrate, PlantStatus.Active))
            store.addPlant(plant) match
              case AddPlantResult.Added =>
                log.info(s"plant created $plant")
                substrate.parts.foreach(part => metrics.incrementSubstrateComponent(part.componentId))
                CreatePlantResult.Created(plant)
              case AddPlantResult.AddFailed(reason) => CreatePlantResult.CreateFailed(reason).tap(_ => log.error("create plant", reason))

    override def getPlants(status: PlantStatus): GetPlantsResult =
      store.getPlants(status).tap:
        case GetPlantsResult.ReadFailed(reason) => log.error("get plants", reason)
        case GetPlantsResult.Read(found)        =>
          metrics.setPlantsCount(status, found.size.toLong)
          metrics.setPlantsDisplayNames(status, found.map(displayName))

    override def getArchivedCount: ArchivedCountResult =
      store.getArchivedCount.tap:
        case ArchivedCountResult.ReadFailed(reason) => log.error("get archived count", reason)
        case ArchivedCountResult.Counted(count)     => metrics.setPlantsCount(PlantStatus.Archived, count)

    private def displayName(plant: Plant) =
      plant.id -> plant.details.maybeNickname.map(_.value).getOrElse(plant.details.species.value)

    override def editPlant(plant: PlantId, revise: PlantDetails => PlantDetails): EditPlantResult = lock.exclusively:
      val outcome =
        for
          edited <- readAndRevise(plant, revise)
          _      <- rejectUnknownSubstrate(edited.details.substrate)
          saved  <- persistEdit(edited)
        yield saved
      outcome.fold(identity, EditPlantResult.Edited.apply).tap:
        case EditPlantResult.Edited(edited) => log.info(s"plant edited $edited")
        case _                              => ()

    private def readAndRevise(plant: PlantId, revise: PlantDetails => PlantDetails): Either[EditPlantResult, Plant] =
      store.getPlant(plant) match
        case GetPlantResult.RecordMissing      => EditPlantResult.PlantMissing.asLeft
        case GetPlantResult.ReadFailed(reason) => EditPlantResult.EditFailed(reason).asLeft.tap(_ => log.error("edit plant", reason))
        case GetPlantResult.Read(found) if found.details.status === PlantStatus.Archived => EditPlantResult.PlantArchived.asLeft
        case GetPlantResult.Read(found)                                                  => found.focus(_.details).modify(revise).asRight

    private def rejectUnknownSubstrate(substrate: Substrate) =
      substrateStore.getSubstrateComponents match
        case GetSubstrateComponentsResult.ReadFailed(reason) =>
          EditPlantResult.CatalogReadFailed(reason).asLeft.tap(_ => log.error("edit plant", reason))
        case GetSubstrateComponentsResult.Read(components) =>
          val known = components.filter(_.status === SubstrateComponentStatus.Active).map(_.id).toSet
          Either.cond(substrate.parts.forall(part => known.contains(part.componentId)), (), EditPlantResult.UnknownComponent)

    private def persistEdit(plant: Plant): Either[EditPlantResult, Plant] =
      store.updatePlant(plant) match
        case UpdatePlantResult.Updated              => plant.asRight
        case UpdatePlantResult.UpdateFailed(reason) => EditPlantResult.EditFailed(reason).asLeft.tap(_ => log.error("edit plant", reason))

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
                case result @ AddPhotoResult.Added(added) =>
                  log.info(s"photo added $added mediaType=${content.mediaType}")
                  result
                case AddPhotoResult.PlantMissing      => compensateContentDeleteAfterMissingPlant(photo.id)
                case AddPhotoResult.AddFailed(reason) => compensateContentDeleteAfterAddFailed(photo.id, reason)

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
