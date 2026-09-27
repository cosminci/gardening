package gardening.ports

import gardening.domain.PlantId

import scala.concurrent.duration.FiniteDuration

trait PlantAttentionMonitorMetricsApi:
  def setWateringUrgencyRatio(plant: PlantId, ratio: Double): Unit
  def setWateringCadence(plant: PlantId, cadence: FiniteDuration): Unit
