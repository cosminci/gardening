package gardening.domain.attention

import cats.Eq
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.{GreaterEqual, Positive}

import java.time.Instant
import scala.concurrent.duration.*

type WateringSampleSize  = Int :| Positive
type WateringSampleCount = Int :| GreaterEqual[0]
type WateringHistory     = Vector[Instant]

object WateringHistory:
  extension (history: WateringHistory)
    def sampleCount: WateringSampleCount = history.size.assume[GreaterEqual[0]]

sealed trait WateringAttention:
  def sampleCount: WateringSampleCount

object WateringAttention:
  final case class Unavailable(sampleCount: WateringSampleCount, maybeElapsed: Option[FiniteDuration]) extends WateringAttention

  sealed trait Available extends WateringAttention:
    def averageInterval: FiniteDuration
    def elapsed: FiniteDuration

    def assess(overdueGracePeriod: FiniteDuration): Available =
      if elapsed >= averageInterval + overdueGracePeriod then RedAlert(sampleCount, averageInterval, elapsed)
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
