package gardening.usecases

import cats.syntax.either.*
import cats.syntax.eq.*
import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.ports.{PlantAttentionStore, PlantAttentionMonitorMetricsApi}
import gardening.capabilities.{Clock, Logger}
import gardening.domain.attention.WateringHistory.*

import language.experimental.captureChecking

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.util.chaining.scalaUtilChainingOps

trait PlantAttentionMonitor:
  def current: AttentionProjection
  def refreshAll: RefreshAttentionResult

object PlantAttentionMonitor:

  final case class Settings(minSampleCount: WateringSampleCount, historySize: WateringSampleSize, overdueGracePeriod: FiniteDuration)

  def make(settings: Settings)(using
      store: PlantAttentionStore^,
      clock: Clock^
  )(using log: Logger^, metrics: PlantAttentionMonitorMetricsApi^): PlantAttentionMonitor^{store, clock, log, metrics} =
    LivePlantAttentionMonitor(settings)

  private class LivePlantAttentionMonitor(settings: Settings)(using store: PlantAttentionStore^, clock: Clock^)(using
      log: Logger^,
      metrics: PlantAttentionMonitorMetricsApi^
  ) extends PlantAttentionMonitor:
    // A failed initial projection means the store is unreachable at startup; crash rather than serve stale data.
    @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
    private val currentProjection = computeProjection.toTry.get.tap(recordWateringMetrics).pipe(AtomicReference(_))

    override def current: AttentionProjection = currentProjection.get()

    override def refreshAll: RefreshAttentionResult = synchronized:
      computeProjection match
        case Left(reason)      => RefreshAttentionResult.RefreshFailed(reason).tap(_ => log.error("refresh attention", reason))
        case Right(projection) =>
          val previousLevels = currentProjection.get().plants.map(p => p.plantId -> p.watering.level).toMap
          currentProjection.set(projection)
          logLevelTransitions(projection, previousLevels)
          recordWateringMetrics(projection)
          RefreshAttentionResult.Refreshed(projection)

    private def computeProjection: Either[Throwable, AttentionProjection] =
      store.getAttentionSamples(size = settings.historySize) match
        case GetAttentionSamplesResult.ReadFailed(reason) => reason.asLeft
        case GetAttentionSamplesResult.Read(samples)      => projectionFor(samples).asRight

    private def projectionFor(samples: Vector[PlantAttentionSample]): AttentionProjection =
      val measuredAt = clock.now()
      AttentionProjection(measuredAt, samples.map(attentionFor(_, measuredAt)))

    private def attentionFor(sample: PlantAttentionSample, measuredAt: Instant): PlantAttention =
      val dates       = sample.wateringDates
      val sampleCount = dates.sampleCount
      val watering    =
        dates.headOption match
          case None                 => WateringAttention.Unavailable(sampleCount, none)
          case Some(latestWatering) =>
            val timeSinceWatering = elapsed(latestWatering, measuredAt)
            if sampleCount < settings.minSampleCount then WateringAttention.Unavailable(sampleCount, timeSinceWatering.some)
            else assessWatering(dates, sampleCount, timeSinceWatering)
      PlantAttention(sample.plantId, watering)

    private def assessWatering(dates: Vector[Instant], sampleCount: WateringSampleCount, timeSinceWatering: FiniteDuration): WateringAttention =
      val intervals = dates.reverse.sliding(2).flatMap: window =>
        window.headOption.zip(window.lastOption).map((previous, current) => elapsed(previous, current))
      val average = intervals.foldLeft(Duration.Zero)(_ + _) / (dates.size - 1L)
      WateringAttention.Current(sampleCount, average, timeSinceWatering).assess(settings.overdueGracePeriod)

    private def logLevelTransitions(projection: AttentionProjection, previousLevels: Map[PlantId, AttentionLevel]): Unit =
      val transitions = projection.plants.flatMap: plant =>
        val nextLevel = plant.watering.level
        previousLevels.get(plant.plantId).filter(_ =!= nextLevel).map(previousLevel => s"${plant.plantId.value}:$previousLevel->$nextLevel")
      if transitions.nonEmpty then log.info(s"attention changed ${transitions.mkString(",")}")

    private def recordWateringMetrics(projection: AttentionProjection): Unit =
      projection.plants.foreach:
        case PlantAttention(plantId, available: WateringAttention.Available) =>
          metrics.setWateringUrgencyRatio(plantId, available.elapsed.toNanos.toDouble / available.averageInterval.toNanos.toDouble)
          metrics.setWateringCadence(plantId, available.averageInterval)
        case _: PlantAttention => ()

    private def elapsed(previous: Instant, current: Instant): FiniteDuration =
      ChronoUnit.MILLIS.between(previous, current).millis
