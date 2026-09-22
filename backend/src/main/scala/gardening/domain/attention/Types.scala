package gardening.domain.attention

import gardening.domain.Plant

import java.time.{Duration, Instant}

enum Urgency:
  case Finite(elapsed: Duration, averageInterval: Duration)
  case Unbounded

enum WateringState:
  case Current, Overdue, RedAlert

enum WateringCadence:
  case Unavailable(sampleCount: Int, maybeElapsed: Option[Duration])
  case Inferred(
      sampleCount: Int,
      averageInterval: Duration,
      elapsed: Duration,
      urgency: Urgency,
      state: WateringState
  )

final case class PlantAttention(plant: Plant, cadence: WateringCadence)
final case class AttentionProjection(measuredAt: Instant, plants: Vector[PlantAttention])

enum RefreshAttentionResult:
  case Refreshed(projection: AttentionProjection)
  case RefreshFailed(reason: Throwable)
