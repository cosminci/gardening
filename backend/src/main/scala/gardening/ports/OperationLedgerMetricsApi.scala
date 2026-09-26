package gardening.ports

import gardening.domain.*
import gardening.domain.operations.*

trait OperationLedgerMetricsApi:
  def incrementAction(kind: ActionType): Unit
  def incrementRepot(plant: PlantId): Unit
  def incrementMoisture(level: MoistureLevel): Unit
  def incrementSubstrateComponent(component: SubstrateComponentId): Unit
  def incrementPesticide(pesticide: PesticideId): Unit
