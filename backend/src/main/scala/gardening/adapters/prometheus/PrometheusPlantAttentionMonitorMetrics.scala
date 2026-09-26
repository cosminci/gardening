package gardening.adapters.prometheus

import gardening.domain.PlantId
import gardening.ports.PlantAttentionMonitorMetricsApi
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.model.registry.PrometheusRegistry

import scala.concurrent.duration.{FiniteDuration, SECONDS}

object PrometheusPlantAttentionMonitorMetrics:

  def make(registry: PrometheusRegistry): PlantAttentionMonitorMetricsApi =
    val urgencyRatio = Gauge
      .builder()
      .name("gardening_attention_urgency_ratio")
      .help("Watering urgency ratio (elapsed / average interval), by plant")
      .labelNames("plant")
      .register(registry)
    val wateringCadenceSeconds = Gauge
      .builder()
      .name("gardening_attention_watering_cadence_seconds")
      .help("Average watering interval, by plant")
      .labelNames("plant")
      .register(registry)
    LivePlantAttentionMonitorMetrics(urgencyRatio, wateringCadenceSeconds)

  private class LivePlantAttentionMonitorMetrics(urgencyRatio: Gauge, wateringCadenceSeconds: Gauge)
      extends PlantAttentionMonitorMetricsApi:

    override def setWateringUrgencyRatio(plant: PlantId, ratio: Double): Unit =
      urgencyRatio.labelValues(plant.value).set(ratio)

    override def setWateringCadence(plant: PlantId, cadence: FiniteDuration): Unit =
      wateringCadenceSeconds.labelValues(plant.value).set(cadence.toUnit(SECONDS))
