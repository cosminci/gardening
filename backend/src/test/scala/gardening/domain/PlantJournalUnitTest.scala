package gardening.domain

import cats.data.NonEmptyList
import cats.syntax.either.*
import cats.syntax.option.*
import io.github.iltotore.iron.*

import language.experimental.captureChecking

import java.time.Instant
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.CountDownLatch
import scala.util.chaining.scalaUtilChainingOps

class PlantJournalUnitTest extends munit.FunSuite:

  private val date = Instant.parse("2026-01-01T00:00:00Z")

  private val substrate = Substrate
    .of(List(SubstratePart(SubstrateComponent.Perlite, share = 100)))
    .getOrElse(fail("invalid test substrate"))

  private val plant = Plant(
    id = PlantId("p1"),
    details = PlantDetails(
      species = Species("Ficus lyrata"),
      maybeNickname = Nickname("Fern").some,
      location = Location("Balcony"),
      substrate = substrate,
      status = PlantStatus.Active
    )
  )

  private val care = OperationDetails.Care(
    actions = Set(ActionType.Watered),
    moisture = MoistureLevel.Wet,
    maybeNote = Note("dry").some
  )

  private val repot = OperationDetails.Repot(substrate, maybeNote = Note("repotted").some)

  private val operation = Operation(OperationId("o1"), PlantId("p1"), date, care)

  private def buildJournal(
      store: PlantJournalStore^,
      idGen: IdGenerator^ = () => "id-1",
      clock: Clock^ = () => date
  ): PlantJournal^ =
    PlantJournal.make(using store, idGen, clock)

  test("should preserve every store outcome when reading the journal"):
    val corruptions = NonEmptyList.one(JournalCorruption(JournalRecord.Operation(OperationId("o1")), RuntimeException("corrupt row")))
    val readFailure = RuntimeException("store down")

    assertEquals(
      buildJournal(StoreStub(getPlantsResult = Vector(plant).asRight)).getPlants,
      Vector(plant).asRight
    )
    assertEquals(
      buildJournal(StoreStub(getOperationsResult = Vector(operation).asRight)).getOperations(PlantId("p1")),
      Vector(operation).asRight
    )
    assertEquals(
      buildJournal(StoreStub(getOperationsResult = JournalReadFailure.Corrupted(corruptions).asLeft)).getOperations(PlantId("p1")),
      JournalReadFailure.Corrupted(corruptions).asLeft
    )
    assertEquals(
      buildJournal(StoreStub(getOperationsResult = JournalReadFailure.ReadFailed(readFailure).asLeft)).getOperations(PlantId("p1")),
      JournalReadFailure.ReadFailed(readFailure).asLeft
    )

  test("should assign the backend timestamp when recording care"):
    val store = StoreStub()

    assertEquals(buildJournal(store).logOperation(PlantId("p1"), care), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(store.recordedOperations.get(), Vector(Operation(OperationId("id-1"), PlantId("p1"), date, care)))
    assertEquals(store.updatedPlants.get(), Vector.empty)

  test("should update a plant after recording the latest repot"):
    val newSubstrate = Substrate.of(List(SubstratePart(SubstrateComponent.Leca, share = 100))).getOrElse(fail("invalid test substrate"))
    val newRepot     = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val store        = StoreStub(getOperationsResult = JournalReadFailure.ReadFailed(RuntimeException("must not read history")).asLeft)

    assertEquals(buildJournal(store).logOperation(PlantId("p1"), newRepot), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(store.recordedOperations.get(), Vector(Operation(OperationId("id-1"), PlantId("p1"), date, newRepot)))
    assertEquals(store.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should finish concurrent repot logs in timestamp order"):
    val firstSubstrate  = Substrate.of(List(SubstratePart(SubstrateComponent.Leca, share = 100))).getOrElse(fail("invalid test substrate"))
    val secondSubstrate = Substrate.of(List(SubstratePart(SubstrateComponent.Sand3to5, share = 100))).getOrElse(fail("invalid test substrate"))
    val firstRepot      = OperationDetails.Repot(firstSubstrate, maybeNote = none)
    val secondRepot     = OperationDetails.Repot(secondSubstrate, maybeNote = none)
    val firstClockCall  = CountDownLatch(1)
    val releaseFirst    = CountDownLatch(1)
    val secondClockCall = CountDownLatch(1)
    val clockCalls      = AtomicInteger()
    val clock           = new Clock:
      override def now(): Instant =
        clockCalls.incrementAndGet() match
          case 1 =>
            firstClockCall.countDown()
            releaseFirst.await()
            date
          case _ =>
            secondClockCall.countDown()
            date.plusNanos(1)
    val store   = StoreStub()
    val journal = buildJournal(store, clock = clock)

    val firstThread = Thread.ofVirtual().start: () =>
      val _ = journal.logOperation(PlantId("p1"), firstRepot)
    assert(firstClockCall.await(1, java.util.concurrent.TimeUnit.SECONDS))
    val secondThread = Thread.ofVirtual().start: () =>
      val _ = journal.logOperation(PlantId("p1"), secondRepot)
    val overtook = secondClockCall.await(100, MILLISECONDS)
    releaseFirst.countDown()
    firstThread.join()
    secondThread.join()

    assertEquals(overtook, false)
    assertEquals(store.updatedPlants.get().map(_.details.substrate), Vector(firstSubstrate, secondSubstrate))

  test("should remove a recorded repot whenever its plant cannot reflect it"):
    val readFailure   = RuntimeException("read failed")
    val updateFailure = RuntimeException("update failed")
    val missingPlant  = StoreStub(getPlantResult = JournalReadFailure.RecordMissing.asLeft)
    val unreadable    = StoreStub(getPlantResult = JournalReadFailure.ReadFailed(readFailure).asLeft)
    val notUpdated    = StoreStub(updatePlantResult = updateFailure.asLeft)

    buildJournal(missingPlant).logOperation(PlantId("p1"), repot) match
      case LogOperationResult.LoggingFailed(reason) => assertEquals(reason.getMessage, "cannot read plant after repot")
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(missingPlant.removedOperations.get(), Vector(OperationId("id-1")))
    assertEquals(
      buildJournal(unreadable).logOperation(PlantId("p1"), repot),
      LogOperationResult.LoggingFailed(readFailure)
    )
    assertEquals(unreadable.removedOperations.get(), Vector(OperationId("id-1")))
    assertEquals(
      buildJournal(notUpdated).logOperation(PlantId("p1"), repot),
      LogOperationResult.LoggingFailed(updateFailure)
    )
    assertEquals(notUpdated.removedOperations.get(), Vector(OperationId("id-1")))

  test("should report both failures when removing a recorded repot also fails"):
    val updateFailure = RuntimeException("update failed")
    val removeFailure = RuntimeException("remove failed")
    val store         = StoreStub(updatePlantResult = updateFailure.asLeft, removeOperationResult = removeFailure.asLeft)

    buildJournal(store).logOperation(PlantId("p1"), repot) match
      case LogOperationResult.LoggingFailed(reason) =>
        assertEquals(reason.getCause, updateFailure)
        assertEquals(reason.getSuppressed.toList, List(removeFailure))
      case other => fail(s"expected LoggingFailed, got $other")

  test("should leave the plant unchanged when recording a repot fails"):
    val cause = RuntimeException("store down")
    val store = StoreStub(addOperationResult = LogOperationResult.LoggingFailed(cause))

    assertEquals(buildJournal(store).logOperation(PlantId("p1"), repot), LogOperationResult.LoggingFailed(cause))
    assertEquals(store.updatedPlants.get(), Vector.empty)
    assertEquals(store.removedOperations.get(), Vector.empty)

  test("should update a plant after amending the latest repot"):
    val existingRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val existingCare  = Operation(OperationId("o2"), PlantId("p1"), date.plusNanos(1), care)
    val newSubstrate  = Substrate.of(List(SubstratePart(SubstrateComponent.Leca, share = 100))).getOrElse(fail("invalid test substrate"))
    val amended       = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val editResult    = EditOperationResult.Edited(existingRepot.copy(details = amended))
    val store         = StoreStub(
      getOperationResult = existingRepot.asRight,
      getOperationsResult = Vector(existingRepot, existingCare).asRight,
      updateOperationResult = editResult
    )

    assertEquals(buildJournal(store).editOperation(existingRepot.id, amended), editResult)
    assertEquals(store.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should amend an older repot without changing the plant"):
    val olderRepot = Operation(OperationId("older"), PlantId("p1"), date, repot)
    val newerRepot = Operation(OperationId("newer"), PlantId("p1"), date.plusNanos(1), repot)
    val amended    = OperationDetails.Repot(substrate, maybeNote = none)
    val editResult = EditOperationResult.Edited(olderRepot.copy(details = amended))
    val store      = StoreStub(
      getOperationResult = olderRepot.asRight,
      getOperationsResult = Vector(olderRepot, newerRepot).asRight,
      updateOperationResult = editResult
    )

    assertEquals(buildJournal(store).editOperation(olderRepot.id, amended), editResult)
    assertEquals(store.updatedPlants.get(), Vector.empty)
    assertEquals(store.restoredOperations.get(), Vector.empty)

  test("should restore an amended repot whenever plant synchronization cannot complete"):
    val existingRepot    = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val historyFailure   = RuntimeException("history unavailable")
    val plantFailure     = RuntimeException("plant unavailable")
    val updateFailure    = RuntimeException("plant update failed")
    val firstCorruption  = RuntimeException("first corruption")
    val secondCorruption = RuntimeException("second corruption")
    val corruptions      = NonEmptyList.of(
      JournalCorruption(JournalRecord.Operation(OperationId("o1")), firstCorruption),
      JournalCorruption(JournalRecord.Operation(OperationId("o2")), secondCorruption)
    )
    val unreadableHistory = StoreStub(
      getOperationResult = existingRepot.asRight,
      getOperationsResult = JournalReadFailure.ReadFailed(historyFailure).asLeft,
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val corruptedHistory = StoreStub(
      getOperationResult = existingRepot.asRight,
      getOperationsResult = JournalReadFailure.Corrupted(corruptions).asLeft,
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val unreadablePlant = StoreStub(
      getOperationResult = existingRepot.asRight,
      getOperationsResult = Vector(existingRepot).asRight,
      getPlantResult = JournalReadFailure.ReadFailed(plantFailure).asLeft,
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val plantNotUpdated = StoreStub(
      getOperationResult = existingRepot.asRight,
      getOperationsResult = Vector(existingRepot).asRight,
      updateOperationResult = EditOperationResult.Edited(existingRepot),
      updatePlantResult = updateFailure.asLeft
    )

    assertEquals(
      buildJournal(unreadableHistory).editOperation(existingRepot.id, repot),
      EditOperationResult.EditFailed(historyFailure)
    )
    buildJournal(corruptedHistory).editOperation(existingRepot.id, repot) match
      case EditOperationResult.EditFailed(reason) =>
        assertEquals(reason.getCause, firstCorruption)
        assertEquals(reason.getSuppressed.toList, List(secondCorruption))
      case other => fail(s"expected EditFailed, got $other")
    assertEquals(
      buildJournal(unreadablePlant).editOperation(existingRepot.id, repot),
      EditOperationResult.EditFailed(plantFailure)
    )
    assertEquals(
      buildJournal(plantNotUpdated).editOperation(existingRepot.id, repot),
      EditOperationResult.EditFailed(updateFailure)
    )
    List(unreadableHistory, corruptedHistory, unreadablePlant, plantNotUpdated).foreach: store =>
      assertEquals(store.updatedOperations.get(), Vector(existingRepot.id -> repot))
      assertEquals(store.restoredOperations.get(), Vector(existingRepot))

  test("should surface a failed latest repot edit without updating the plant"):
    val existingRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val cause         = RuntimeException("store down")
    val store         = StoreStub(
      getOperationResult = existingRepot.asRight,
      getOperationsResult = Vector(existingRepot).asRight,
      updateOperationResult = EditOperationResult.EditFailed(cause)
    )

    assertEquals(buildJournal(store).editOperation(existingRepot.id, repot), EditOperationResult.EditFailed(cause))
    assertEquals(store.updatedPlants.get(), Vector.empty)

  test("should report both failures when restoring an amended repot also fails"):
    val existingRepot  = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val updateFailure  = RuntimeException("update failed")
    val restoreFailure = RuntimeException("restore failed")
    val store          = StoreStub(
      getOperationResult = existingRepot.asRight,
      getOperationsResult = Vector(existingRepot).asRight,
      updateOperationResult = EditOperationResult.Edited(existingRepot),
      updatePlantResult = updateFailure.asLeft,
      restoreOperationResult = restoreFailure.asLeft
    )

    buildJournal(store).editOperation(existingRepot.id, repot) match
      case EditOperationResult.EditFailed(reason) =>
        assertEquals(reason.getCause, updateFailure)
        assertEquals(reason.getSuppressed.toList, List(restoreFailure))
      case other => fail(s"expected EditFailed, got $other")

  test("should amend care without changing the plant"):
    val store = StoreStub()

    assertEquals(buildJournal(store).editOperation(operation.id, care), EditOperationResult.Edited(operation))
    assertEquals(store.updatedOperations.get(), Vector(operation.id -> care))
    assertEquals(store.updatedPlants.get(), Vector.empty)

  test("should reject invalid edit requests before writing"):
    val corruptions  = NonEmptyList.one(JournalCorruption(JournalRecord.Operation(OperationId("o1")), RuntimeException("corrupt row")))
    val readFailure  = RuntimeException("store down")
    val typeMismatch = StoreStub()
    val missing      = StoreStub(getOperationResult = JournalReadFailure.RecordMissing.asLeft)
    val corrupted    = StoreStub(getOperationResult = JournalReadFailure.Corrupted(corruptions).asLeft)
    val unreadable   = StoreStub(getOperationResult = JournalReadFailure.ReadFailed(readFailure).asLeft)

    assertEquals(buildJournal(typeMismatch).editOperation(operation.id, repot), EditOperationResult.OperationTypeMismatch)
    assertEquals(buildJournal(missing).editOperation(OperationId("nope"), care), EditOperationResult.OperationMissing)
    assertEquals(buildJournal(corrupted).editOperation(operation.id, care), EditOperationResult.Corrupted(corruptions))
    assertEquals(buildJournal(unreadable).editOperation(operation.id, care), EditOperationResult.EditFailed(readFailure))
    List(typeMismatch, missing, corrupted, unreadable).foreach: store =>
      assertEquals(store.updatedOperations.get(), Vector.empty)

  test("should surface an edit failure from the store"):
    val cause = RuntimeException("store down")
    val store = StoreStub(updateOperationResult = EditOperationResult.EditFailed(cause))

    assertEquals(buildJournal(store).editOperation(operation.id, care), EditOperationResult.EditFailed(cause))

  final private case class StoreStub(
      getPlantResult: Either[JournalReadFailure, Plant] = plant.asRight,
      getPlantsResult: Either[JournalReadFailure, Vector[Plant]] = Vector.empty.asRight,
      getOperationsResult: Either[JournalReadFailure, Vector[Operation]] = Vector.empty.asRight,
      getOperationResult: Either[JournalReadFailure, Operation] = operation.asRight,
      addOperationResult: LogOperationResult = LogOperationResult.Logged(OperationId("id-1")),
      updateOperationResult: EditOperationResult = EditOperationResult.Edited(operation),
      removeOperationResult: Either[Throwable, Unit] = ().asRight,
      restoreOperationResult: Either[Throwable, Unit] = ().asRight,
      updatePlantResult: Either[Throwable, Unit] = ().asRight
  ) extends PlantJournalStore:
    val recordedOperations: AtomicReference[Vector[Operation]]                      = new AtomicReference(Vector.empty)
    val updatedOperations: AtomicReference[Vector[(OperationId, OperationDetails)]] = new AtomicReference(Vector.empty)
    val removedOperations: AtomicReference[Vector[OperationId]]                     = new AtomicReference(Vector.empty)
    val restoredOperations: AtomicReference[Vector[Operation]]                      = new AtomicReference(Vector.empty)
    val updatedPlants: AtomicReference[Vector[Plant]]                               = new AtomicReference(Vector.empty)

    override def getPlant(id: PlantId): Either[JournalReadFailure, Plant]                       = getPlantResult
    override def getPlants: Either[JournalReadFailure, Vector[Plant]]                           = getPlantsResult
    override def getOperations(plantId: PlantId): Either[JournalReadFailure, Vector[Operation]] = getOperationsResult
    override def getOperation(id: OperationId): Either[JournalReadFailure, Operation]           = getOperationResult
    override def addOperation(operation: Operation): LogOperationResult                         =
      recordedOperations.updateAndGet(_ :+ operation).pipe(_ => addOperationResult)
    override def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult =
      updatedOperations.updateAndGet(_ :+ (id -> details)).pipe(_ => updateOperationResult)
    override def removeOperation(id: OperationId): Either[Throwable, Unit] =
      removedOperations.updateAndGet(_ :+ id).pipe(_ => removeOperationResult)
    override def restoreOperation(operation: Operation): Either[Throwable, Unit] =
      restoredOperations.updateAndGet(_ :+ operation).pipe(_ => restoreOperationResult)
    override def updatePlant(plant: Plant): Either[Throwable, Unit] =
      updatedPlants.updateAndGet(_ :+ plant).pipe(_ => updatePlantResult)
