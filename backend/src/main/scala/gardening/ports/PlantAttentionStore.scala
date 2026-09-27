package gardening.ports

import gardening.domain.attention.*

trait PlantAttentionStore:
  def getAttentionSamples(size: WateringSampleSize): GetAttentionSamplesResult
