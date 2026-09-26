package gardening.domain.attention

import cats.Eq
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MaxLength
import io.github.iltotore.iron.constraint.numeric.Interval

import java.time.Instant
import scala.concurrent.duration.*

type WateringSampleSize  = Int :| Interval.Closed[1, 20]
type WateringSampleCount = Int :| Interval.Closed[0, 20]
type WateringHistory     = Vector[Instant] :| MaxLength[20]

object WateringHistory:
  def from(dates: Vector[Instant]): Either[String, WateringHistory] = dates.refineEither[MaxLength[20]]

  extension (history: WateringHistory)
    def sampleCount: WateringSampleCount = history.size.assume[Interval.Closed[0, 20]]

sealed trait WateringAttention:
  def sampleCount: WateringSampleCount

object WateringAttention:
  final case class Unavailable(sampleCount: WateringSampleCount, maybeElapsed: Option[FiniteDuration]) extends WateringAttention

  sealed trait Available extends WateringAttention:
    def averageInterval: FiniteDuration
    def elapsed: FiniteDuration

    def assess: Available =
      if elapsed >= averageInterval + 24.hours then RedAlert(sampleCount, averageInterval, elapsed)
      else if elapsed > averageInterval then Overdue(sampleCount, averageInterval, elapsed)
      else Current(sampleCount, averageInterval, elapsed)

  final case class Current(sampleCount: WateringSampleCount, averageInterval: FiniteDuration, elapsed: FiniteDuration)  extends Available
  final case class Overdue(sampleCount: WateringSampleCount, averageInterval: FiniteDuration, elapsed: FiniteDuration)  extends Available
  final case class RedAlert(sampleCount: WateringSampleCount, averageInterval: FiniteDuration, elapsed: FiniteDuration) extends Available

enum AttentionLevel derives CanEqual:
  case Unavailable, Current, Overdue, RedAlert

object AttentionLevel:
  given Eq[AttentionLevel] = Eq.fromUniversalEquals

extension (watering: WateringAttention)
  def level: AttentionLevel = watering match
    case _: WateringAttention.Unavailable => AttentionLevel.Unavailable
    case _: WateringAttention.Current     => AttentionLevel.Current
    case _: WateringAttention.Overdue     => AttentionLevel.Overdue
    case _: WateringAttention.RedAlert    => AttentionLevel.RedAlert
