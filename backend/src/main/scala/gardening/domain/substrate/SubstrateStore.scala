package gardening.domain.substrate

import gardening.domain.*
import gardening.domain.catalog.*

import java.util.UUID

trait SubstrateStore:
  def getSubstrateComponents: CatalogReadResult[SubstrateComponent]
  def addSubstrateComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent]
  def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent]
  def getSubstrateMixes: CatalogReadResult[SubstrateMix]
  def addSubstrateMix(mix: SubstrateMix): CatalogAddResult[SubstrateMix]
  def deleteSubstrateMix(id: UUID): CatalogDeleteResult
