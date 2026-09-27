package gardening.domain.attention

import gardening.domain.PlantId

import java.time.Instant

final case class PlantAttentionSample(plantId: PlantId, wateringDates: WateringHistory)

enum GetAttentionSamplesResult:
  case Read(samples: Vector[PlantAttentionSample])
  case ReadFailed(reason: Throwable)

final case class PlantAttention(plantId: PlantId, watering: WateringAttention)
final case class AttentionProjection(measuredAt: Instant, plants: Vector[PlantAttention])

enum RefreshAttentionResult:
  case Refreshed(projection: AttentionProjection)
  case RefreshFailed(reason: Throwable)
