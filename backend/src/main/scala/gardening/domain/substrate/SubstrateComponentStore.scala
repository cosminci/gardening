package gardening.domain.substrate

import gardening.domain.*
import gardening.domain.catalog.*

trait SubstrateComponentStore:
  def getSubstrateComponents: CatalogReadResult[SubstrateComponent]
  def addSubstrateComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent]
  def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent]
