package gardening.adapters.prometheus

import gardening.domain.{PlantId, SubstrateComponentId}
import gardening.domain.plants.{PlantStatus, PlantsMetricsApi}
import io.prometheus.metrics.core.metrics.{Counter, Gauge, GaugeWithCallback}
import io.prometheus.metrics.model.registry.PrometheusRegistry
import ox.discard

import java.util.concurrent.atomic.AtomicReference

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
    val displayNames = AtomicReference(Map.empty[PlantStatus, Vector[(PlantId, String)]])
    GaugeWithCallback
      .builder()
      .name("gardening_journal_plant_info")
      .help("Always 1; joins a plant's current display name onto its id-labeled series")
      .labelNames("plant", "name")
      .callback(cb => displayNames.get().values.flatten.foreach((id, name) => cb.call(1.0, id.value, name)))
      .register(registry)
    (LivePlantsMetrics(plantsCount, substrateComponentUsageTotal, displayNames), substrateComponentUsageTotal)

  private class LivePlantsMetrics(
      plantsCount: Gauge,
      substrateComponentUsageTotal: Counter,
      displayNames: AtomicReference[Map[PlantStatus, Vector[(PlantId, String)]]]
  ) extends PlantsMetricsApi:

    override def setPlantsCount(status: PlantStatus, count: Long): Unit =
      plantsCount.labelValues(statusLabel(status)).set(count.toDouble)

    override def incrementSubstrateComponent(component: SubstrateComponentId): Unit =
      substrateComponentUsageTotal.labelValues(component.value.toString).inc()

    /**
     * Replaces this status's whole slice rather than merging per-plant, so a rename or archival is reflected on the very next read — no history to
     * drift, unlike an incremented series.
     */
    override def setPlantsDisplayNames(status: PlantStatus, plants: Vector[(PlantId, String)]): Unit =
      displayNames.updateAndGet(_.updated(status, plants)).discard

    private def statusLabel(status: PlantStatus): String = status match
      case PlantStatus.Active   => "active"
      case PlantStatus.Archived => "archived"
