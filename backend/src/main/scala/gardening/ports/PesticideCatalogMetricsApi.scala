package gardening.ports

import gardening.domain.PesticideId

trait PesticideCatalogMetricsApi:
  def setPesticideDisplayNames(pesticides: Vector[(PesticideId, String)]): Unit
