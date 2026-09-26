package gardening.domain.pesticide

import gardening.domain.PesticideId

trait PesticideCatalogMetricsApi:
  def setPesticideDisplayNames(pesticides: Vector[(PesticideId, String)]): Unit
