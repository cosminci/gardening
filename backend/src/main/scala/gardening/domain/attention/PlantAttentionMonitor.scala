package gardening.domain.attention

import cats.syntax.either.*
import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.attention.WateringHistory.*
import io.github.iltotore.iron.autoRefine

import language.experimental.captureChecking

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

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
    AttentionProjection(measuredAt, samples.map(attentionFor(_, measuredAt))).asRight

  private def attentionFor(sample: PlantAttentionSample, measuredAt: Instant) =
    val dates       = sample.wateringDates
    val sampleCount = dates.sampleCount
    val watering    =
      dates.headOption match
        case None                 => WateringAttention.Unavailable(sampleCount, none)
        case Some(latestWatering) =>
          val timeSinceWatering = elapsed(latestWatering, measuredAt)
          if sampleCount < 5 then WateringAttention.Unavailable(sampleCount, timeSinceWatering.some)
          else assessWatering(dates, sampleCount, timeSinceWatering)
    PlantAttention(sample.plant, watering)

  private def assessWatering(dates: Vector[Instant], sampleCount: WateringSampleCount, timeSinceWatering: FiniteDuration) =
    val intervals = dates.reverse.sliding(2).flatMap: window =>
      window.headOption.zip(window.lastOption).map((previous, current) => elapsed(previous, current))
    val average = intervals.foldLeft(Duration.Zero)(_ + _) / (dates.size - 1L)
    WateringAttention.Current(sampleCount, average, timeSinceWatering).assess

  private def elapsed(previous: Instant, current: Instant) =
    ChronoUnit.MILLIS.between(previous, current).millis
