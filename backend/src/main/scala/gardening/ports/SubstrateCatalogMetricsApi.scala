package gardening.ports

import gardening.domain.SubstrateComponentId

trait SubstrateCatalogMetricsApi:
  def setComponentDisplayNames(components: Vector[(SubstrateComponentId, String)]): Unit
