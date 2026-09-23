package gardening.domain.attention

import cats.syntax.either.*
import cats.syntax.traverse.*
import gardening.domain.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.numeric.Interval

import language.experimental.captureChecking

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference

trait PlantAttentionMonitor:
  def current: AttentionProjection
  def refreshAll: RefreshAttentionResult

object PlantAttentionMonitor:

  def make(using store: PlantAttentionStore^, clock: Clock^): Either[Throwable, PlantAttentionMonitor^{store, clock}] =
    computeProjection.map(new LivePlantAttentionMonitor(_))

  private class LivePlantAttentionMonitor(initialProjection: AttentionProjection)(using store: PlantAttentionStore^, clock: Clock^)
      extends PlantAttentionMonitor:
    private val currentProjection = AtomicReference(initialProjection)

    override def current: AttentionProjection = currentProjection.get()

    override def refreshAll: RefreshAttentionResult =
      computeProjection match
        case Left(reason)      => RefreshAttentionResult.RefreshFailed(reason)
        case Right(projection) =>
          currentProjection.set(projection)
          RefreshAttentionResult.Refreshed(projection)

  private def computeProjection(using store: PlantAttentionStore^, clock: Clock^) =
    store.getAttentionSamples(size = 20) match
      case GetAttentionSamplesResult.ReadFailed(reason) => reason.asLeft
      case GetAttentionSamplesResult.Read(samples)      => projectionFor(samples)

  private def projectionFor(samples: Vector[PlantAttentionSample])(using clock: Clock^) =
    val measuredAt = clock.now()
    samples.traverse(attentionFor(_, measuredAt)).map(AttentionProjection(measuredAt, _))

  private def attentionFor(sample: PlantAttentionSample, measuredAt: Instant) =
    val dates        = sample.wateringDates
    val maybeElapsed = dates.headOption.map(Duration.between(_, measuredAt))
    dates.size
      .refineOption[Interval.Closed[0, 20]]
      .toRight(RuntimeException(s"attention store returned ${dates.size} waterings; expected at most 20"))
      .map: sampleCount =>
        val cadence =
          if dates.size < 5 then WateringCadence.Unavailable(sampleCount, maybeElapsed)
          else inferCadence(dates, sampleCount, measuredAt)
        PlantAttention(sample.plant, cadence)

  private def inferCadence(dates: Vector[Instant], sampleCount: WateringSampleCount, measuredAt: Instant) =
    val intervals = dates.reverse.sliding(2).map(interval => Duration.between(interval.head, interval.last))
    val average   = intervals.foldLeft(Duration.ZERO)(_.plus(_)).dividedBy(dates.size - 1L)
    val elapsed   = Duration.between(dates.head, measuredAt)
    val urgency   =
      if average.isZero && elapsed.isZero then Urgency.Finite(Duration.ZERO, Duration.ZERO)
      else if average.isZero then Urgency.Unbounded
      else Urgency.Finite(elapsed, average)
    WateringCadence.Inferred(sampleCount, average, elapsed, urgency, wateringState(elapsed, average))

  private def wateringState(elapsed: Duration, average: Duration) =
    if elapsed.compareTo(average.plus(Duration.ofHours(24))) >= 0 then WateringState.RedAlert
    else if elapsed.compareTo(average) > 0 then WateringState.Overdue
    else WateringState.Current
