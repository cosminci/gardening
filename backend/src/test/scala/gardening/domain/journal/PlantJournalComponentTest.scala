package gardening.domain.journal

import cats.syntax.option.*
import gardening.domain.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine

import language.experimental.captureChecking

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.CountDownLatch
import scala.util.chaining.scalaUtilChainingOps

class PlantJournalComponentTest extends munit.FunSuite:

  private val date       = Instant.parse("2026-01-01T00:00:00Z")
  private val perliteId  = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val pineBarkId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000004"))
  private val sand3to5Id = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000005"))
  private val lecaId     = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000007"))
  private val vertabId   = PesticideId(UUID.fromString("00000000-0000-4000-8001-000000000003"))
  private val neemOilId  = PesticideId(UUID.fromString("00000000-0000-4000-8001-000000000007"))

  private val substrate = Substrate
    .of(List(SubstratePart(perliteId, share = 100)))
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

  private val operation        = Operation(OperationId("o1"), PlantId("p1"), date, care)
  private val firstPage        = OperationWindow(offset = 0, size = 3)
  private val seededComponents = Vector(perliteId, pineBarkId, sand3to5Id, lecaId)
    .map(id => SubstrateComponent(id, SubstrateComponentData(NomenclatureName(id.value.toString), none)))
  private val addedComponent = SubstrateComponent(perliteId, SubstrateComponentData(NomenclatureName("Perlite"), none))
  private val addedPesticide = Pesticide(
    PesticideId(UUID.fromString("20000000-0000-4000-8000-000000000001")),
    PesticideData(NomenclatureName("Neem"), PesticideType.Treatment, none)
  )

  test("should return operation history while preserving read failures"):
    val readFailure = RuntimeException("store down")
    val store       = StoreStub(getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false)))

    assertEquals(
      buildJournal(store).getOperations(PlantId("p1"), firstPage),
      GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false))
    )
    assertEquals(store.requestedOperationWindows.get(), Vector(PlantId("p1") -> firstPage))
    assertEquals(
      buildJournal(StoreStub(getOperationsResult = GetOperationsResult.ReadFailed(readFailure))).getOperations(PlantId("p1"), firstPage),
      GetOperationsResult.ReadFailed(readFailure)
    )

  test("should return current plants by status and surface read failures"):
    val failure = RuntimeException("plant read failed")
    val store   = StoreStub(getPlantsResult = GetPlantsResult.Read(Vector(plant)))

    assertEquals(buildJournal(store).getPlants(PlantStatus.Active), GetPlantsResult.Read(Vector(plant)))
    assertEquals(store.requestedStatuses.get(), Vector(PlantStatus.Active))
    assertEquals(
      buildJournal(StoreStub(getPlantsResult = GetPlantsResult.ReadFailed(failure))).getPlants(PlantStatus.Archived),
      GetPlantsResult.ReadFailed(failure)
    )

  test("should assign catalog identifiers and delegate nomenclature operations"):
    val componentId = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
    val pesticideId = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
    val ids         = Iterator(componentId.value.toString, pesticideId.value.toString)
    val store       = StoreStub()
    val journal     = buildJournal(store, idGen = () => ids.next())
    val component   = SubstrateComponentData(NomenclatureName("Pumice"), none)
    val pesticide   = PesticideData(NomenclatureName("Soap"), PesticideType.Treatment, none)

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

  test("should validate catalog references before writing operations"):
    val selectedCare = care.copy(pesticides = Set(vertabId, neemOilId))
    val pesticides   = Vector(
      Pesticide(
        vertabId,
        PesticideData(NomenclatureName("VERTAB"), PesticideType.Insecticide, NomenclatureInfo("0.8ml/L").some)
      ),
      Pesticide(
        neemOilId,
        PesticideData(NomenclatureName("Neem oil"), PesticideType.Insecticide, none)
      )
    )
    val validStore = StoreStub(pesticideReadResult = CatalogReadResult.Read(pesticides))
    assertEquals(buildJournal(validStore).logOperation(plant.id, date, selectedCare), LogOperationResult.Logged(OperationId("id-1")))

    val unknownPesticideStore = StoreStub()
    buildJournal(unknownPesticideStore).logOperation(plant.id, date, selectedCare) match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown pesticide ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(unknownPesticideStore.recordedOperations.get(), Vector.empty)

    val readFailure          = RuntimeException("catalog unavailable")
    val unreadablePesticides = StoreStub(pesticideReadResult = CatalogReadResult.ReadFailed(readFailure))
    assertEquals(
      buildJournal(unreadablePesticides).logOperation(plant.id, date, selectedCare),
      LogOperationResult.LoggingFailed(readFailure)
    )

    val unknownComponentStore = StoreStub(componentReadResult = CatalogReadResult.Read(Vector.empty))
    val unknownComponents     = Substrate
      .of(List(SubstratePart(perliteId, 50), SubstratePart(lecaId, 50)))
      .getOrElse(fail("invalid test substrate"))
    val repotWithUnknownComponents = OperationDetails.Repot(unknownComponents, none)
    buildJournal(unknownComponentStore).logOperation(plant.id, date, repotWithUnknownComponents) match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown substrate component ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(unknownComponentStore.recordedOperations.get(), Vector.empty)

    val unreadableComponents = StoreStub(componentReadResult = CatalogReadResult.ReadFailed(readFailure))
    assertEquals(buildJournal(unreadableComponents).logOperation(plant.id, date, repot), LogOperationResult.LoggingFailed(readFailure))

    buildJournal(unknownPesticideStore).editOperation(operation.id, selectedCare) match
      case EditOperationResult.EditFailed(reason) =>
        assert(reason.getMessage.contains(vertabId.value.toString))
        assert(reason.getMessage.contains(neemOilId.value.toString))
      case other => fail(s"expected EditFailed, got $other")
    assertEquals(unknownPesticideStore.updatedOperations.get(), Vector.empty)

  test("should record the caller's operation instant"):
    val store        = StoreStub()
    val selectedDate = date.plusSeconds(120)

    assertEquals(buildJournal(store).logOperation(PlantId("p1"), selectedDate, care), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(store.recordedOperations.get(), Vector(Operation(OperationId("id-1"), PlantId("p1"), selectedDate, care)))
    assertEquals(store.updatedPlants.get(), Vector.empty)

  test("should update a plant after recording the latest repot"):
    val newSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val newRepot     = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val store        = StoreStub()

    assertEquals(buildJournal(store).logOperation(PlantId("p1"), date, newRepot), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(store.recordedOperations.get(), Vector(Operation(OperationId("id-1"), PlantId("p1"), date, newRepot)))
    assertEquals(store.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should leave the plant unchanged when a recorded repot loses the ordering tie-break"):
    val newerRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val logged     = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val store      = StoreStub(
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(newerRepot, logged), hasNextPage = false)),
      addOperationResult = LogOperationResult.Logged(logged.id)
    )

    assertEquals(buildJournal(store, idGen = () => logged.id.value).logOperation(plant.id, date, repot), LogOperationResult.Logged(logged.id))
    assertEquals(store.updatedPlants.get(), Vector.empty)
    assertEquals(store.removedOperations.get(), Vector.empty)

  test("should preserve the current substrate when logging a historical repot"):
    val newerSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val olderSubstrate = Substrate.of(List(SubstratePart(sand3to5Id, share = 100))).getOrElse(fail("invalid test substrate"))
    val newerRepot     = Operation(OperationId("newer"), plant.id, date, OperationDetails.Repot(newerSubstrate, none))
    val olderRepot     = OperationDetails.Repot(olderSubstrate, none)
    val store          = StoreStub(
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(newerRepot), hasNextPage = false))
    )

    assertEquals(buildJournal(store).logOperation(plant.id, date.minusSeconds(60), olderRepot), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(store.updatedPlants.get(), Vector.empty)

  test("should finish concurrent repot logs in timestamp order"):
    val firstSubstrate  = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val secondSubstrate = Substrate.of(List(SubstratePart(sand3to5Id, share = 100))).getOrElse(fail("invalid test substrate"))
    val firstRepot      = OperationDetails.Repot(firstSubstrate, maybeNote = none)
    val secondRepot     = OperationDetails.Repot(secondSubstrate, maybeNote = none)
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
    val store   = StoreStub()
    val journal = buildJournal(store, idGen = idGen)

    val firstThread = Thread.ofVirtual().start: () =>
      val _ = journal.logOperation(PlantId("p1"), date, firstRepot)
    assert(firstIdCall.await(1, java.util.concurrent.TimeUnit.SECONDS))
    val secondThread = Thread.ofVirtual().start: () =>
      val _ = journal.logOperation(PlantId("p1"), date.plusNanos(1), secondRepot)
    val overtook = secondIdCall.await(100, MILLISECONDS)
    releaseFirst.countDown()
    firstThread.join()
    secondThread.join()

    assertEquals(overtook, false)
    assertEquals(store.updatedPlants.get().map(_.details.substrate), Vector(firstSubstrate, secondSubstrate))

  test("should remove a recorded repot whenever its plant cannot reflect it"):
    val historyFailure    = RuntimeException("history failed")
    val readFailure       = RuntimeException("read failed")
    val updateFailure     = RuntimeException("update failed")
    val unreadableHistory = StoreStub(getOperationsResult = GetOperationsResult.ReadFailed(historyFailure))
    val missingPlant      = StoreStub(getPlantResult = GetPlantResult.RecordMissing)
    val unreadable        = StoreStub(getPlantResult = GetPlantResult.ReadFailed(readFailure))
    val notUpdated        = StoreStub(updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure))

    assertEquals(
      buildJournal(unreadableHistory).logOperation(PlantId("p1"), date, repot),
      LogOperationResult.LoggingFailed(historyFailure)
    )
    buildJournal(missingPlant).logOperation(PlantId("p1"), date, repot) match
      case LogOperationResult.LoggingFailed(reason) => assertEquals(reason.getMessage, "cannot read plant after repot")
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(missingPlant.removedOperations.get(), Vector(OperationId("id-1")))
    assertEquals(
      buildJournal(unreadable).logOperation(PlantId("p1"), date, repot),
      LogOperationResult.LoggingFailed(readFailure)
    )
    assertEquals(unreadable.removedOperations.get(), Vector(OperationId("id-1")))
    assertEquals(
      buildJournal(notUpdated).logOperation(PlantId("p1"), date, repot),
      LogOperationResult.LoggingFailed(updateFailure)
    )
    assertEquals(notUpdated.removedOperations.get(), Vector(OperationId("id-1")))
    assertEquals(unreadableHistory.removedOperations.get(), Vector(OperationId("id-1")))

  test("should report both failures when removing a recorded repot also fails"):
    val updateFailure = RuntimeException("update failed")
    val removeFailure = RuntimeException("remove failed")
    val store         = StoreStub(
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure),
      removeOperationResult = OperationCompensationResult.CompensationFailed(removeFailure)
    )

    buildJournal(store).logOperation(PlantId("p1"), date, repot) match
      case LogOperationResult.LoggingFailed(reason) =>
        assertEquals(reason.getCause, updateFailure)
        assertEquals(reason.getSuppressed.toList, List(removeFailure))
      case other => fail(s"expected LoggingFailed, got $other")

  test("should leave the plant unchanged when recording a repot fails"):
    val cause = RuntimeException("store down")
    val store = StoreStub(addOperationResult = LogOperationResult.LoggingFailed(cause))

    assertEquals(buildJournal(store).logOperation(PlantId("p1"), date, repot), LogOperationResult.LoggingFailed(cause))
    assertEquals(store.updatedPlants.get(), Vector.empty)
    assertEquals(store.removedOperations.get(), Vector.empty)

  test("should update a plant after amending the latest repot"):
    val existingRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val tiedRepot     = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val newSubstrate  = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val amended       = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val editResult    = EditOperationResult.Edited(existingRepot.copy(details = amended))
    val store         = StoreStub(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot, tiedRepot), hasNextPage = false)),
      updateOperationResult = editResult
    )

    assertEquals(buildJournal(store).editOperation(existingRepot.id, amended), editResult)
    assertEquals(store.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should update the plant after amending its latest repot despite newer care operations"):
    val existingRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val newerCare     = Vector.tabulate(10)(index =>
      Operation(OperationId(s"care-$index"), PlantId("p1"), date.plusSeconds(index.toLong + 1), care)
    )
    val newSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val amended      = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val editResult   = EditOperationResult.Edited(existingRepot.copy(details = amended))
    val store        = StoreStub(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(newerCare, hasNextPage = true)),
      nextOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)).some,
      updateOperationResult = editResult
    )

    assertEquals(buildJournal(store).editOperation(existingRepot.id, amended), editResult)
    assertEquals(store.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should amend an older repot without changing the plant"):
    val olderRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val newerRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val amended    = OperationDetails.Repot(substrate, maybeNote = none)
    val editResult = EditOperationResult.Edited(olderRepot.copy(details = amended))
    val store      = StoreStub(
      getOperationResult = GetOperationResult.Read(olderRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(olderRepot, newerRepot), hasNextPage = false)),
      updateOperationResult = editResult
    )

    assertEquals(buildJournal(store).editOperation(olderRepot.id, amended), editResult)
    assertEquals(store.updatedPlants.get(), Vector.empty)
    assertEquals(store.restoredOperations.get(), Vector.empty)

  test("should restore an amended repot whenever plant synchronization cannot complete"):
    val existingRepot     = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val historyFailure    = RuntimeException("history unavailable")
    val plantFailure      = RuntimeException("plant unavailable")
    val updateFailure     = RuntimeException("plant update failed")
    val unreadableHistory = StoreStub(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.ReadFailed(historyFailure),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val unreadablePlant = StoreStub(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      getPlantResult = GetPlantResult.ReadFailed(plantFailure),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val plantNotUpdated = StoreStub(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      updateOperationResult = EditOperationResult.Edited(existingRepot),
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure)
    )

    assertEquals(
      buildJournal(unreadableHistory).editOperation(existingRepot.id, repot),
      EditOperationResult.EditFailed(historyFailure)
    )
    assertEquals(
      buildJournal(unreadablePlant).editOperation(existingRepot.id, repot),
      EditOperationResult.EditFailed(plantFailure)
    )
    assertEquals(
      buildJournal(plantNotUpdated).editOperation(existingRepot.id, repot),
      EditOperationResult.EditFailed(updateFailure)
    )
    List(unreadableHistory, unreadablePlant, plantNotUpdated).foreach: store =>
      assertEquals(store.updatedOperations.get(), Vector(existingRepot.id -> repot))
      assertEquals(store.restoredOperations.get(), Vector(existingRepot))

  test("should surface a failed latest repot edit without updating the plant"):
    val existingRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val cause         = RuntimeException("store down")
    val store         = StoreStub(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      updateOperationResult = EditOperationResult.EditFailed(cause)
    )

    assertEquals(buildJournal(store).editOperation(existingRepot.id, repot), EditOperationResult.EditFailed(cause))
    assertEquals(store.updatedPlants.get(), Vector.empty[Plant])

  test("should report both failures when restoring an amended repot also fails"):
    val existingRepot  = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val updateFailure  = RuntimeException("update failed")
    val restoreFailure = RuntimeException("restore failed")
    val store          = StoreStub(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
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
    val readFailure  = RuntimeException("store down")
    val typeMismatch = StoreStub()
    val missing      = StoreStub(getOperationResult = GetOperationResult.RecordMissing)
    val unreadable   = StoreStub(getOperationResult = GetOperationResult.ReadFailed(readFailure))

    assertEquals(buildJournal(typeMismatch).editOperation(operation.id, repot), EditOperationResult.OperationTypeMismatch)
    assertEquals(buildJournal(missing).editOperation(OperationId("nope"), care), EditOperationResult.OperationMissing)
    assertEquals(buildJournal(unreadable).editOperation(operation.id, care), EditOperationResult.EditFailed(readFailure))
    List(typeMismatch, missing, unreadable).foreach: store =>
      assertEquals(store.updatedOperations.get(), Vector.empty)

  test("should surface an edit failure from the store"):
    val cause = RuntimeException("store down")
    val store = StoreStub(updateOperationResult = EditOperationResult.EditFailed(cause))

    assertEquals(buildJournal(store).editOperation(operation.id, care), EditOperationResult.EditFailed(cause))

  final private case class StoreStub(
      getPlantsResult: GetPlantsResult = GetPlantsResult.Read(Vector.empty),
      getPlantResult: GetPlantResult = GetPlantResult.Read(plant),
      getOperationsResult: GetOperationsResult = GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false)),
      nextOperationsResult: Option[GetOperationsResult] = none,
      getOperationResult: GetOperationResult = GetOperationResult.Read(operation),
      addOperationResult: LogOperationResult = LogOperationResult.Logged(OperationId("id-1")),
      updateOperationResult: EditOperationResult = EditOperationResult.Edited(operation),
      removeOperationResult: OperationCompensationResult = OperationCompensationResult.Compensated,
      restoreOperationResult: OperationCompensationResult = OperationCompensationResult.Compensated,
      updatePlantResult: UpdatePlantResult = UpdatePlantResult.Updated,
      componentReadResult: CatalogReadResult[SubstrateComponent] = CatalogReadResult.Read(seededComponents),
      componentAddResult: CatalogAddResult[SubstrateComponent] = CatalogAddResult.Added(addedComponent),
      componentEditResult: CatalogEditResult[SubstrateComponent] = CatalogEditResult.RecordMissing,
      pesticideReadResult: CatalogReadResult[Pesticide] = CatalogReadResult.Read(Vector.empty),
      pesticideAddResult: CatalogAddResult[Pesticide] = CatalogAddResult.Added(addedPesticide),
      pesticideEditResult: CatalogEditResult[Pesticide] = CatalogEditResult.RecordMissing
  ) extends PlantJournalStore:
    private val operationReads                                                                    = AtomicInteger(0)
    val requestedStatuses: AtomicReference[Vector[PlantStatus]]                                   = AtomicReference(Vector.empty)
    val requestedOperationWindows: AtomicReference[Vector[(PlantId, OperationWindow)]]            = AtomicReference(Vector.empty)
    val recordedOperations: AtomicReference[Vector[Operation]]                                    = new AtomicReference(Vector.empty)
    val updatedOperations: AtomicReference[Vector[(OperationId, OperationDetails)]]               = new AtomicReference(Vector.empty)
    val removedOperations: AtomicReference[Vector[OperationId]]                                   = new AtomicReference(Vector.empty)
    val restoredOperations: AtomicReference[Vector[Operation]]                                    = new AtomicReference(Vector.empty)
    val updatedPlants: AtomicReference[Vector[Plant]]                                             = new AtomicReference(Vector.empty)
    val addedComponents: AtomicReference[Vector[SubstrateComponent]]                              = new AtomicReference(Vector.empty)
    val editedComponents: AtomicReference[Vector[(SubstrateComponentId, SubstrateComponentData)]] = new AtomicReference(Vector.empty)
    val addedPesticides: AtomicReference[Vector[Pesticide]]                                       = new AtomicReference(Vector.empty)
    val editedPesticides: AtomicReference[Vector[(PesticideId, PesticideData)]]                   = new AtomicReference(Vector.empty)

    override def getPlants(status: PlantStatus): GetPlantsResult =
      requestedStatuses.updateAndGet(_ :+ status)
      getPlantsResult
    override def getPlant(id: PlantId): GetPlantResult                                         = getPlantResult
    override def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult =
      requestedOperationWindows.updateAndGet(_ :+ (plantId -> window))
      if operationReads.getAndIncrement().equals(0) then getOperationsResult
      else nextOperationsResult.getOrElse(getOperationsResult)
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

  private def buildJournal(
      store: PlantJournalStore^,
      idGen: IdGenerator^ = () => "id-1"
  ) =
    PlantJournal.make(using store, idGen)
