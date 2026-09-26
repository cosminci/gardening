package gardening.adapters.prometheus

import gardening.domain.SubstrateComponentId
import gardening.domain.plants.{PlantStatus, PlantsMetricsApi}
import io.prometheus.metrics.core.metrics.{Counter, Gauge}
import io.prometheus.metrics.model.registry.PrometheusRegistry

object PrometheusPlantsMetrics:

  /**
   * `substrateComponentUsageTotal` is also incremented by [[PrometheusOperationsMetrics]] (a repot uses the same counter as an initial mix), so it's
   * returned here to be shared rather than registered twice against the same registry under the same name.
   */
  def make(registry: PrometheusRegistry): (PlantsMetricsApi, Counter) =
    val plantsCount = Gauge
      .builder()
      .name("gardening_journal_plants")
      .help("Current plant count, by status")
      .labelNames("status")
      .register(registry)
    val substrateComponentUsageTotal = Counter
      .builder()
      .name("gardening_journal_substrate_component_usage_total")
      .help("Substrate component usage, by component")
      .labelNames("component")
      .register(registry)
    (LivePlantsMetrics(plantsCount, substrateComponentUsageTotal), substrateComponentUsageTotal)

  private class LivePlantsMetrics(plantsCount: Gauge, substrateComponentUsageTotal: Counter) extends PlantsMetricsApi:

    override def setPlantsCount(status: PlantStatus, count: Long): Unit =
      plantsCount.labelValues(statusLabel(status)).set(count.toDouble)

    override def incrementSubstrateComponent(component: SubstrateComponentId): Unit =
      substrateComponentUsageTotal.labelValues(component.value.toString).inc()

    private def statusLabel(status: PlantStatus): String = status match
      case PlantStatus.Active   => "active"
      case PlantStatus.Archived => "archived"
