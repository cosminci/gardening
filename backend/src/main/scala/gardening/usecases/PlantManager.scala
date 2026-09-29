package gardening.usecases

import cats.syntax.either.*
import cats.syntax.eq.*
import gardening.domain.*
import gardening.domain.plants.*
import gardening.domain.substrate.GetSubstrateComponentsResult
import gardening.ports.{PlantStore, PlantManagerMetricsApi, SubstrateStore}
import gardening.capabilities.{IdGenerator, PlantUpdateLock, Logger}
import monocle.syntax.all.*

import language.experimental.captureChecking

import scala.util.chaining.scalaUtilChainingOps

trait PlantManager:
  def createPlant(species: Species, maybeNickname: Option[Nickname], location: Location, substrate: Substrate): CreatePlantResult
  def getPlants(status: PlantStatus): GetPlantsResult
  def getArchivedCount: ArchivedCountResult
  def editPlant(plant: PlantId, revise: PlantDetails => PlantDetails): EditPlantResult

object PlantManager:

  def make(using
      store: PlantStore^,
      substrateStore: SubstrateStore^,
      idGen: IdGenerator^,
      lock: PlantUpdateLock^
  )(using
      log: Logger^,
      metrics: PlantManagerMetricsApi^
  ): PlantManager^{store, substrateStore, idGen, lock, log, metrics} =
    new LivePlantManager

  private class LivePlantManager(using
      store: PlantStore^,
      substrateStore: SubstrateStore^,
      idGen: IdGenerator^,
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
