package gardening.domain.attention

import cats.syntax.option.*
import gardening.domain.*
import io.github.iltotore.iron.autoRefine

import java.time.{Duration, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class PlantAttentionMonitorComponentTest extends munit.FunSuite:

  private val start       = Instant.parse("2026-01-01T00:00:00Z")
  private val componentId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
  private val substrate   = Substrate.of(List(SubstratePart(componentId, 100))).toOption.getOrElse(fail("invalid substrate"))

  test("should infer cadence from the latest bounded watering samples"):
    val unknown = plant("unknown", "Balcony", "Ficus", none)
    val scored  = plant("scored", "Office", "Monstera", Nickname("Monty").some)
    val store   = StoreStub(
      Vector(unknown, scored),
      Map(unknown.id -> wateringDates(4, Duration.ofDays(2)), scored.id -> wateringDates(25, Duration.ofDays(2)))
    )
    val attention  = PlantAttentionMonitor.make(using store, FixedClock(start.plus(Duration.ofDays(50)))).getOrElse(fail("initial attention failed"))
    val projection = attention.current

    assertEquals(projection.plants.map(_.plant.id), Vector(unknown.id, scored.id))
    projection.plants.head.cadence match
      case WateringCadence.Unavailable(sampleCount, maybeElapsed) =>
        assertEquals(sampleCount, 4)
        assertEquals(maybeElapsed, Duration.ofDays(2).some)
      case cadence => fail(s"expected unavailable cadence, got $cadence")
    projection.plants(1).cadence match
      case WateringCadence.Inferred(sampleCount, average, elapsed, Urgency.Finite(_, denominator), state) =>
        assertEquals(sampleCount, 20)
        assertEquals(average, Duration.ofDays(2))
        assertEquals(elapsed, Duration.ofDays(2))
        assertEquals(denominator, average)
        assertEquals(state, WateringState.Current)
      case cadence => fail(s"expected inferred cadence, got $cadence")

    assertEquals(store.wateringRequests.get(), Vector(20))

  test("should change from current to overdue and red alert at the defined boundaries"):
    val caredFor  = plant("p1", "Office", "Ficus", none)
    val waterings = wateringDates(5, Duration.ofDays(2))
    val latest    = waterings.head
    val store     = StoreStub(Vector(caredFor), Map(caredFor.id -> waterings))
    val clock     = MutableClock(latest.plus(Duration.ofDays(2)))
    val attention = PlantAttentionMonitor.make(using store, clock).getOrElse(fail("initial attention failed"))

    assertEquals(inferredState(attention.current), WateringState.Current)
    clock.current.set(latest.plus(Duration.ofDays(2)).plusNanos(1))
    assertEquals(inferredState(attention.refreshAll), WateringState.Overdue)
    clock.current.set(latest.plus(Duration.ofDays(3)).minusNanos(1))
    assertEquals(inferredState(attention.refreshAll), WateringState.Overdue)
    clock.current.set(latest.plus(Duration.ofDays(3)))
    assertEquals(inferredState(attention.refreshAll), WateringState.RedAlert)
    clock.current.set(latest.plus(Duration.ofDays(3)).plusNanos(1))
    assertEquals(inferredState(attention.refreshAll), WateringState.RedAlert)

  test("should publish an empty projection when there are no active plants"):
    val clock     = FixedClock(start)
    val attention = PlantAttentionMonitor.make(using StoreStub(Vector.empty, Map.empty), clock).getOrElse(fail("initial attention failed"))

    assertEquals(attention.current, AttentionProjection(start, Vector.empty))

  test("should report unavailable cadence for a plant without waterings"):
    val caredFor  = plant("p1", "Office", "Ficus", none)
    val attention = PlantAttentionMonitor.make(using StoreStub(Vector(caredFor), Map(caredFor.id -> Vector.empty)), FixedClock(start))
      .getOrElse(fail("initial attention failed"))

    assertEquals(attention.current.plants, Vector(PlantAttention(caredFor, WateringCadence.Unavailable(sampleCount = 0, maybeElapsed = none))))

  test("should give equal timestamps a deterministic zero then unbounded urgency"):
    val caredFor  = plant("p1", "Office", "Ficus", none)
    val waterings = Vector.fill(5)(start)
    val store     = StoreStub(Vector(caredFor), Map(caredFor.id -> waterings))
    val clock     = MutableClock(start)
    val attention = PlantAttentionMonitor.make(using store, clock).getOrElse(fail("initial attention failed"))

    assertEquals(inferredUrgency(attention.current), Urgency.Finite(Duration.ZERO, Duration.ZERO))
    clock.current.set(start.plusNanos(1))
    assertEquals(inferredUrgency(attention.refreshAll), Urgency.Unbounded)

  test("should recompute urgency with time and no new operations"):
    val fast  = plant("fast", "Office", "Ficus", none)
    val slow  = plant("slow", "Office", "Monstera", none)
    val clock = MutableClock(start)
    val store = StoreStub(
      Vector(fast, slow),
      Map(
        fast.id -> wateringDates(5, Duration.ofDays(10), latest = start.minus(Duration.ofDays(1))),
        slow.id -> wateringDates(5, Duration.ofDays(20), latest = start.minus(Duration.ofDays(10)))
      )
    )
    val attention = PlantAttentionMonitor.make(using store, clock).getOrElse(fail("initial attention failed"))

    assertEquals(
      attention.current.plants.map(entry => entry.plant.id -> entry.cadence),
      Vector(
        fast.id -> WateringCadence.Inferred(
          sampleCount = 5,
          averageInterval = Duration.ofDays(10),
          elapsed = Duration.ofDays(1),
          urgency = Urgency.Finite(Duration.ofDays(1), Duration.ofDays(10)),
          state = WateringState.Current
        ),
        slow.id -> WateringCadence.Inferred(
          sampleCount = 5,
          averageInterval = Duration.ofDays(20),
          elapsed = Duration.ofDays(10),
          urgency = Urgency.Finite(Duration.ofDays(10), Duration.ofDays(20)),
          state = WateringState.Current
        )
      )
    )
    clock.current.set(start.plus(Duration.ofDays(20)))
    assertEquals(
      refreshedCadences(attention.refreshAll),
      Vector(
        fast.id -> Urgency.Finite(Duration.ofDays(21), Duration.ofDays(10)),
        slow.id -> Urgency.Finite(Duration.ofDays(30), Duration.ofDays(20))
      )
    )

  test("should recompute after stored edits add or remove a watering"):
    val caredFor  = plant("p1", "Office", "Ficus", none)
    val initial   = wateringDates(4, Duration.ofDays(2))
    val store     = StoreStub(Vector(caredFor), Map(caredFor.id -> initial))
    val attention = PlantAttentionMonitor.make(using store, FixedClock(start.plus(Duration.ofDays(50)))).getOrElse(fail("initial attention failed"))

    assertUnavailable(attention.current)
    store.updatedWaterings.set(Map(caredFor.id -> wateringDates(5, Duration.ofDays(2))).some)
    assertInferred(attention.refreshAll)
    store.updatedWaterings.set(Map(caredFor.id -> initial).some)
    assertUnavailable(attention.refreshAll)

  test("should retain the last complete projection when refresh fails"):
    val caredFor  = plant("p1", "Office", "Ficus", none)
    val store     = StoreStub(Vector(caredFor), Map(caredFor.id -> wateringDates(5, Duration.ofDays(2))))
    val clock     = FixedClock(start.plus(Duration.ofDays(50)))
    val attention = PlantAttentionMonitor.make(using store, clock).getOrElse(fail("initial attention failed"))

    val current = attention.current

    val failure = RuntimeException("store down")
    store.nextSamplesResult.set(GetAttentionSamplesResult.ReadFailed(failure).some)
    assertEquals(attention.refreshAll, RefreshAttentionResult.RefreshFailed(failure))
    assertEquals(attention.current, current)

  test("should reject an oversized sample without replacing the current projection"):
    val caredFor = plant("p1", "Office", "Ficus", none)
    val samples  = AtomicReference(Vector(PlantAttentionSample(caredFor, wateringDates(5, Duration.ofDays(2)))))
    val store    = new PlantAttentionStore:
      override def getAttentionSamples(size: WateringSampleSize): GetAttentionSamplesResult =
        assertEquals(size, 20)
        GetAttentionSamplesResult.Read(samples.get())
    val attention = PlantAttentionMonitor.make(using store, FixedClock(start.plus(Duration.ofDays(50)))).getOrElse(fail("initial attention failed"))
    val current   = attention.current

    samples.set(Vector(PlantAttentionSample(caredFor, wateringDates(21, Duration.ofDays(2)))))

    attention.refreshAll match
      case RefreshAttentionResult.RefreshFailed(reason) =>
        assertEquals(reason.getMessage, "attention store returned 21 waterings; expected at most 20")
      case other => fail(s"expected RefreshFailed, got $other")
    assertEquals(attention.current, current)

  test("should fail construction when the initial projection cannot be materialized"):
    val failure = RuntimeException("store down")
    val store   = StoreStub(Vector.empty, Map.empty)
    store.nextSamplesResult.set(GetAttentionSamplesResult.ReadFailed(failure).some)

    PlantAttentionMonitor.make(using store, FixedClock(start)) match
      case Left(reason) => assertEquals(reason, failure)
      case Right(_)     => fail("expected initial attention failure")

  private def inferredState(projection: AttentionProjection) =
    projection match
      case AttentionProjection(_, Vector(PlantAttention(_, WateringCadence.Inferred(_, _, _, _, state)))) => state
      case other => fail(s"expected one inferred cadence, got $other")

  private def inferredState(result: RefreshAttentionResult) =
    result match
      case RefreshAttentionResult.Refreshed(AttentionProjection(_, Vector(PlantAttention(_, WateringCadence.Inferred(_, _, _, _, state))))) =>
        state
      case other => fail(s"expected one inferred cadence, got $other")

  private def inferredUrgency(projection: AttentionProjection) =
    projection match
      case AttentionProjection(_, Vector(PlantAttention(_, WateringCadence.Inferred(_, _, _, urgency, _)))) => urgency
      case other => fail(s"expected one inferred cadence, got $other")

  private def inferredUrgency(result: RefreshAttentionResult) =
    result match
      case RefreshAttentionResult.Refreshed(AttentionProjection(_, Vector(PlantAttention(_, WateringCadence.Inferred(_, _, _, urgency, _))))) =>
        urgency
      case other => fail(s"expected one inferred cadence, got $other")

  private def refreshedCadences(result: RefreshAttentionResult) =
    result match
      case RefreshAttentionResult.Refreshed(projection) =>
        projection.plants.map:
          case PlantAttention(plant, WateringCadence.Inferred(_, _, _, urgency, _)) => plant.id -> urgency
          case other                                                                => fail(s"expected inferred cadence, got $other")
      case other => fail(s"expected refreshed projection, got $other")

  private def assertUnavailable(result: RefreshAttentionResult) =
    result match
      case RefreshAttentionResult.Refreshed(AttentionProjection(_, Vector(PlantAttention(_, _: WateringCadence.Unavailable)))) => ()
      case other => fail(s"expected unavailable cadence, got $other")

  private def assertUnavailable(projection: AttentionProjection) =
    projection match
      case AttentionProjection(_, Vector(PlantAttention(_, _: WateringCadence.Unavailable))) => ()
      case other                                                                             => fail(s"expected unavailable cadence, got $other")

  private def assertInferred(result: RefreshAttentionResult) =
    result match
      case RefreshAttentionResult.Refreshed(AttentionProjection(_, Vector(PlantAttention(_, _: WateringCadence.Inferred)))) => ()
      case other => fail(s"expected inferred cadence, got $other")

  private def plant(id: String, location: String, species: String, maybeNickname: Option[Nickname]) =
    Plant(PlantId(id), PlantDetails(Species(species), maybeNickname, Location(location), substrate, PlantStatus.Active))

  private def wateringDates(
      count: Int,
      interval: Duration,
      latest: Instant = start.plus(Duration.ofDays(48))
  ) =
    Vector.tabulate(count): index =>
      latest.minus(interval.multipliedBy(index.toLong))

  final private case class FixedClock(current: Instant) extends Clock:
    override def now(): Instant = current

  final private case class MutableClock(current: AtomicReference[Instant]) extends Clock:
    override def now(): Instant = current.get()

  private object MutableClock:
    def apply(current: Instant): MutableClock = MutableClock(AtomicReference(current))

  final private case class StoreStub(
      plants: Vector[Plant],
      waterings: Map[PlantId, Vector[Instant]],
      nextSamplesResult: AtomicReference[Option[GetAttentionSamplesResult]] = AtomicReference(none),
      updatedWaterings: AtomicReference[Option[Map[PlantId, Vector[Instant]]]] = AtomicReference(none),
      wateringRequests: AtomicReference[Vector[WateringSampleSize]] = AtomicReference(Vector.empty)
  ) extends PlantAttentionStore:
    override def getAttentionSamples(size: WateringSampleSize): GetAttentionSamplesResult =
      wateringRequests.updateAndGet(_ :+ size)
      val currentWaterings = updatedWaterings.get().getOrElse(waterings)
      nextSamplesResult.getAndSet(none).getOrElse:
        GetAttentionSamplesResult.Read:
          plants.map(plant => PlantAttentionSample(plant, currentWaterings.getOrElse(plant.id, Vector.empty).take(size)))
