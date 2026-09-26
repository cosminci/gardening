package gardening.adapters.prometheus

import gardening.domain.SubstrateComponentId
import gardening.domain.substrate.SubstrateCatalogMetricsApi
import io.prometheus.metrics.core.metrics.GaugeWithCallback
import io.prometheus.metrics.model.registry.PrometheusRegistry

import java.util.concurrent.atomic.AtomicReference

object PrometheusSubstrateCatalogMetrics:

  def make(registry: PrometheusRegistry): SubstrateCatalogMetricsApi =
    val displayNames = AtomicReference(Vector.empty[(SubstrateComponentId, String)])
    GaugeWithCallback
      .builder()
      .name("gardening_journal_substrate_component_info")
      .help("Always 1; joins a substrate component's current display name onto its id-labeled series")
      .labelNames("component", "name")
      .callback(cb => displayNames.get().foreach((id, name) => cb.call(1.0, id.value.toString, name)))
      .register(registry)
    LiveSubstrateCatalogMetrics(displayNames)

  private class LiveSubstrateCatalogMetrics(displayNames: AtomicReference[Vector[(SubstrateComponentId, String)]]) extends SubstrateCatalogMetricsApi:

    override def setComponentDisplayNames(components: Vector[(SubstrateComponentId, String)]): Unit =
      displayNames.set(components)
