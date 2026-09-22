package gardening.domain.attention

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.journal.*
import io.github.iltotore.iron.autoRefine

import java.time.{Duration, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class PlantAttentionComponentTest extends munit.FunSuite:

  private val start       = Instant.parse("2026-01-01T00:00:00Z")
  private val componentId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
  private val substrate   = Substrate.of(List(SubstratePart(componentId, 100))).toOption.getOrElse(fail("invalid substrate"))

  test("should infer cadence from the latest bounded watering samples"):
    val unknown = plant("unknown", "Balcony", "Ficus", none)
    val scored  = plant("scored", "Office", "Monstera", Nickname("Monty").some)
    val store   = StoreStub(
      plants = Vector(unknown, scored),
      waterings = Map(
        unknown.id -> wateringOperations(unknown.id, 4, Duration.ofDays(2)),
        scored.id  -> wateringOperations(scored.id, 25, Duration.ofDays(2))
      )
    )
    val attention = PlantAttentionService.make(using store, FixedClock(start.plus(Duration.ofDays(50))))

    attention.refreshAll match
      case RefreshAttentionResult.Refreshed(projection) =>
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
      case result => fail(s"expected refreshed projection, got $result")

    assertEquals(
      store.operationRequests.get(),
      Vector(
        (unknown.id, OperationSelection.Watering, OperationWindow(offset = 0, size = 20)),
        (scored.id, OperationSelection.Watering, OperationWindow(offset = 0, size = 20))
      )
    )

  test("should change from current to overdue and red alert at the defined boundaries"):
    val caredFor  = plant("p1", "Office", "Ficus", none)
    val waterings = wateringOperations(caredFor.id, 5, Duration.ofDays(2))
    val latest    = waterings.head.date
    val store     = StoreStub(Vector(caredFor), Map(caredFor.id -> waterings))
    val clock     = MutableClock(latest.plus(Duration.ofDays(2)))
    val attention = PlantAttentionService.make(using store, clock)

    assertEquals(inferredState(attention.refreshAll), WateringState.Current)
    clock.current.set(latest.plus(Duration.ofDays(2)).plusNanos(1))
    assertEquals(inferredState(attention.refreshAll), WateringState.Overdue)
    clock.current.set(latest.plus(Duration.ofDays(3)).minusNanos(1))
    assertEquals(inferredState(attention.refreshAll), WateringState.Overdue)
    clock.current.set(latest.plus(Duration.ofDays(3)))
    assertEquals(inferredState(attention.refreshAll), WateringState.RedAlert)
    clock.current.set(latest.plus(Duration.ofDays(3)).plusNanos(1))
    assertEquals(inferredState(attention.refreshAll), WateringState.RedAlert)

  test("should publish an empty projection when there are no active plants"):
    val attention = PlantAttentionService.make(using StoreStub(Vector.empty, Map.empty), FixedClock(start))

    attention.refreshAll match
      case RefreshAttentionResult.Refreshed(projection) =>
        assertEquals(projection, AttentionProjection(start, Vector.empty))
      case other => fail(s"expected empty refreshed projection, got $other")

  test("should give equal timestamps a deterministic zero then unbounded urgency"):
    val caredFor  = plant("p1", "Office", "Ficus", none)
    val waterings = Vector.tabulate(5)(index => watering(caredFor.id, index, start))
    val store     = StoreStub(Vector(caredFor), Map(caredFor.id -> waterings))
    val clock     = MutableClock(start)
    val attention = PlantAttentionService.make(using store, clock)

    assertEquals(inferredUrgency(attention.refreshAll), Urgency.Finite(Duration.ZERO, Duration.ZERO))
    clock.current.set(start.plusNanos(1))
    assertEquals(inferredUrgency(attention.refreshAll), Urgency.Unbounded)

    val ordering = summon[Ordering[Urgency]]
    assertEquals(ordering.compare(Urgency.Unbounded, Urgency.Unbounded), 0)
    assert(ordering.compare(Urgency.Unbounded, Urgency.Finite(Duration.ZERO, Duration.ZERO)) > 0)
    assert(ordering.compare(Urgency.Finite(Duration.ZERO, Duration.ZERO), Urgency.Unbounded) < 0)

  test("should order unknown cadence first and use urgency then plant fields"):
    val unknownFirst      = plant("z", "Balcony", "Ficus", none)
    val unknownLast       = plant("y", "Kitchen", "Anthurium", none)
    val urgentLater       = plant("c", "Office", "Ficus", none)
    val tiedLocationFirst = plant("f", "Balcony", "Zamioculcas", none)
    val urgentSooner      = plant("b", "Office", "Ficus", none)
    val tiedNoName        = plant("a", "Office", "Monstera", none)
    val tiedWithName      = plant("d", "Office", "Monstera", Nickname("Monty").some)
    val sameNamedLast     = plant("e", "Office", "Monstera", Nickname("Monty").some)
    val tiedOtherName     = plant("g", "Office", "Monstera", Nickname("Zed").some)
    val store             = StoreStub(
      Vector(
        tiedOtherName,
        sameNamedLast,
        tiedWithName,
        tiedNoName,
        urgentSooner,
        tiedLocationFirst,
        urgentLater,
        unknownLast,
        unknownFirst
      ),
      Map(
        unknownFirst.id      -> wateringOperations(unknownFirst.id, 4, Duration.ofDays(2)),
        unknownLast.id       -> wateringOperations(unknownLast.id, 4, Duration.ofDays(2)),
        urgentLater.id       -> wateringOperations(urgentLater.id, 5, Duration.ofDays(1), latest = start.minus(Duration.ofDays(2))),
        tiedLocationFirst.id -> wateringOperations(tiedLocationFirst.id, 5, Duration.ofDays(2), latest = start.minus(Duration.ofDays(2))),
        urgentSooner.id      -> wateringOperations(urgentSooner.id, 5, Duration.ofDays(2), latest = start.minus(Duration.ofDays(2))),
        tiedNoName.id        -> wateringOperations(tiedNoName.id, 5, Duration.ofDays(2), latest = start.minus(Duration.ofDays(2))),
        tiedWithName.id      -> wateringOperations(tiedWithName.id, 5, Duration.ofDays(2), latest = start.minus(Duration.ofDays(2))),
        sameNamedLast.id     -> wateringOperations(sameNamedLast.id, 5, Duration.ofDays(2), latest = start.minus(Duration.ofDays(2))),
        tiedOtherName.id     -> wateringOperations(tiedOtherName.id, 5, Duration.ofDays(2), latest = start.minus(Duration.ofDays(2)))
      )
    )
    val attention = PlantAttentionService.make(using store, FixedClock(start))

    attention.refreshAll match
      case RefreshAttentionResult.Refreshed(projection) =>
        assertEquals(
          projection.plants.map(_.plant.id),
          Vector(
            unknownFirst.id,
            unknownLast.id,
            urgentLater.id,
            tiedLocationFirst.id,
            urgentSooner.id,
            tiedNoName.id,
            tiedWithName.id,
            sameNamedLast.id,
            tiedOtherName.id
          )
        )
      case result => fail(s"expected refreshed projection, got $result")

  test("should reorder with time and no new operations"):
    val fast  = plant("fast", "Office", "Ficus", none)
    val slow  = plant("slow", "Office", "Monstera", none)
    val clock = MutableClock(start)
    val store = StoreStub(
      Vector(fast, slow),
      Map(
        fast.id -> wateringOperations(fast.id, 5, Duration.ofDays(10), latest = start.minus(Duration.ofDays(1))),
        slow.id -> wateringOperations(slow.id, 5, Duration.ofDays(20), latest = start.minus(Duration.ofDays(10)))
      )
    )
    val attention = PlantAttentionService.make(using store, clock)

    assertEquals(refreshedPlantIds(attention.refreshAll), Vector(slow.id, fast.id))
    clock.current.set(start.plus(Duration.ofDays(20)))
    assertEquals(refreshedPlantIds(attention.refreshAll), Vector(fast.id, slow.id))

  test("should recompute after stored edits add or remove a watering"):
    val caredFor  = plant("p1", "Office", "Ficus", none)
    val initial   = wateringOperations(caredFor.id, 4, Duration.ofDays(2))
    val store     = StoreStub(Vector(caredFor), Map(caredFor.id -> initial))
    val attention = PlantAttentionService.make(using store, FixedClock(start.plus(Duration.ofDays(50))))

    assertUnavailable(attention.refreshAll)
    store.updatedWaterings.set(Map(caredFor.id -> wateringOperations(caredFor.id, 5, Duration.ofDays(2))).some)
    assertInferred(attention.refreshAll)
    store.updatedWaterings.set(Map(caredFor.id -> initial).some)
    assertUnavailable(attention.refreshAll)

  test("should retain the last complete projection when refresh fails"):
    val caredFor  = plant("p1", "Office", "Ficus", none)
    val store     = StoreStub(Vector(caredFor), Map(caredFor.id -> wateringOperations(caredFor.id, 5, Duration.ofDays(2))))
    val clock     = FixedClock(start.plus(Duration.ofDays(50)))
    val attention = PlantAttentionService.make(using store, clock)

    assertEquals(attention.current, GetAttentionProjectionResult.Unavailable)
    val refreshed = attention.refreshAll
    refreshed match
      case RefreshAttentionResult.Refreshed(_) => ()
      case other                               => fail(s"expected refreshed projection, got $other")
    val current = attention.current

    val failure = RuntimeException("store down")
    store.nextPlantsResult.set(GetPlantsResult.ReadFailed(failure).some)
    assertEquals(attention.refreshAll, RefreshAttentionResult.RefreshFailed(failure))
    assertEquals(attention.current, current)

    val historyFailure = RuntimeException("history down")
    store.nextOperationsResult.set(GetOperationsResult.ReadFailed(historyFailure).some)
    assertEquals(attention.refreshAll, RefreshAttentionResult.RefreshFailed(historyFailure))
    assertEquals(attention.current, current)

  private def inferredState(result: RefreshAttentionResult): WateringState =
    result match
      case RefreshAttentionResult.Refreshed(AttentionProjection(_, Vector(PlantAttention(_, WateringCadence.Inferred(_, _, _, _, state))))) =>
        state
      case other => fail(s"expected one inferred cadence, got $other")

  private def inferredUrgency(result: RefreshAttentionResult): Urgency =
    result match
      case RefreshAttentionResult.Refreshed(AttentionProjection(_, Vector(PlantAttention(_, WateringCadence.Inferred(_, _, _, urgency, _))))) =>
        urgency
      case other => fail(s"expected one inferred cadence, got $other")

  private def refreshedPlantIds(result: RefreshAttentionResult): Vector[PlantId] =
    result match
      case RefreshAttentionResult.Refreshed(projection) => projection.plants.map(_.plant.id)
      case other                                        => fail(s"expected refreshed projection, got $other")

  private def assertUnavailable(result: RefreshAttentionResult): Unit =
    result match
      case RefreshAttentionResult.Refreshed(AttentionProjection(_, Vector(PlantAttention(_, _: WateringCadence.Unavailable)))) => ()
      case other => fail(s"expected unavailable cadence, got $other")

  private def assertInferred(result: RefreshAttentionResult): Unit =
    result match
      case RefreshAttentionResult.Refreshed(AttentionProjection(_, Vector(PlantAttention(_, _: WateringCadence.Inferred)))) => ()
      case other => fail(s"expected inferred cadence, got $other")

  private def plant(id: String, location: String, species: String, maybeNickname: Option[Nickname]) =
    Plant(PlantId(id), PlantDetails(Species(species), maybeNickname, Location(location), substrate, PlantStatus.Active))

  private def wateringOperations(
      plantId: PlantId,
      count: Int,
      interval: Duration,
      latest: Instant = start.plus(Duration.ofDays(48))
  ): Vector[Operation] =
    Vector.tabulate(count): index =>
      watering(plantId, index, latest.minus(interval.multipliedBy(index.toLong)))

  private def watering(plantId: PlantId, index: Int, date: Instant) =
    Operation(
      OperationId(f"watering-$index%02d"),
      plantId,
      date,
      OperationDetails.Care(Set(ActionType.Watered), Set.empty, MoistureLevel.Wet, none)
    )

  final private case class FixedClock(current: Instant) extends Clock:
    override def now(): Instant = current

  final private case class MutableClock(current: AtomicReference[Instant]) extends Clock:
    override def now(): Instant = current.get()

  private object MutableClock:
    def apply(current: Instant): MutableClock = MutableClock(AtomicReference(current))

  final private case class StoreStub(
      plants: Vector[Plant],
      waterings: Map[PlantId, Vector[Operation]],
      nextPlantsResult: AtomicReference[Option[GetPlantsResult]] = AtomicReference(none),
      nextOperationsResult: AtomicReference[Option[GetOperationsResult]] = AtomicReference(none),
      updatedWaterings: AtomicReference[Option[Map[PlantId, Vector[Operation]]]] = AtomicReference(none),
      operationRequests: AtomicReference[Vector[(PlantId, OperationSelection, OperationWindow)]] = AtomicReference(Vector.empty)
  ) extends PlantJournalStore:
    override def getPlants: GetPlantsResult            = nextPlantsResult.getAndSet(none).getOrElse(GetPlantsResult.Read(plants))
    override def getPlant(id: PlantId): GetPlantResult =
      plants.find(_.id.equals(id)).fold[GetPlantResult](GetPlantResult.RecordMissing)(GetPlantResult.Read.apply)
    override def getOperations(plantId: PlantId, selection: OperationSelection, window: OperationWindow): GetOperationsResult =
      operationRequests.updateAndGet(_ :+ ((plantId, selection, window)))
      val currentWaterings = updatedWaterings.get().getOrElse(waterings)
      nextOperationsResult.getAndSet(none).getOrElse:
        GetOperationsResult.Read(OperationPage(currentWaterings.getOrElse(plantId, Vector.empty).take(window.size), hasNextPage = false))
    override def getOperation(id: OperationId): GetOperationResult      = GetOperationResult.RecordMissing
    override def addOperation(operation: Operation): LogOperationResult = LogOperationResult.LoggingFailed(UnsupportedOperationException())
    override def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult =
      EditOperationResult.EditFailed(UnsupportedOperationException())
    override def removeOperation(id: OperationId): OperationCompensationResult =
      OperationCompensationResult.CompensationFailed(UnsupportedOperationException())
    override def restoreOperation(operation: Operation): OperationCompensationResult =
      OperationCompensationResult.CompensationFailed(UnsupportedOperationException())
    override def updatePlant(plant: Plant): UpdatePlantResult                  = UpdatePlantResult.UpdateFailed(UnsupportedOperationException())
    override def getSubstrateComponents: CatalogReadResult[SubstrateComponent] =
      CatalogReadResult.ReadFailed(UnsupportedOperationException())
    override def addSubstrateComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent] =
      CatalogAddResult.AddFailed(UnsupportedOperationException())
    override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
      CatalogEditResult.EditFailed(UnsupportedOperationException())
    override def getPesticides: CatalogReadResult[Pesticide]                     = CatalogReadResult.ReadFailed(UnsupportedOperationException())
    override def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide] =
      CatalogAddResult.AddFailed(UnsupportedOperationException())
    override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
      CatalogEditResult.EditFailed(UnsupportedOperationException())
