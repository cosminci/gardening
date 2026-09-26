package gardening.domain.operations

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.{GetPesticideResult, PesticideStore, UpdatePesticideResult}
import gardening.domain.plants.*
import gardening.domain.substrate.{GetSubstrateComponentResult, SubstrateStore, UpdateSubstrateComponentResult}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine

import language.experimental.captureChecking

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.CountDownLatch
import scala.util.chaining.scalaUtilChainingOps

class OperationsRepotLogComponentTest extends munit.FunSuite with TestImplicits:

  private val date       = Instant.parse("2026-01-01T00:00:00Z")
  private val perliteId  = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val sand3to5Id = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000005"))
  private val lecaId     = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000007"))

  private val substrate = Substrate
    .of(List(SubstratePart(perliteId, share = 100)))
    .getOrElse(fail("invalid test substrate"))

  private val seededComponents = Vector(perliteId, sand3to5Id, lecaId)
    .map(id => SubstrateComponent(id, SubstrateComponentData(SubstrateComponentName(id.value.toString), none), SubstrateComponentStatus.Active))

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

  private val repot = OperationDetails.Repot(substrate, maybeNote = Note("repotted").some)

  test("should update a plant after recording the latest repot"):
    val newSubstrate    = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val newRepot        = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val loggedOperation = Operation(OperationId("id-1"), PlantId("p1"), date, newRepot)
    val refs            = Refs()
    val operations      =
      buildOperations(refs, getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(loggedOperation), hasNextPage = false)))

    assertEquals(operations.logOperation(PlantId("p1"), date, newRepot), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(refs.recordedOperations.get(), Vector(loggedOperation))
    assertEquals(refs.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should leave the plant unchanged when a recorded repot loses the ordering tie-break"):
    val newerRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val logged     = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val refs       = Refs()

    val operations = buildOperations(
      refs,
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(newerRepot, logged), hasNextPage = false)),
      addOperationResult = LogOperationResult.Logged(logged.id),
      nextId = () => logged.id.value
    )
    assertEquals(operations.logOperation(plant.id, date, repot), LogOperationResult.Logged(logged.id))
    assertEquals(refs.updatedPlants.get(), Vector.empty)
    assertEquals(refs.removedOperations.get(), Vector.empty)

  test("should preserve the current substrate when logging a historical repot"):
    val newerSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val olderSubstrate = Substrate.of(List(SubstratePart(sand3to5Id, share = 100))).getOrElse(fail("invalid test substrate"))
    val newerRepot     = Operation(OperationId("newer"), plant.id, date, OperationDetails.Repot(newerSubstrate, none))
    val olderRepot     = OperationDetails.Repot(olderSubstrate, none)
    val refs           = Refs()

    val operations = buildOperations(refs, getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(newerRepot), hasNextPage = false)))
    assertEquals(operations.logOperation(plant.id, date.minusSeconds(60), olderRepot), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(refs.updatedPlants.get(), Vector.empty)

  test("should finish concurrent repot logs in timestamp order"):
    val firstSubstrate  = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val secondSubstrate = Substrate.of(List(SubstratePart(sand3to5Id, share = 100))).getOrElse(fail("invalid test substrate"))
    val firstRepot      = OperationDetails.Repot(firstSubstrate, maybeNote = none)
    val secondRepot     = OperationDetails.Repot(secondSubstrate, maybeNote = none)
    val firstOperation  = Operation(OperationId("id-1"), PlantId("p1"), date, firstRepot)
    val secondOperation = Operation(OperationId("id-2"), PlantId("p1"), date.plusNanos(1), secondRepot)
    val firstIdCall     = CountDownLatch(1)
    val releaseFirst    = CountDownLatch(1)
    val secondIdCall    = CountDownLatch(1)
    val idCalls         = AtomicInteger()
    val idGen           = new IdGenerator:
      override def nextId(): String =
        idCalls.incrementAndGet() match
          case 1 =>
            firstIdCall.countDown()
            releaseFirst.await()
            "id-1"
          case _ =>
            secondIdCall.countDown()
            "id-2"
    val refs       = Refs()
    val operations = buildOperations(
      refs,
      nextId = () => idGen.nextId(),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(firstOperation), hasNextPage = false)),
      nextOperationsResult = GetOperationsResult.Read(OperationPage(Vector(secondOperation, firstOperation), hasNextPage = false)).some
    )

    val firstThread = Thread.ofVirtual().start: () =>
      val _ = operations.logOperation(PlantId("p1"), date, firstRepot)
    assert(firstIdCall.await(1, java.util.concurrent.TimeUnit.SECONDS))
    val secondThread = Thread.ofVirtual().start: () =>
      val _ = operations.logOperation(PlantId("p1"), date.plusNanos(1), secondRepot)
    val overtook = secondIdCall.await(100, MILLISECONDS)
    releaseFirst.countDown()
    firstThread.join()
    secondThread.join()

    assertEquals(overtook, false)
    assertEquals(refs.updatedPlants.get().map(_.details.substrate), Vector(firstSubstrate, secondSubstrate))

  test("should remove a recorded repot whenever its plant cannot reflect it"):
    val historyFailure    = RuntimeException("history failed")
    val readFailure       = RuntimeException("read failed")
    val updateFailure     = RuntimeException("update failed")
    val loggedRepot       = Operation(OperationId("id-1"), plant.id, date, repot)
    val selfOnlyPage      = GetOperationsResult.Read(OperationPage(Vector(loggedRepot), hasNextPage = false))
    val unreadableHistory = Refs()
    val missingPlant      = Refs()
    val unreadable        = Refs()
    val notUpdated        = Refs()

    val historyResult =
      buildOperations(unreadableHistory, getOperationsResult = GetOperationsResult.ReadFailed(historyFailure)).logOperation(plant.id, date, repot)
    val missingResult = buildOperations(
      missingPlant,
      getOperationsResult = selfOnlyPage,
      getPlantResultAfterLog = GetPlantResult.RecordMissing.some
    ).logOperation(plant.id, date, repot)
    val readResult = buildOperations(
      unreadable,
      getOperationsResult = selfOnlyPage,
      getPlantResultAfterLog = GetPlantResult.ReadFailed(readFailure).some
    ).logOperation(plant.id, date, repot)
    val updateResult = buildOperations(
      notUpdated,
      getOperationsResult = selfOnlyPage,
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure)
    ).logOperation(plant.id, date, repot)

    assertEquals(historyResult, LogOperationResult.LoggingFailed(historyFailure))
    missingResult match
      case LogOperationResult.LoggingFailed(reason) => assertEquals(reason.getMessage, "cannot read plant after repot")
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(missingPlant.removedOperations.get(), Vector(OperationId("id-1")))
    assertEquals(readResult, LogOperationResult.LoggingFailed(readFailure))
    assertEquals(unreadable.removedOperations.get(), Vector(OperationId("id-1")))
    assertEquals(updateResult, LogOperationResult.LoggingFailed(updateFailure))
    assertEquals(notUpdated.removedOperations.get(), Vector(OperationId("id-1")))
    assertEquals(unreadableHistory.removedOperations.get(), Vector(OperationId("id-1")))

  test("should report both failures when removing a recorded repot also fails"):
    val updateFailure = RuntimeException("update failed")
    val removeFailure = RuntimeException("remove failed")
    val loggedRepot   = Operation(OperationId("id-1"), PlantId("p1"), date, repot)
    val operations    = buildOperations(
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(loggedRepot), hasNextPage = false)),
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure),
      removeOperationResult = OperationCompensationResult.CompensationFailed(removeFailure)
    )

    operations.logOperation(PlantId("p1"), date, repot) match
      case LogOperationResult.LoggingFailed(reason) =>
        assertEquals(reason.getCause, updateFailure)
        assertEquals(reason.getSuppressed.toList, List(removeFailure))
      case other => fail(s"expected LoggingFailed, got $other")

  test("should leave the plant unchanged when recording a repot fails"):
    val cause = RuntimeException("store down")
    val refs  = Refs()

    assertEquals(
      buildOperations(refs, addOperationResult = LogOperationResult.LoggingFailed(cause)).logOperation(PlantId("p1"), date, repot),
      LogOperationResult.LoggingFailed(cause)
    )
    assertEquals(refs.updatedPlants.get(), Vector.empty)
    assertEquals(refs.removedOperations.get(), Vector.empty)

  test("should delete a non-latest repot"):
    val olderRepot = Operation(OperationId("o1"), plant.id, date, repot)
    val newerRepot = Operation(OperationId("o2"), plant.id, date.plusSeconds(60), repot)
    val refs       = Refs()
    val operations = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(olderRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(newerRepot), hasNextPage = false))
    )

    assertEquals(operations.deleteOperation(olderRepot.id), DeleteOperationResult.Deleted)
    assertEquals(refs.removedOperations.get(), Vector(olderRepot.id))

  test("should reject deleting a plant's only repot"):
    val onlyRepot = Operation(OperationId("o1"), plant.id, date, repot)
    val refs      = Refs()
    val result    = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(onlyRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(onlyRepot), hasNextPage = false))
    ).deleteOperation(onlyRepot.id)

    assertEquals(result, DeleteOperationResult.CannotDeleteLatestRepot)
    assertEquals(refs.removedOperations.get(), Vector.empty)

  test("should reject deleting the latest repot on a same-instant tie-break"):
    val newerRepot = Operation(OperationId("o2"), plant.id, date, repot)
    val olderRepot = Operation(OperationId("o1"), plant.id, date, repot)
    val refs       = Refs()
    val result     = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(newerRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(newerRepot, olderRepot), hasNextPage = false))
    ).deleteOperation(newerRepot.id)

    assertEquals(result, DeleteOperationResult.CannotDeleteLatestRepot)
    assertEquals(refs.removedOperations.get(), Vector.empty)

  test("should allow deleting a repot when its plant's history reports no repot at all"):
    val orphanRepot = Operation(OperationId("o1"), plant.id, date, repot)
    val refs        = Refs()
    val operations  = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(orphanRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false))
    )

    assertEquals(operations.deleteOperation(orphanRepot.id), DeleteOperationResult.Deleted)
    assertEquals(refs.removedOperations.get(), Vector(orphanRepot.id))

  final private case class Refs():
    val requestedOperationWindows: AtomicReference[Vector[(PlantId, OperationWindow)]] = AtomicReference(Vector.empty)
    val requestedDateRanges: AtomicReference[Vector[PlantId]]                          = AtomicReference(Vector.empty)
    val recordedOperations: AtomicReference[Vector[Operation]]                         = AtomicReference(Vector.empty)
    val updatedOperations: AtomicReference[Vector[(OperationId, OperationDetails)]]    = AtomicReference(Vector.empty)
    val removedOperations: AtomicReference[Vector[OperationId]]                        = AtomicReference(Vector.empty)
    val restoredOperations: AtomicReference[Vector[Operation]]                         = AtomicReference(Vector.empty)
    val updatedPlants: AtomicReference[Vector[Plant]]                                  = AtomicReference(Vector.empty)

  private def buildOperations(
      refs: Refs = Refs(),
      getPlantResult: GetPlantResult = GetPlantResult.Read(plant),
      getPlantResultAfterLog: Option[GetPlantResult] = none,
      getOperationsResult: GetOperationsResult = GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false)),
      nextOperationsResult: Option[GetOperationsResult] = none,
      getOperationResult: GetOperationResult = GetOperationResult.Read(Operation(OperationId("o1"), PlantId("p1"), date, repot)),
      addOperationResult: LogOperationResult = LogOperationResult.Logged(OperationId("id-1")),
      updateOperationResult: EditOperationResult = EditOperationResult.Edited(Operation(OperationId("o1"), PlantId("p1"), date, repot)),
      removeOperationResult: OperationCompensationResult = OperationCompensationResult.Compensated,
      restoreOperationResult: OperationCompensationResult = OperationCompensationResult.Compensated,
      updatePlantResult: UpdatePlantResult = UpdatePlantResult.Updated,
      componentReadResult: CatalogReadResult[SubstrateComponent] = CatalogReadResult.Read(seededComponents),
      nextId: () => String = () => "id-1"
  ) =
    val plantReads     = AtomicInteger(0)
    val operationReads = AtomicInteger(0)
    val plantStore     = new PlantStore:
      override def addPlant(plant: Plant): AddPlantResult          = fail("operations must not add plants")
      override def getPlants(status: PlantStatus): GetPlantsResult = fail("operations must not list plants")
      override def getArchivedCount: ArchivedCountResult           = fail("operations must not count plants")
      override def getPlant(plant: PlantId): GetPlantResult        =
        if plantReads.getAndIncrement().equals(0) then getPlantResult
        else getPlantResultAfterLog.getOrElse(getPlantResult)
      override def updatePlant(plant: Plant): UpdatePlantResult =
        refs.updatedPlants.updateAndGet(_ :+ plant).pipe(_ => updatePlantResult)
      override def addPhoto(photo: PlantPhoto): AddPhotoResult                     = fail("operations must not add photos")
      override def removePhoto(photo: PhotoId): RemovePhotoResult                  = fail("operations must not remove photos")
      override def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult = fail("operations must not list photos")
    val store = new OperationStore:
      override def getOperations(plant: PlantId, window: OperationWindow): GetOperationsResult =
        refs.requestedOperationWindows.updateAndGet(_ :+ (plant -> window))
        if operationReads.getAndIncrement().equals(0) then getOperationsResult
        else nextOperationsResult.getOrElse(getOperationsResult)
      override def getOperationDateRange(plant: PlantId): GetOperationDateRangeResult =
        refs.requestedDateRanges.updateAndGet(_ :+ plant).pipe(_ => GetOperationDateRangeResult.Read(OperationDateRange.Empty))
      override def getOperation(operation: OperationId): GetOperationResult = getOperationResult
      override def addOperation(operation: Operation): LogOperationResult   =
        refs.recordedOperations.updateAndGet(_ :+ operation).pipe(_ => addOperationResult)
      override def updateOperation(operation: OperationId, details: OperationDetails): EditOperationResult =
        refs.updatedOperations.updateAndGet(_ :+ (operation -> details)).pipe(_ => updateOperationResult)
      override def removeOperation(operation: OperationId): OperationCompensationResult =
        refs.removedOperations.updateAndGet(_ :+ operation).pipe(_ => removeOperationResult)
      override def restoreOperation(operation: Operation): OperationCompensationResult =
        refs.restoredOperations.updateAndGet(_ :+ operation).pipe(_ => restoreOperationResult)
    val substrateStore = new SubstrateStore:
      override def getSubstrateComponents: CatalogReadResult[SubstrateComponent]                = componentReadResult
      override def getSubstrateComponent(id: SubstrateComponentId): GetSubstrateComponentResult =
        fail("operations must not read a single substrate component")
      override def addSubstrateComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent] =
        fail("operations must not write substrate components")
      override def updateSubstrateComponent(component: SubstrateComponent): UpdateSubstrateComponentResult =
        fail("operations must not edit substrate components")
      override def getSubstrateMixes: CatalogReadResult[SubstrateMix] =
        fail("operations must not read substrate mixes")
      override def addSubstrateMix(mix: SubstrateMix): CatalogAddResult[SubstrateMix] =
        fail("operations must not write substrate mixes")
      override def deleteSubstrateMix(id: java.util.UUID): CatalogDeleteResult =
        fail("operations must not delete substrate mixes")
    val pesticideStore = new PesticideStore:
      override def getPesticides: CatalogReadResult[Pesticide]                     = CatalogReadResult.Read(Vector.empty)
      override def getPesticide(id: PesticideId): GetPesticideResult               = fail("operations must not read a single pesticide")
      override def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide] =
        fail("operations must not write pesticides")
      override def updatePesticide(pesticide: Pesticide): UpdatePesticideResult =
        fail("operations must not update pesticides")
    Operations.make(using store, plantStore, substrateStore, pesticideStore, () => nextId(), PlantUpdateLock.make)
