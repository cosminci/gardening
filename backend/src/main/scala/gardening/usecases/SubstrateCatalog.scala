package gardening.usecases

import cats.syntax.eq.*
import gardening.domain.*
import gardening.domain.substrate.*
import gardening.ports.{SubstrateStore, SubstrateCatalogMetricsApi}
import gardening.capabilities.{IdGenerator, Logger}

import java.util.UUID

import language.experimental.captureChecking

import scala.util.chaining.scalaUtilChainingOps

enum AddSubstrateMixResult:
  case Added(entry: SubstrateMix)
  case DuplicateSubstrate
  case AddFailed(reason: Throwable)

trait SubstrateCatalog:
  def getSubstrateComponents: GetSubstrateComponentsResult
  def addSubstrateComponent(data: SubstrateComponentData): AddSubstrateComponentResult
  def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): SubstrateComponentUpdateResult
  def archiveSubstrateComponent(id: SubstrateComponentId): SubstrateComponentUpdateResult
  def getSubstrateMixes: GetSubstrateMixesResult
  def addSubstrateMix(name: SubstrateMixName, maybeNotes: Option[SubstrateMixNotes], substrate: Substrate): AddSubstrateMixResult
  def deleteSubstrateMix(id: UUID): DeleteSubstrateMixResult

object SubstrateCatalog:

  def make(using
      store: SubstrateStore^,
      idGen: IdGenerator^
  )(using log: Logger^, metrics: SubstrateCatalogMetricsApi^): SubstrateCatalog^{store, idGen, log, metrics} =
    new LiveSubstrateCatalog

  private class LiveSubstrateCatalog(using store: SubstrateStore^, idGen: IdGenerator^)(using log: Logger^, metrics: SubstrateCatalogMetricsApi^)
      extends SubstrateCatalog:

    override def getSubstrateComponents: GetSubstrateComponentsResult =
      store.getSubstrateComponents.tap:
        case GetSubstrateComponentsResult.ReadFailed(reason) => log.error("get substrate components", reason)
        case GetSubstrateComponentsResult.Read(found)        =>
          metrics.setComponentDisplayNames(found.map(component => component.id -> component.data.name.value))

    override def addSubstrateComponent(data: SubstrateComponentData): AddSubstrateComponentResult =
      store
        .addSubstrateComponent(SubstrateComponent(SubstrateComponentId(UUID.fromString(idGen.nextId())), data, SubstrateComponentStatus.Active))
        .tap:
          case AddSubstrateComponentResult.Added(entry)      => log.info(s"substrate component added $entry")
          case AddSubstrateComponentResult.AddFailed(reason) => log.error("add substrate component", reason)

    override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): SubstrateComponentUpdateResult =
      update(id)(editFn = _.copy(data = data)).tap:
        case SubstrateComponentUpdateResult.Updated(component)   => log.info(s"substrate component edited $component")
        case SubstrateComponentUpdateResult.UpdateFailed(reason) => log.error("edit substrate component", reason)
        case _                                                   => ()

    override def archiveSubstrateComponent(id: SubstrateComponentId): SubstrateComponentUpdateResult =
      update(id)(editFn = _.copy(status = SubstrateComponentStatus.Archived)).tap:
        case SubstrateComponentUpdateResult.Updated(component)   => log.info(s"substrate component archived $component")
        case SubstrateComponentUpdateResult.UpdateFailed(reason) => log.error("archive substrate component", reason)
        case _                                                   => ()

    private def update(id: SubstrateComponentId)(editFn: SubstrateComponent => SubstrateComponent): SubstrateComponentUpdateResult =
      store.getSubstrateComponent(id) match
        case GetSubstrateComponentResult.RecordMissing      => SubstrateComponentUpdateResult.ComponentMissing
        case GetSubstrateComponentResult.ReadFailed(reason) => SubstrateComponentUpdateResult.UpdateFailed(reason)
        case GetSubstrateComponentResult.Read(component) if component.status === SubstrateComponentStatus.Archived =>
          SubstrateComponentUpdateResult.ComponentArchived
        case GetSubstrateComponentResult.Read(component) =>
          val edited = editFn(component)
          store.updateSubstrateComponent(edited) match
            case UpdateSubstrateComponentResult.Updated              => SubstrateComponentUpdateResult.Updated(edited)
            case UpdateSubstrateComponentResult.UpdateFailed(reason) => SubstrateComponentUpdateResult.UpdateFailed(reason)

    override def getSubstrateMixes: GetSubstrateMixesResult =
      store.getSubstrateMixes.tap:
        case GetSubstrateMixesResult.ReadFailed(reason) => log.error("get substrate mixes", reason)
        case _                                          => ()

    override def addSubstrateMix(name: SubstrateMixName, maybeNotes: Option[SubstrateMixNotes], substrate: Substrate): AddSubstrateMixResult =
      store.getSubstrateMixes match
        case GetSubstrateMixesResult.ReadFailed(reason) => AddSubstrateMixResult.AddFailed(reason).tap(_ => log.error("add substrate mix", reason))
        case GetSubstrateMixesResult.Read(mixes) if mixes.exists(sameComposition(_, substrate)) => AddSubstrateMixResult.DuplicateSubstrate
        case GetSubstrateMixesResult.Read(_)                                                    =>
          val mix = SubstrateMix(UUID.fromString(idGen.nextId()), name, maybeNotes, substrate)
          store.saveSubstrateMix(mix) match
            case SaveSubstrateMixResult.Saved(entry)       => AddSubstrateMixResult.Added(entry).tap(_ => log.info(s"substrate mix added $entry"))
            case SaveSubstrateMixResult.SaveFailed(reason) =>
              AddSubstrateMixResult.AddFailed(reason).tap(_ => log.error("add substrate mix", reason))

    override def deleteSubstrateMix(id: UUID): DeleteSubstrateMixResult =
      store.deleteSubstrateMix(id).tap:
        case DeleteSubstrateMixResult.Deleted              => log.info(s"substrate mix deleted id=$id")
        case DeleteSubstrateMixResult.DeleteFailed(reason) => log.error("delete substrate mix", reason)

    private def sameComposition(mix: SubstrateMix, substrate: Substrate) =
      mix.substrate.parts.toSet.equals(substrate.parts.toSet)
