package gardening.ports

import gardening.domain.*
import gardening.domain.substrate.*

import java.util.UUID

trait SubstrateStore:
  def getSubstrateComponents: GetSubstrateComponentsResult
  def getSubstrateComponent(id: SubstrateComponentId): GetSubstrateComponentResult
  def addSubstrateComponent(component: SubstrateComponent): AddSubstrateComponentResult
  def updateSubstrateComponent(component: SubstrateComponent): UpdateSubstrateComponentResult
  def getSubstrateMixes: GetSubstrateMixesResult
  def saveSubstrateMix(mix: SubstrateMix): SaveSubstrateMixResult
  def deleteSubstrateMix(id: UUID): DeleteSubstrateMixResult
