package gardening.domain.substrate

import gardening.domain.*
import gardening.domain.catalog.*

import java.util.UUID

import language.experimental.captureChecking

import scala.util.chaining.scalaUtilChainingOps

trait SubstrateComponentCatalog:
  def getSubstrateComponents: CatalogReadResult[SubstrateComponent]
  def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent]
  def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent]

object SubstrateComponentCatalog:

  def make(using
      store: SubstrateComponentStore^,
      idGen: IdGenerator^
  )(using log: Logger^): SubstrateComponentCatalog^{store, idGen, log} =
    new LiveSubstrateComponentCatalog

  private class LiveSubstrateComponentCatalog(using store: SubstrateComponentStore^, idGen: IdGenerator^)(using log: Logger^)
      extends SubstrateComponentCatalog:

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
