package gardening.domain.attention

import gardening.domain.Plant
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval

import java.time.{Duration, Instant}

type WateringSampleSize  = Int :| Interval.Closed[1, 20]
type WateringSampleCount = Int :| Interval.Closed[0, 20]

final case class PlantAttentionSample(plant: Plant, wateringDates: Vector[Instant])

enum GetAttentionSamplesResult:
  case Read(samples: Vector[PlantAttentionSample])
  case ReadFailed(reason: Throwable)

enum Urgency:
  case Finite(elapsed: Duration, averageInterval: Duration)
  case Unbounded

enum WateringState:
  case Current, Overdue, RedAlert

enum WateringCadence:
  case Unavailable(sampleCount: WateringSampleCount, maybeElapsed: Option[Duration])
  case Inferred(
      sampleCount: WateringSampleCount,
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
