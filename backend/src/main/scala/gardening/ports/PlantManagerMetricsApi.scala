package gardening.ports

import gardening.domain.*
import gardening.domain.plants.*

trait PlantManagerMetricsApi:
  def setPlantsCount(status: PlantStatus, count: Long): Unit
  def incrementSubstrateComponent(component: SubstrateComponentId): Unit
  def setPlantsDisplayNames(status: PlantStatus, plants: Vector[(PlantId, String)]): Unit
