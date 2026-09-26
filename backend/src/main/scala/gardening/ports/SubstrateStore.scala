package gardening.ports

import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.substrate.*

import java.util.UUID

trait SubstrateStore:
  def getSubstrateComponents: CatalogReadResult[SubstrateComponent]
  def getSubstrateComponent(id: SubstrateComponentId): GetSubstrateComponentResult
  def addSubstrateComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent]
  def updateSubstrateComponent(component: SubstrateComponent): UpdateSubstrateComponentResult
  def getSubstrateMixes: CatalogReadResult[SubstrateMix]
  def addSubstrateMix(mix: SubstrateMix): CatalogAddResult[SubstrateMix]
  def deleteSubstrateMix(id: UUID): CatalogDeleteResult
