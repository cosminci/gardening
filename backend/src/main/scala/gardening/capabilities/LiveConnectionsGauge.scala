package gardening.capabilities

trait LiveConnectionsGauge:
  def liveCount: Int
