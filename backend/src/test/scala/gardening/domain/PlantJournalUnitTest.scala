package gardening.domain

import cats.data.NonEmptyList
import cats.syntax.option.*
import io.github.iltotore.iron.*

import language.experimental.captureChecking

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.CountDownLatch
import scala.util.chaining.scalaUtilChainingOps

class PlantJournalUnitTest extends munit.FunSuite:

  private val date = Instant.parse("2026-01-01T00:00:00Z")

  private val substrate = Substrate
    .of(List(SubstratePart(TestNomenclatureIds.Perlite, share = 100)))
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
    pesticides = Set.empty,
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

  test("should return plants and operation history while preserving read failures"):
    val plantCorruptions     = NonEmptyList.one(JournalCorruption(JournalRecord.Plant(PlantId("p1")), RuntimeException("corrupt plant")))
    val operationCorruptions = NonEmptyList.one(JournalCorruption(JournalRecord.Operation(OperationId("o1")), RuntimeException("corrupt operation")))
    val readFailure          = RuntimeException("store down")

    assertEquals(
      buildJournal(StoreStub(getPlantsResult = GetPlantsResult.Read(Vector(plant)))).getPlants,
      GetPlantsResult.Read(Vector(plant))
    )
    assertEquals(
      buildJournal(StoreStub(getPlantsResult = GetPlantsResult.Corrupted(plantCorruptions))).getPlants,
      GetPlantsResult.Corrupted(plantCorruptions)
    )
    assertEquals(
      buildJournal(StoreStub(getPlantsResult = GetPlantsResult.ReadFailed(readFailure))).getPlants,
      GetPlantsResult.ReadFailed(readFailure)
    )
    assertEquals(
      buildJournal(StoreStub(getOperationsResult = GetOperationsResult.Read(Vector(operation)))).getOperations(PlantId("p1")),
      GetOperationsResult.Read(Vector(operation))
    )
    assertEquals(
      buildJournal(StoreStub(getOperationsResult = GetOperationsResult.Corrupted(operationCorruptions))).getOperations(PlantId("p1")),
      GetOperationsResult.Corrupted(operationCorruptions)
    )
    assertEquals(
      buildJournal(StoreStub(getOperationsResult = GetOperationsResult.ReadFailed(readFailure))).getOperations(PlantId("p1")),
      GetOperationsResult.ReadFailed(readFailure)
    )

  test("should assign catalog identifiers and delegate nomenclature operations"):
    val componentId = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
    val pesticideId = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
    val ids         = Iterator(componentId.value.toString, pesticideId.value.toString)
    val store       = StoreStub()
    val journal     = buildJournal(store, idGen = () => ids.next())
    val component   = SubstrateComponentData(NomenclatureName("Pumice"), none)
    val pesticide   = PesticideData(NomenclatureName("Soap"), PesticideType("Treatment"), none)

    assertEquals(journal.getSubstrateComponents, store.componentReadResult)
    assertEquals(journal.addSubstrateComponent(component), store.componentAddResult)
    assertEquals(journal.editSubstrateComponent(componentId, component), store.componentEditResult)
    assertEquals(journal.getPesticides, store.pesticideReadResult)
    assertEquals(journal.addPesticide(pesticide), store.pesticideAddResult)
    assertEquals(journal.editPesticide(pesticideId, pesticide), store.pesticideEditResult)
    assertEquals(store.addedComponents.get(), Vector(SubstrateComponent(componentId, component)))
    assertEquals(store.editedComponents.get(), Vector(componentId -> component))
    assertEquals(store.addedPesticides.get(), Vector(Pesticide(pesticideId, pesticide)))
    assertEquals(store.editedPesticides.get(), Vector(pesticideId -> pesticide))

  test("should assign the backend timestamp when recording care"):
    val store = StoreStub()

    assertEquals(buildJournal(store).logOperation(PlantId("p1"), care), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(store.recordedOperations.get(), Vector(Operation(OperationId("id-1"), PlantId("p1"), date, care)))
    assertEquals(store.updatedPlants.get(), Vector.empty)

  test("should update a plant after recording the latest repot"):
    val newSubstrate = Substrate.of(List(SubstratePart(TestNomenclatureIds.Leca, share = 100))).getOrElse(fail("invalid test substrate"))
    val newRepot     = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val store        = StoreStub(getOperationsResult = GetOperationsResult.ReadFailed(RuntimeException("must not read history")))

    assertEquals(buildJournal(store).logOperation(PlantId("p1"), newRepot), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(store.recordedOperations.get(), Vector(Operation(OperationId("id-1"), PlantId("p1"), date, newRepot)))
    assertEquals(store.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should finish concurrent repot logs in timestamp order"):
    val firstSubstrate  = Substrate.of(List(SubstratePart(TestNomenclatureIds.Leca, share = 100))).getOrElse(fail("invalid test substrate"))
    val secondSubstrate = Substrate.of(List(SubstratePart(TestNomenclatureIds.Sand3to5, share = 100))).getOrElse(fail("invalid test substrate"))
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
    val readFailure     = RuntimeException("read failed")
    val updateFailure   = RuntimeException("update failed")
    val firstCorruption = RuntimeException("invalid substrate")
    val nextCorruption  = RuntimeException("invalid status")
    val corruptions     = NonEmptyList.of(
      JournalCorruption(JournalRecord.Plant(PlantId("p1")), firstCorruption),
      JournalCorruption(JournalRecord.Plant(PlantId("p1")), nextCorruption)
    )
    val missingPlant = StoreStub(getPlantResult = GetPlantResult.RecordMissing)
    val unreadable   = StoreStub(getPlantResult = GetPlantResult.ReadFailed(readFailure))
    val corrupted    = StoreStub(getPlantResult = GetPlantResult.Corrupted(corruptions))
    val notUpdated   = StoreStub(updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure))

    buildJournal(missingPlant).logOperation(PlantId("p1"), repot) match
      case LogOperationResult.LoggingFailed(reason) => assertEquals(reason.getMessage, "cannot read plant after repot")
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(missingPlant.removedOperations.get(), Vector(OperationId("id-1")))
    assertEquals(
      buildJournal(unreadable).logOperation(PlantId("p1"), repot),
      LogOperationResult.LoggingFailed(readFailure)
    )
    assertEquals(unreadable.removedOperations.get(), Vector(OperationId("id-1")))
    buildJournal(corrupted).logOperation(PlantId("p1"), repot) match
      case LogOperationResult.LoggingFailed(reason) =>
        assertEquals(reason.getCause, firstCorruption)
        assertEquals(reason.getSuppressed.toList, List(nextCorruption))
      case other => fail(s"expected LoggingFailed, got $other")
    assertEquals(corrupted.removedOperations.get(), Vector(OperationId("id-1")))
    assertEquals(
      buildJournal(notUpdated).logOperation(PlantId("p1"), repot),
      LogOperationResult.LoggingFailed(updateFailure)
    )
    assertEquals(notUpdated.removedOperations.get(), Vector(OperationId("id-1")))

  test("should report both failures when removing a recorded repot also fails"):
    val updateFailure = RuntimeException("update failed")
    val removeFailure = RuntimeException("remove failed")
    val store         = StoreStub(
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure),
      removeOperationResult = OperationCompensationResult.CompensationFailed(removeFailure)
    )

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
    val newSubstrate  = Substrate.of(List(SubstratePart(TestNomenclatureIds.Leca, share = 100))).getOrElse(fail("invalid test substrate"))
    val amended       = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val editResult    = EditOperationResult.Edited(existingRepot.copy(details = amended))
    val store         = StoreStub(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(Vector(existingRepot, existingCare)),
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
      getOperationResult = GetOperationResult.Read(olderRepot),
      getOperationsResult = GetOperationsResult.Read(Vector(olderRepot, newerRepot)),
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
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.ReadFailed(historyFailure),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val corruptedHistory = StoreStub(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Corrupted(corruptions),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val unreadablePlant = StoreStub(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(Vector(existingRepot)),
      getPlantResult = GetPlantResult.ReadFailed(plantFailure),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val plantNotUpdated = StoreStub(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(Vector(existingRepot)),
      updateOperationResult = EditOperationResult.Edited(existingRepot),
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure)
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
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(Vector(existingRepot)),
      updateOperationResult = EditOperationResult.EditFailed(cause)
    )

    assertEquals(buildJournal(store).editOperation(existingRepot.id, repot), EditOperationResult.EditFailed(cause))
    assertEquals(store.updatedPlants.get(), Vector.empty)

  test("should report both failures when restoring an amended repot also fails"):
    val existingRepot  = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val updateFailure  = RuntimeException("update failed")
    val restoreFailure = RuntimeException("restore failed")
    val store          = StoreStub(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(Vector(existingRepot)),
      updateOperationResult = EditOperationResult.Edited(existingRepot),
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure),
      restoreOperationResult = OperationCompensationResult.CompensationFailed(restoreFailure)
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
    val missing      = StoreStub(getOperationResult = GetOperationResult.RecordMissing)
    val corrupted    = StoreStub(getOperationResult = GetOperationResult.Corrupted(corruptions))
    val unreadable   = StoreStub(getOperationResult = GetOperationResult.ReadFailed(readFailure))

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
      getPlantResult: GetPlantResult = GetPlantResult.Read(plant),
      getPlantsResult: GetPlantsResult = GetPlantsResult.Read(Vector.empty),
      getOperationsResult: GetOperationsResult = GetOperationsResult.Read(Vector.empty),
      getOperationResult: GetOperationResult = GetOperationResult.Read(operation),
      addOperationResult: LogOperationResult = LogOperationResult.Logged(OperationId("id-1")),
      updateOperationResult: EditOperationResult = EditOperationResult.Edited(operation),
      removeOperationResult: OperationCompensationResult = OperationCompensationResult.Compensated,
      restoreOperationResult: OperationCompensationResult = OperationCompensationResult.Compensated,
      updatePlantResult: UpdatePlantResult = UpdatePlantResult.Updated,
      componentReadResult: CatalogReadResult[SubstrateComponent] = CatalogReadResult.Read(Vector.empty),
      componentAddResult: CatalogAddResult[SubstrateComponent] = CatalogAddResult.Added(
        SubstrateComponent(TestNomenclatureIds.Perlite, SubstrateComponentData(NomenclatureName("Perlite"), none))
      ),
      componentEditResult: CatalogEditResult[SubstrateComponent] = CatalogEditResult.RecordMissing,
      pesticideReadResult: CatalogReadResult[Pesticide] = CatalogReadResult.Read(Vector.empty),
      pesticideAddResult: CatalogAddResult[Pesticide] = CatalogAddResult.Added(
        Pesticide(
          PesticideId(UUID.fromString("20000000-0000-4000-8000-000000000001")),
          PesticideData(NomenclatureName("Neem"), PesticideType("Treatment"), none)
        )
      ),
      pesticideEditResult: CatalogEditResult[Pesticide] = CatalogEditResult.RecordMissing
  ) extends PlantJournalStore:
    val recordedOperations: AtomicReference[Vector[Operation]]                                    = new AtomicReference(Vector.empty)
    val updatedOperations: AtomicReference[Vector[(OperationId, OperationDetails)]]               = new AtomicReference(Vector.empty)
    val removedOperations: AtomicReference[Vector[OperationId]]                                   = new AtomicReference(Vector.empty)
    val restoredOperations: AtomicReference[Vector[Operation]]                                    = new AtomicReference(Vector.empty)
    val updatedPlants: AtomicReference[Vector[Plant]]                                             = new AtomicReference(Vector.empty)
    val addedComponents: AtomicReference[Vector[SubstrateComponent]]                              = new AtomicReference(Vector.empty)
    val editedComponents: AtomicReference[Vector[(SubstrateComponentId, SubstrateComponentData)]] = new AtomicReference(Vector.empty)
    val addedPesticides: AtomicReference[Vector[Pesticide]]                                       = new AtomicReference(Vector.empty)
    val editedPesticides: AtomicReference[Vector[(PesticideId, PesticideData)]]                   = new AtomicReference(Vector.empty)

    override def getPlant(id: PlantId): GetPlantResult                  = getPlantResult
    override def getPlants: GetPlantsResult                             = getPlantsResult
    override def getOperations(plantId: PlantId): GetOperationsResult   = getOperationsResult
    override def getOperation(id: OperationId): GetOperationResult      = getOperationResult
    override def addOperation(operation: Operation): LogOperationResult =
      recordedOperations.updateAndGet(_ :+ operation).pipe(_ => addOperationResult)
    override def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult =
      updatedOperations.updateAndGet(_ :+ (id -> details)).pipe(_ => updateOperationResult)
    override def removeOperation(id: OperationId): OperationCompensationResult =
      removedOperations.updateAndGet(_ :+ id).pipe(_ => removeOperationResult)
    override def restoreOperation(operation: Operation): OperationCompensationResult =
      restoredOperations.updateAndGet(_ :+ operation).pipe(_ => restoreOperationResult)
    override def updatePlant(plant: Plant): UpdatePlantResult =
      updatedPlants.updateAndGet(_ :+ plant).pipe(_ => updatePlantResult)
    override def getSubstrateComponents: CatalogReadResult[SubstrateComponent]                              = componentReadResult
    override def addSubstrateComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent] =
      addedComponents.updateAndGet(_ :+ component).pipe(_ => componentAddResult)
    override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
      editedComponents.updateAndGet(_ :+ (id -> data)).pipe(_ => componentEditResult)
    override def getPesticides: CatalogReadResult[Pesticide]                     = pesticideReadResult
    override def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide] =
      addedPesticides.updateAndGet(_ :+ pesticide).pipe(_ => pesticideAddResult)
    override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
      editedPesticides.updateAndGet(_ :+ (id -> data)).pipe(_ => pesticideEditResult)
