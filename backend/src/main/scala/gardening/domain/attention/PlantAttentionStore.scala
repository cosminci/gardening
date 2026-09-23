package gardening.domain.attention

trait PlantAttentionStore:
  def getAttentionSamples(size: WateringSampleSize): GetAttentionSamplesResult
