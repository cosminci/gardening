package gardening.domain.attention

import gardening.domain.Plant
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MaxLength
import io.github.iltotore.iron.constraint.numeric.Interval

import java.time.Instant
import scala.concurrent.duration.*

type WateringSampleSize  = Int :| Interval.Closed[1, 20]
type WateringSampleCount = Int :| Interval.Closed[0, 20]
type WateringHistory     = Vector[Instant] :| MaxLength[20]

object WateringHistory:
  def from(dates: Vector[Instant]): Either[String, Vector[Instant] :| MaxLength[20]] = dates.refineEither[MaxLength[20]]

  extension (history: WateringHistory)
    def sampleCount: WateringSampleCount = history.size.assume[Interval.Closed[0, 20]]

final case class PlantAttentionSample(plant: Plant, wateringDates: WateringHistory)

enum GetAttentionSamplesResult:
  case Read(samples: Vector[PlantAttentionSample])
  case ReadFailed(reason: Throwable)

sealed trait WateringAttention:
  def sampleCount: WateringSampleCount

object WateringAttention:
  final case class Unavailable(sampleCount: WateringSampleCount, maybeElapsed: Option[FiniteDuration]) extends WateringAttention

  sealed trait Available extends WateringAttention:
    def averageInterval: FiniteDuration
    def elapsed: FiniteDuration

    def assess =
      if elapsed >= averageInterval + 24.hours then RedAlert(sampleCount, averageInterval, elapsed)
      else if elapsed > averageInterval then Overdue(sampleCount, averageInterval, elapsed)
      else Current(sampleCount, averageInterval, elapsed)

  final case class Current(sampleCount: WateringSampleCount, averageInterval: FiniteDuration, elapsed: FiniteDuration)  extends Available
  final case class Overdue(sampleCount: WateringSampleCount, averageInterval: FiniteDuration, elapsed: FiniteDuration)  extends Available
  final case class RedAlert(sampleCount: WateringSampleCount, averageInterval: FiniteDuration, elapsed: FiniteDuration) extends Available

final case class PlantAttention(plant: Plant, watering: WateringAttention)
final case class AttentionProjection(measuredAt: Instant, plants: Vector[PlantAttention])

enum RefreshAttentionResult:
  case Refreshed(projection: AttentionProjection)
  case RefreshFailed(reason: Throwable)
