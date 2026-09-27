package gardening.adapters.prometheus

import gardening.capabilities.LiveConnectionsGauge
import io.prometheus.metrics.core.metrics.GaugeWithCallback
import io.prometheus.metrics.model.registry.PrometheusRegistry
import ox.discard

object PrometheusAttentionFeedMetrics:

  def register(registry: PrometheusRegistry, connections: LiveConnectionsGauge): Unit =
    GaugeWithCallback
      .builder()
      .name("gardening_attention_feed_connections")
      .help("Open plant-attention WebSocket connections, tracked by heartbeat so a missed close can't leak the count")
      .callback(cb => cb.call(connections.liveCount.toDouble))
      .register(registry)
      .discard
