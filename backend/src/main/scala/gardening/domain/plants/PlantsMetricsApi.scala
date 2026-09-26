package gardening.domain.plants

import gardening.domain.*

trait PlantsMetricsApi:
  def setPlantsCount(status: PlantStatus, count: Long): Unit
  def incrementSubstrateComponent(component: SubstrateComponentId): Unit
