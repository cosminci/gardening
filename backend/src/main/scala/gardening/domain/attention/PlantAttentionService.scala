package gardening.domain.attention

import gardening.domain.*
import gardening.domain.journal.*
import io.github.iltotore.iron.autoRefine

import language.experimental.captureChecking

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

trait PlantAttentionProjection:
  def current: GetAttentionProjectionResult

trait PlantAttentionRefresh:
  def refreshAll: RefreshAttentionResult

trait PlantAttentionService extends PlantAttentionProjection, PlantAttentionRefresh

object PlantAttentionService:

  private val plantOrdering: Ordering[Plant] =
    Ordering.by: plant =>
      (
        plant.details.location.value,
        plant.details.species.value,
        plant.details.maybeNickname.map(_.value),
        plant.id.value
      )

  def make(using store: PlantJournalStore^, clock: Clock^): PlantAttentionService^{store, clock} =
    new LivePlantAttention

  final private case class SampledPlant(plant: Plant, wateringDates: Vector[Instant])

  private class LivePlantAttention(using store: PlantJournalStore^, clock: Clock^) extends PlantAttentionService:
    private val materialized = AtomicReference(Option.empty[AttentionProjection])

    override def current: GetAttentionProjectionResult =
      materialized.get().fold[GetAttentionProjectionResult](GetAttentionProjectionResult.Unavailable)(GetAttentionProjectionResult.Read.apply)

    override def refreshAll: RefreshAttentionResult =
      store.getPlants match
        case GetPlantsResult.ReadFailed(reason) => RefreshAttentionResult.RefreshFailed(reason)
        case GetPlantsResult.Read(plants)       =>
          readSamples(plants) match
            case Left(reason)   => RefreshAttentionResult.RefreshFailed(reason)
            case Right(samples) => publish(samples)

    private def readSamples(plants: Vector[Plant]): Either[Throwable, Vector[SampledPlant]] =
      @tailrec
      def loop(remaining: List[Plant], samples: Vector[SampledPlant]): Either[Throwable, Vector[SampledPlant]] =
        remaining match
          case Nil           => Right(samples)
          case plant :: tail =>
            readSample(plant) match
              case Left(reason)  => Left(reason)
              case Right(sample) => loop(tail, samples.appended(sample))
      loop(plants.toList, Vector.empty)

    private def readSample(plant: Plant): Either[Throwable, SampledPlant] =
      store.getOperations(
        plant.id,
        OperationSelection.Watering,
        OperationWindow(offset = 0, size = 20)
      ) match
        case GetOperationsResult.ReadFailed(reason) => Left(reason)
        case GetOperationsResult.Read(page)         => Right(SampledPlant(plant, page.operations.map(_.date)))

    private def publish(samples: Vector[SampledPlant]): RefreshAttentionResult =
      val measuredAt = clock.now()
      val plants     = samples.iterator.map(attentionFor(_, measuredAt)).toVector.sortWith(precedes)
      val projection = AttentionProjection(measuredAt, plants)
      materialized.set(Some(projection))
      RefreshAttentionResult.Refreshed(projection)

    private def attentionFor(sample: SampledPlant, measuredAt: Instant): PlantAttention =
      val dates        = sample.wateringDates
      val maybeElapsed = dates.headOption.map(Duration.between(_, measuredAt))
      val cadence      =
        if dates.size < 5 then WateringCadence.Unavailable(dates.size, maybeElapsed)
        else inferredCadence(dates, measuredAt)
      PlantAttention(sample.plant, cadence)

    private def inferredCadence(dates: Vector[Instant], measuredAt: Instant): WateringCadence =
      val chronological = dates.reverse
      val intervals     = chronological.zip(chronological.drop(1)).map: (previous, current) =>
        Duration.between(previous, current)
      val average = intervals.foldLeft(Duration.ZERO)(_.plus(_)).dividedBy(dates.size - 1L)
      val elapsed = Duration.between(dates.head, measuredAt)
      val urgency =
        if average.isZero && elapsed.isZero then Urgency.Finite(Duration.ZERO, Duration.ZERO)
        else if average.isZero then Urgency.Unbounded
        else Urgency.Finite(elapsed, average)
      WateringCadence.Inferred(dates.size, average, elapsed, urgency, wateringState(elapsed, average))

    private def wateringState(elapsed: Duration, average: Duration): WateringState =
      if elapsed.compareTo(average.plus(Duration.ofHours(24))) >= 0 then WateringState.RedAlert
      else if elapsed.compareTo(average) > 0 then WateringState.Overdue
      else WateringState.Current

    private def precedes(first: PlantAttention, second: PlantAttention): Boolean =
      compareCadence(first.cadence, second.cadence) match
        case 0          => comparePlants(first.plant, second.plant) < 0
        case comparison => comparison < 0

    private def compareCadence(first: WateringCadence, second: WateringCadence): Int =
      (first, second) match
        case (_: WateringCadence.Unavailable, _: WateringCadence.Inferred)                                             => -1
        case (_: WateringCadence.Inferred, _: WateringCadence.Unavailable)                                             => 1
        case (_: WateringCadence.Unavailable, _: WateringCadence.Unavailable)                                          => 0
        case (WateringCadence.Inferred(_, _, _, firstUrgency, _), WateringCadence.Inferred(_, _, _, secondUrgency, _)) =>
          -summon[Ordering[Urgency]].compare(firstUrgency, secondUrgency)

    private def comparePlants(first: Plant, second: Plant): Int =
      plantOrdering.compare(first, second)
