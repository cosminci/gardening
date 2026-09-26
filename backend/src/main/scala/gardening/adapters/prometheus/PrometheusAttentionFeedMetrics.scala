package gardening.adapters.prometheus

import gardening.adapters.http.ConnectionHeartbeats
import io.prometheus.metrics.core.metrics.GaugeWithCallback
import io.prometheus.metrics.model.registry.PrometheusRegistry
import ox.discard

object PrometheusAttentionFeedMetrics:

  def register(registry: PrometheusRegistry, heartbeats: ConnectionHeartbeats): Unit =
    GaugeWithCallback
      .builder()
      .name("gardening_attention_feed_connections")
      .help("Open plant-attention WebSocket connections, tracked by heartbeat so a missed close can't leak the count")
      .callback(cb => cb.call(heartbeats.liveCount.toDouble))
      .register(registry)
      .discard
