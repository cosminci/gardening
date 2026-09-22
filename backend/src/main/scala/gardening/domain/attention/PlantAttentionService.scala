package gardening.domain.attention

import cats.syntax.either.*
import cats.syntax.traverse.*
import gardening.domain.*
import gardening.domain.journal.*
import io.github.iltotore.iron.autoRefine

import language.experimental.captureChecking

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference

trait PlantAttentionService:
  def current: AttentionProjection
  def refreshAll: RefreshAttentionResult

object PlantAttentionService:

  def make(using store: PlantJournalStore^, clock: Clock^): Either[Throwable, PlantAttentionService^{store, clock}] =
    computeProjection.map(new LivePlantAttentionService(_))

  final private case class SampledPlant(plant: Plant, wateringDates: Vector[Instant])

  private class LivePlantAttentionService(initialProjection: AttentionProjection)(using store: PlantJournalStore^, clock: Clock^)
      extends PlantAttentionService:
    private val currentProjection = AtomicReference(initialProjection)

    override def current: AttentionProjection = currentProjection.get()

    override def refreshAll: RefreshAttentionResult =
      computeProjection match
        case Left(reason)      => RefreshAttentionResult.RefreshFailed(reason)
        case Right(projection) =>
          currentProjection.set(projection)
          RefreshAttentionResult.Refreshed(projection)

  private def computeProjection(using store: PlantJournalStore^, clock: Clock^) =
    store.getPlants match
      case GetPlantsResult.ReadFailed(reason) => reason.asLeft
      case GetPlantsResult.Read(plants)       => plants.traverse(readSample).map(projectionFor)

  private def readSample(plant: Plant)(using store: PlantJournalStore^) =
    store.getOperations(plant.id, OperationSelection.Watering, OperationWindow(offset = 0, size = 20)) match
      case GetOperationsResult.ReadFailed(reason) => reason.asLeft
      case GetOperationsResult.Read(page)         => SampledPlant(plant, page.operations.map(_.date)).asRight

  private def projectionFor(samples: Vector[SampledPlant])(using clock: Clock^) =
    val measuredAt = clock.now()
    val plants     = samples.iterator.map(attentionFor(_, measuredAt)).toVector
    AttentionProjection(measuredAt, plants)

  private def attentionFor(sample: SampledPlant, measuredAt: Instant) =
    val dates        = sample.wateringDates
    val maybeElapsed = dates.headOption.map(Duration.between(_, measuredAt))
    val cadence      =
      if dates.size < 5 then WateringCadence.Unavailable(dates.size, maybeElapsed)
      else inferCadence(dates, measuredAt)
    PlantAttention(sample.plant, cadence)

  private def inferCadence(dates: Vector[Instant], measuredAt: Instant) =
    val intervals = dates.reverse.sliding(2).map(interval => Duration.between(interval.head, interval.last))
    val average   = intervals.foldLeft(Duration.ZERO)(_.plus(_)).dividedBy(dates.size - 1L)
    val elapsed   = Duration.between(dates.head, measuredAt)
    val urgency   =
      if average.isZero && elapsed.isZero then Urgency.Finite(Duration.ZERO, Duration.ZERO)
      else if average.isZero then Urgency.Unbounded
      else Urgency.Finite(elapsed, average)
    WateringCadence.Inferred(dates.size, average, elapsed, urgency, wateringState(elapsed, average))

  private def wateringState(elapsed: Duration, average: Duration) =
    if elapsed.compareTo(average.plus(Duration.ofHours(24))) >= 0 then WateringState.RedAlert
    else if elapsed.compareTo(average) > 0 then WateringState.Overdue
    else WateringState.Current
