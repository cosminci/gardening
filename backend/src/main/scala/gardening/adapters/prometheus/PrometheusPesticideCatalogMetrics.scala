package gardening.adapters.prometheus

import gardening.domain.PesticideId
import gardening.domain.pesticide.PesticideCatalogMetricsApi
import io.prometheus.metrics.core.metrics.GaugeWithCallback
import io.prometheus.metrics.model.registry.PrometheusRegistry

import java.util.concurrent.atomic.AtomicReference

object PrometheusPesticideCatalogMetrics:

  def make(registry: PrometheusRegistry): PesticideCatalogMetricsApi =
    val displayNames = AtomicReference(Vector.empty[(PesticideId, String)])
    GaugeWithCallback
      .builder()
      .name("gardening_journal_pesticide_info")
      .help("Always 1; joins a pesticide's current display name onto its id-labeled series")
      .labelNames("pesticide", "name")
      .callback(cb => displayNames.get().foreach((id, name) => cb.call(1.0, id.value.toString, name)))
      .register(registry)
    LivePesticideCatalogMetrics(displayNames)

  private class LivePesticideCatalogMetrics(displayNames: AtomicReference[Vector[(PesticideId, String)]]) extends PesticideCatalogMetricsApi:

    override def setPesticideDisplayNames(pesticides: Vector[(PesticideId, String)]): Unit =
      displayNames.set(pesticides)
