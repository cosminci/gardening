package gardening.domain.attention

import gardening.domain.Plant

import java.time.{Duration, Instant}

enum Urgency:
  case Finite(elapsed: Duration, averageInterval: Duration)
  case Unbounded

object Urgency:
  given Ordering[Urgency] with
    override def compare(first: Urgency, second: Urgency): Int =
      (first, second) match
        case (Urgency.Unbounded, Urgency.Unbounded)                                                     => 0
        case (Urgency.Unbounded, _)                                                                     => 1
        case (_, Urgency.Unbounded)                                                                     => -1
        case (Urgency.Finite(firstElapsed, firstAverage), Urgency.Finite(secondElapsed, secondAverage)) =>
          val firstProduct  = durationNanos(firstElapsed) * durationNanos(secondAverage)
          val secondProduct = durationNanos(secondElapsed) * durationNanos(firstAverage)
          firstProduct.compare(secondProduct)

  private def durationNanos(duration: Duration): BigInt =
    BigInt(duration.getSeconds) * 1_000_000_000 + duration.getNano

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

enum GetAttentionProjectionResult:
  case Read(projection: AttentionProjection)
  case Unavailable

enum RefreshAttentionResult:
  case Refreshed(projection: AttentionProjection)
  case RefreshFailed(reason: Throwable)
