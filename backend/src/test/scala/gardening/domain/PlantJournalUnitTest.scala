package gardening.domain

import cats.data.NonEmptyList
import io.github.iltotore.iron.*

import language.experimental.captureChecking

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class PlantJournalUnitTest extends munit.FunSuite:

  private val substrate = Substrate
    .of(List(SubstratePart(SubstrateComponent.Perlite, share = 100)))
    .getOrElse(fail("invalid test substrate"))

  private val plant = Plant(
    id = PlantId("p1"),
    species = Species("Ficus lyrata"),
    maybeNickname = Some(Nickname("Fern")),
    location = Location("Balcony"),
    substrate = substrate,
    status = PlantStatus.Active
  )

  private val care = OperationDetails.Care(
    date = Instant.parse("2026-01-01T00:00:00Z"),
    actions = Set(ActionType.Watered),
    moisture = MoistureLevel.Wet,
    maybeNote = Some(Note("dry"))
  )

  private val repot = OperationDetails
    .Repot(date = Instant.parse("2026-02-01T00:00:00Z"), substrate = substrate, maybeNote = Some(Note("repotted")))

  private val operation = Operation(OperationId("o1"), PlantId("p1"), care)

  private def buildJournal(store: PlantJournalStore^): PlantJournal^ = PlantJournal.make(using store, idGen = () => "id-1")

  test("should list the plants the store holds"):
    val store = buildStore(getPlantsResult = JournalReadResult.Read(Vector(plant)))

    assertEquals(buildJournal(store).getPlants, JournalReadResult.Read(Vector(plant)))

  test("should return the operations recorded for a plant"):
    val store = buildStore(getOperationsResult = JournalReadResult.Read(Vector(operation)))

    assertEquals(buildJournal(store).getOperations(PlantId("p1")), JournalReadResult.Read(Vector(operation)))

  test("should return store corruption while listing operations"):
    val corruptions = NonEmptyList.one(JournalCorruption(JournalRecord.Operation(OperationId("o1")), RuntimeException("corrupt row")))
    val store       = buildStore(getOperationsResult = JournalReadResult.Corrupted(corruptions))

    assertEquals(buildJournal(store).getOperations(PlantId("p1")), JournalReadResult.Corrupted(corruptions))

  test("should return a store read failure while listing operations"):
    val cause = RuntimeException("store down")
    val store = buildStore(getOperationsResult = JournalReadResult.ReadFailed(cause))

    assertEquals(buildJournal(store).getOperations(PlantId("p1")), JournalReadResult.ReadFailed(cause))

  test("should record a care operation without changing the plant substrate"):
    val calls = StoreCalls()
    val store = buildStore(calls)

    assertEquals(buildJournal(store).logOperation(PlantId("p1"), care), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(calls.recordedOperations.get(), Vector(Operation(OperationId("id-1"), PlantId("p1"), care)))
    assertEquals(calls.updatedPlants.get(), Vector.empty)

  test("should update a plant after recording a repot"):
    val calls        = StoreCalls()
    val store        = buildStore(calls)
    val newSubstrate = Substrate.of(List(SubstratePart(SubstrateComponent.Leca, share = 100))).getOrElse(fail("invalid test substrate"))
    val newRepot     = OperationDetails.Repot(date = Instant.parse("2026-02-01T00:00:00Z"), newSubstrate, maybeNote = None)

    assertEquals(buildJournal(store).logOperation(PlantId("p1"), newRepot), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(calls.recordedOperations.get(), Vector(Operation(OperationId("id-1"), PlantId("p1"), newRepot)))
    assertEquals(calls.updatedPlants.get(), Vector(plant.copy(substrate = newSubstrate)))

  test("should leave the plant unchanged when recording a repot fails"):
    val cause = RuntimeException("store down")
    val calls = StoreCalls()
    val store = buildStore(calls, addOperationResult = LogOperationResult.LoggingFailed(cause))

    assertEquals(buildJournal(store).logOperation(PlantId("p1"), repot), LogOperationResult.LoggingFailed(cause))
    assertEquals(calls.updatedPlants.get(), Vector.empty)

  test("should preserve the logged repot when rereading the plant fails"):
    val cause = RuntimeException("store down")
    val calls = StoreCalls()
    val store = buildStore(calls, getPlantResult = JournalReadResult.ReadFailed(cause))

    assertEquals(buildJournal(store).logOperation(PlantId("p1"), repot), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(calls.updatedPlants.get(), Vector.empty)

  test("should update a plant after amending a repot"):
    val existingRepot = Operation(OperationId("o1"), PlantId("p1"), repot)
    val calls         = StoreCalls()
    val getOpResult   = JournalReadResult.Read(existingRepot)
    val editOpResult  = EditOperationResult.Edited(existingRepot)
    val store         = buildStore(calls, getOperationResult = getOpResult, updateOperationResult = editOpResult)

    val _ = buildJournal(store).editOperation(existingRepot.id, repot)

    assertEquals(calls.updatedOperations.get(), Vector(existingRepot.id -> repot))
    assertEquals(calls.updatedPlants.get(), Vector(plant.copy(substrate = substrate)))

  test("should amend care without changing the plant"):
    val calls = StoreCalls()
    val store = buildStore(calls)

    assertEquals(buildJournal(store).editOperation(operation.id, care), EditOperationResult.Edited(operation))
    assertEquals(calls.updatedOperations.get(), Vector(operation.id -> care))
    assertEquals(calls.updatedPlants.get(), Vector.empty)

  test("should reject changing an operation between care and repot"):
    val calls = StoreCalls()
    val store = buildStore(calls)

    assertEquals(buildJournal(store).editOperation(operation.id, repot), EditOperationResult.OperationTypeMismatch)
    assertEquals(calls.updatedOperations.get(), Vector.empty)

  test("should report a missing operation before attempting an edit"):
    val calls = StoreCalls()
    val store = buildStore(calls, getOperationResult = JournalReadResult.RecordMissing)

    assertEquals(buildJournal(store).editOperation(OperationId("nope"), care), EditOperationResult.OperationMissing)
    assertEquals(calls.updatedOperations.get(), Vector.empty)

  test("should report store corruption before attempting an edit"):
    val corruptions = NonEmptyList.one(JournalCorruption(JournalRecord.Operation(OperationId("o1")), RuntimeException("corrupt row")))
    val calls       = StoreCalls()
    val store       = buildStore(calls, getOperationResult = JournalReadResult.Corrupted(corruptions))

    assertEquals(buildJournal(store).editOperation(OperationId("o1"), care), EditOperationResult.Corrupted(corruptions))
    assertEquals(calls.updatedOperations.get(), Vector.empty)

  test("should surface a store read failure before attempting an edit"):
    val cause = RuntimeException("store down")
    val calls = StoreCalls()
    val store = buildStore(calls, getOperationResult = JournalReadResult.ReadFailed(cause))

    assertEquals(buildJournal(store).editOperation(OperationId("o1"), care), EditOperationResult.EditFailed(cause))
    assertEquals(calls.updatedOperations.get(), Vector.empty)

  test("should surface an edit failure from the store"):
    val cause = RuntimeException("store down")
    val store = buildStore(updateOperationResult = EditOperationResult.EditFailed(cause))

    assertEquals(buildJournal(store).editOperation(operation.id, care), EditOperationResult.EditFailed(cause))

  final private case class StoreCalls(
      recordedOperations: AtomicReference[Vector[Operation]] = new AtomicReference(Vector.empty),
      updatedOperations: AtomicReference[Vector[(OperationId, OperationDetails)]] = new AtomicReference(Vector.empty),
      updatedPlants: AtomicReference[Vector[Plant]] = new AtomicReference(Vector.empty)
  )

  private def buildStore(
      calls: StoreCalls = StoreCalls(),
      getPlantResult: JournalReadResult[Plant] = JournalReadResult.Read(plant),
      getPlantsResult: JournalReadResult[Vector[Plant]] = JournalReadResult.Read(Vector.empty),
      getOperationsResult: JournalReadResult[Vector[Operation]] = JournalReadResult.Read(Vector.empty),
      getOperationResult: JournalReadResult[Operation] = JournalReadResult.Read(operation),
      addOperationResult: LogOperationResult = LogOperationResult.Logged(OperationId("id-1")),
      updateOperationResult: EditOperationResult = EditOperationResult.Edited(operation)
  ): PlantJournalStore^ = new PlantJournalStore:
    override def getPlant(id: PlantId): JournalReadResult[Plant]                       = getPlantResult
    override def getPlants: JournalReadResult[Vector[Plant]]                           = getPlantsResult
    override def getOperations(plantId: PlantId): JournalReadResult[Vector[Operation]] = getOperationsResult
    override def getOperation(id: OperationId): JournalReadResult[Operation]           = getOperationResult
    override def addOperation(operation: Operation): LogOperationResult                =
      calls.recordedOperations.updateAndGet(_ :+ operation).pipe(_ => addOperationResult)
    override def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult =
      calls.updatedOperations.updateAndGet(_ :+ (id -> details)).pipe(_ => updateOperationResult)
    override def updatePlant(plant: Plant): Unit =
      val _ = calls.updatedPlants.updateAndGet(_ :+ plant)
