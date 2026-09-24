package gardening.domain.substrate

import gardening.domain.*
import gardening.domain.catalog.*

import java.util.UUID

import language.experimental.captureChecking

trait SubstrateComponentCatalog:
  def getSubstrateComponents: CatalogReadResult[SubstrateComponent]
  def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent]
  def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent]

object SubstrateComponentCatalog:

  def make(using store: SubstrateComponentStore^, idGen: IdGenerator^): SubstrateComponentCatalog^{store, idGen} =
    new LiveSubstrateComponentCatalog

  private class LiveSubstrateComponentCatalog(using store: SubstrateComponentStore^, idGen: IdGenerator^)
      extends SubstrateComponentCatalog:

    override def getSubstrateComponents: CatalogReadResult[SubstrateComponent] = store.getSubstrateComponents

    override def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent] =
      store.addSubstrateComponent(SubstrateComponent(SubstrateComponentId(UUID.fromString(idGen.nextId())), data))

    override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
      store.editSubstrateComponent(id, data)
