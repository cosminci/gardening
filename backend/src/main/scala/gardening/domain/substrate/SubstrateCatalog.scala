package gardening.domain.substrate

import gardening.domain.*
import gardening.domain.catalog.*

import java.util.UUID

import language.experimental.captureChecking

import scala.util.chaining.scalaUtilChainingOps

enum AddSubstrateMixResult:
  case Added(entry: SubstrateMix)
  case DuplicateSubstrate
  case AddFailed(reason: Throwable)

trait SubstrateCatalog:
  def getSubstrateComponents: CatalogReadResult[SubstrateComponent]
  def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent]
  def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent]
  def getSubstrateMixes: CatalogReadResult[SubstrateMix]
  def addSubstrateMix(name: SubstrateMixName, notes: Option[SubstrateMixNotes], substrate: Substrate): AddSubstrateMixResult
  def deleteSubstrateMix(id: UUID): CatalogDeleteResult

object SubstrateCatalog:

  def make(using
      store: SubstrateStore^,
      idGen: IdGenerator^
  )(using log: Logger^): SubstrateCatalog^{store, idGen, log} =
    new LiveSubstrateCatalog

  private class LiveSubstrateCatalog(using store: SubstrateStore^, idGen: IdGenerator^)(using log: Logger^) extends SubstrateCatalog:

    override def getSubstrateComponents: CatalogReadResult[SubstrateComponent] =
      store.getSubstrateComponents.tap:
        case CatalogReadResult.ReadFailed(reason) => log.error("get substrate components", reason)
        case _                                    => ()

    override def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent] =
      store.addSubstrateComponent(SubstrateComponent(SubstrateComponentId(UUID.fromString(idGen.nextId())), data)).tap:
        case CatalogAddResult.Added(entry)      => log.info(s"substrate component added id=${entry.id.value}")
        case CatalogAddResult.AddFailed(reason) => log.error("add substrate component", reason)

    override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
      store.editSubstrateComponent(id, data).tap:
        case CatalogEditResult.Edited(entry)      => log.info(s"substrate component edited id=${entry.id.value}")
        case CatalogEditResult.EditFailed(reason) => log.error("edit substrate component", reason)
        case CatalogEditResult.RecordMissing      => ()

    override def getSubstrateMixes: CatalogReadResult[SubstrateMix] =
      store.getSubstrateMixes.tap:
        case CatalogReadResult.ReadFailed(reason) => log.error("get substrate mixes", reason)
        case _                                    => ()

    override def addSubstrateMix(name: SubstrateMixName, notes: Option[SubstrateMixNotes], substrate: Substrate): AddSubstrateMixResult =
      store.getSubstrateMixes match
        case CatalogReadResult.ReadFailed(reason) => AddSubstrateMixResult.AddFailed(reason).tap(_ => log.error("add substrate mix", reason))
        case CatalogReadResult.Read(mixes) if mixes.exists(sameComposition(_, substrate)) => AddSubstrateMixResult.DuplicateSubstrate
        case CatalogReadResult.Read(_)                                                    =>
          val mix = SubstrateMix(UUID.fromString(idGen.nextId()), name, notes, substrate)
          store.addSubstrateMix(mix) match
            case CatalogAddResult.Added(entry)      => AddSubstrateMixResult.Added(entry).tap(_ => log.info(s"substrate mix added id=${entry.id}"))
            case CatalogAddResult.AddFailed(reason) => AddSubstrateMixResult.AddFailed(reason).tap(_ => log.error("add substrate mix", reason))

    override def deleteSubstrateMix(id: UUID): CatalogDeleteResult =
      store.deleteSubstrateMix(id).tap:
        case CatalogDeleteResult.Deleted              => log.info(s"substrate mix deleted id=$id")
        case CatalogDeleteResult.DeleteFailed(reason) => log.error("delete substrate mix", reason)

    private def sameComposition(mix: SubstrateMix, substrate: Substrate) =
      mix.substrate.parts.toSet.equals(substrate.parts.toSet)
