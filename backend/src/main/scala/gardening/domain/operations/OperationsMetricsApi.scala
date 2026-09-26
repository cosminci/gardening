package gardening.domain.operations

import gardening.domain.*

trait OperationsMetricsApi:
  def incrementAction(kind: ActionType): Unit
  def incrementRepot(plant: PlantId): Unit
  def incrementMoisture(level: MoistureLevel): Unit
  def incrementSubstrateComponent(component: SubstrateComponentId): Unit
  def incrementPesticide(pesticide: PesticideId): Unit
