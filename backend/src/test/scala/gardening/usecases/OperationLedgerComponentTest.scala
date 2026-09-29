package gardening.usecases

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.pesticide.{AddPesticideResult, GetPesticideResult, GetPesticidesResult, UpdatePesticideResult}
import gardening.domain.operations.*
import gardening.ports.{PesticideStore, OperationStore, PlantStore, OperationLedgerMetricsApi}
import gardening.domain.plants.*
import gardening.domain.substrate.{AddSubstrateComponentResult, DeleteSubstrateMixResult, GetSubstrateComponentResult, GetSubstrateComponentsResult, GetSubstrateMixesResult, SaveSubstrateMixResult, UpdateSubstrateComponentResult}
import gardening.ports.SubstrateStore
import gardening.capabilities.{IdGenerator, PlantUpdateLock, TestImplicits}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine

import language.experimental.captureChecking

import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.CountDownLatch
import scala.util.chaining.scalaUtilChainingOps

class OperationLedgerComponentTest extends munit.FunSuite with TestImplicits:

  private given metrics: OperationLedgerMetricsApi = new OperationLedgerMetricsApi:
    def incrementAction(kind: ActionType): Unit                            = ()
    def incrementRepot(plant: PlantId): Unit                               = ()
    def incrementMoisture(level: MoistureLevel): Unit                      = ()
    def incrementSubstrateComponent(component: SubstrateComponentId): Unit = ()
    def incrementPesticide(pesticide: PesticideId): Unit                   = ()

  private val date       = Instant.parse("2026-01-01T00:00:00Z")
  private val perliteId  = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val pineBarkId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000004"))
  private val sand3to5Id = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000005"))
  private val lecaId     = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000007"))
  private val vertabId   = PesticideId(UUID.fromString("00000000-0000-4000-8001-000000000003"))
  private val neemOilId  = PesticideId(UUID.fromString("00000000-0000-4000-8001-000000000007"))

  private def substrateOf(parts: SubstratePart*) = Substrate.of(parts.toList).getOrElse(fail("invalid test substrate"))

  private val substrate = substrateOf(SubstratePart(perliteId, share = 100))

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
    .map(id => SubstrateComponent(id, SubstrateComponentData(SubstrateComponentName(id.value.toString), none), SubstrateComponentStatus.Active))

  test("should return operation history while preserving read failures"):
    val readFailure         = RuntimeException("store down")
    val getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false))
    val refs                = Refs()

    val result       = buildLedger(refs, getOperationsResult = getOperationsResult).getOperations(plant.id, firstPage)
    val failedResult = buildLedger(getOperationsResult = GetOperationsResult.ReadFailed(readFailure)).getOperations(plant.id, firstPage)

    assertEquals(result, getOperationsResult)
    assertEquals(failedResult, GetOperationsResult.ReadFailed(readFailure))
    assertEquals(refs.requestedOperationWindows.get(), Vector(PlantId("p1") -> firstPage))

  test("should read an ordered recorded date range independently of operation pages"):
    val firstRecorded = date
    val lastRecorded  = date.plusSeconds(60)
    val recorded      = OperationDateRange.Recorded(firstRecorded, lastRecorded)
    val failure       = RuntimeException("date read failed")

    val recordedRefs = Refs()

    val recordedResult =
      buildLedger(recordedRefs, operationDateRangeResult = GetOperationDateRangeResult.Read(recorded)).getOperationDateRange(plant.id)
    val emptyResult   = buildLedger().getOperationDateRange(plant.id)
    val missingResult = buildLedger(operationDateRangeResult = GetOperationDateRangeResult.PlantMissing).getOperationDateRange(plant.id)
    val failedResult  = buildLedger(operationDateRangeResult = GetOperationDateRangeResult.ReadFailed(failure)).getOperationDateRange(plant.id)
    val invalidRange  = intercept[IllegalArgumentException](OperationDateRange.Recorded(lastRecorded, firstRecorded))

    val expectedRecorded = GetOperationDateRangeResult.Read(recorded)
    val expectedEmpty    = GetOperationDateRangeResult.Read(OperationDateRange.Empty)
    assertEquals(recordedResult, expectedRecorded)
    assertEquals(emptyResult, expectedEmpty)
    assertEquals(missingResult, GetOperationDateRangeResult.PlantMissing)
    assertEquals(failedResult, GetOperationDateRangeResult.ReadFailed(failure))
    assertEquals(invalidRange.getMessage, "requirement failed: last recorded operation cannot precede first")
    assertEquals(recordedRefs.requestedDateRanges.get(), Vector(plant.id))
    assertEquals(recordedRefs.requestedOperationWindows.get(), Vector.empty)

  test("should log a care operation once its referenced pesticides are all active"):
    val selectedCare = care.copy(pesticides = Set(vertabId, neemOilId))
    val vertabData   = PesticideData(PesticideName("VERTAB"), PesticideType.Insecticide, PesticideInfo("0.8ml/L").some)
    val neemOilData  = PesticideData(PesticideName("Neem oil"), PesticideType.Insecticide, none)
    val pesticides   = Vector(
      Pesticide(vertabId, vertabData, status = PesticideStatus.Active),
      Pesticide(neemOilId, neemOilData, status = PesticideStatus.Active)
    )

    assertEquals(
      buildLedger(pesticideReadResult = GetPesticidesResult.Read(pesticides)).logOperation(plant.id, date, selectedCare),
      LogOperationResult.Logged(OperationId("id-1"))
    )

  test("should reject logging a care operation that references unknown or archived pesticides, without recording it"):
    val selectedCare       = care.copy(pesticides = Set(vertabId, neemOilId))
    val archivedPesticides = Vector(
      Pesticide(vertabId, PesticideData(PesticideName("VERTAB"), PesticideType.Insecticide, none), PesticideStatus.Archived),
      Pesticide(neemOilId, PesticideData(PesticideName("Neem oil"), PesticideType.Insecticide, none), PesticideStatus.Archived)
    )
    val refs = Refs()

    val unknownResult  = buildLedger(refs).logOperation(plant.id, date, selectedCare)
    val archivedResult =
      buildLedger(refs, pesticideReadResult = GetPesticidesResult.Read(archivedPesticides)).logOperation(plant.id, date, selectedCare)

    unknownResult match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown pesticide ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    archivedResult match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown pesticide ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(refs.recordedOperations.get(), Vector.empty)

  test("should reject logging a repot operation that references unknown or archived substrate components, without recording it"):
    val unknownComponents          = substrateOf(SubstratePart(perliteId, 50), SubstratePart(lecaId, 50))
    val repotWithUnknownComponents = OperationDetails.Repot(unknownComponents, none)
    val archivedPerlite            =
      SubstrateComponent(perliteId, SubstrateComponentData(SubstrateComponentName(perliteId.value.toString), none), SubstrateComponentStatus.Archived)
    val archivedComponents       = archivedPerlite +: seededComponents.drop(1)
    val unknownComponentsResult  = GetSubstrateComponentsResult.Read(Vector.empty)
    val archivedComponentsResult = GetSubstrateComponentsResult.Read(archivedComponents)
    val refs                     = Refs()

    val unknownResult  = buildLedger(refs, componentReadResult = unknownComponentsResult).logOperation(plant.id, date, repotWithUnknownComponents)
    val archivedResult = buildLedger(refs, componentReadResult = archivedComponentsResult).logOperation(plant.id, date, repot)

    unknownResult match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown substrate component ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    archivedResult match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown substrate component ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(refs.recordedOperations.get(), Vector.empty)
    assertEquals(refs.recordedRepots.get(), Vector.empty)

  test("should preserve pesticide and substrate catalog read failures when validating an operation"):
    val readFailure  = RuntimeException("catalog unavailable")
    val selectedCare = care.copy(pesticides = Set(vertabId))

    val careResult  = buildLedger(pesticideReadResult = GetPesticidesResult.ReadFailed(readFailure)).logOperation(plant.id, date, selectedCare)
    val repotResult = buildLedger(componentReadResult = GetSubstrateComponentsResult.ReadFailed(readFailure)).logOperation(plant.id, date, repot)

    assertEquals(careResult, LogOperationResult.LoggingFailed(readFailure))
    assertEquals(repotResult, LogOperationResult.LoggingFailed(readFailure))

  test("should reject editing an operation to reference unknown pesticides, without writing"):
    val selectedCare = care.copy(pesticides = Set(vertabId, neemOilId))
    val refs         = Refs()

    buildLedger(refs).editOperation(operation.id, selectedCare) match
      case EditOperationResult.EditFailed(reason) =>
        assert(reason.getMessage.contains(vertabId.value.toString))
        assert(reason.getMessage.contains(neemOilId.value.toString))
      case other => fail(s"expected EditFailed, got $other")
    assertEquals(refs.updatedOperations.get(), Vector.empty)

  test("should record the caller's operation instant"):
    val refs         = Refs()
    val selectedDate = date.plusSeconds(120)

    assertEquals(buildLedger(refs).logOperation(PlantId("p1"), selectedDate, care), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(refs.recordedOperations.get(), Vector(Operation(OperationId("id-1"), PlantId("p1"), selectedDate, care)))

  test("should reject new care and repot for an archived plant without recording history"):
    val archivedPlant  = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))
    val getPlantResult = GetPlantResult.Read(archivedPlant)
    val refs           = Refs()

    val ledger      = buildLedger(refs, getPlantResult = getPlantResult)
    val careResult  = ledger.logOperation(plant.id, date, care)
    val repotResult = ledger.logOperation(plant.id, date, repot)

    assertEquals(careResult, LogOperationResult.PlantArchived)
    assertEquals(repotResult, LogOperationResult.PlantArchived)
    assertEquals(refs.recordedOperations.get(), Vector.empty)
    assertEquals(refs.recordedRepots.get(), Vector.empty)

  test("should preserve missing and unreadable plant failures when logging"):
    val readFailure = RuntimeException("plant unavailable")
    val refs        = Refs()

    val missing = buildLedger(refs, getPlantResult = GetPlantResult.RecordMissing).logOperation(plant.id, date, care)
    val failed  = buildLedger(refs, getPlantResult = GetPlantResult.ReadFailed(readFailure)).logOperation(plant.id, date, care)

    assertEquals(missing, LogOperationResult.PlantMissing)
    assertEquals(failed, LogOperationResult.LoggingFailed(readFailure))
    assertEquals(refs.recordedOperations.get(), Vector.empty)

  test("should amend care without changing the plant"):
    val refs = Refs()

    assertEquals(buildLedger(refs).editOperation(operation.id, care), EditOperationResult.Edited(operation))
    assertEquals(refs.updatedOperations.get(), Vector(operation.id -> care))

  test("should reject invalid edit requests before writing"):
    val readFailure = RuntimeException("store down")
    val refs        = Refs()

    val typeMismatchResult = buildLedger(refs).editOperation(operation.id, repot)
    val missingResult      = buildLedger(refs, getOperationResult = GetOperationResult.RecordMissing).editOperation(OperationId("nope"), care)
    val unreadableResult   = buildLedger(refs, getOperationResult = GetOperationResult.ReadFailed(readFailure)).editOperation(operation.id, care)

    assertEquals(typeMismatchResult, EditOperationResult.OperationTypeMismatch)
    assertEquals(missingResult, EditOperationResult.OperationMissing)
    assertEquals(unreadableResult, EditOperationResult.EditFailed(readFailure))
    assertEquals(refs.updatedOperations.get(), Vector.empty)

  test("should preserve an unexpected updateOperation outcome when editing"):
    assertEquals(
      buildLedger(updateOperationResult = EditOperationResult.OperationMissing).editOperation(operation.id, care),
      EditOperationResult.OperationMissing
    )

  test("should surface an edit failure from the store"):
    val cause = RuntimeException("store down")
    assertEquals(
      buildLedger(updateOperationResult = EditOperationResult.EditFailed(cause)).editOperation(operation.id, care),
      EditOperationResult.EditFailed(cause)
    )

  test("should delete a care operation"):
    val refs   = Refs()
    val ledger = buildLedger(refs, getOperationResult = GetOperationResult.Read(operation))

    assertEquals(ledger.deleteOperation(operation.id), DeleteOperationResult.Deleted)
    assertEquals(refs.removedOperations.get(), Vector(operation.id))

  test("should preserve missing, unreadable, and write-failed outcomes when deleting"):
    val readFailure            = RuntimeException("store down")
    val latestRepotFailure     = RuntimeException("latest repot unavailable")
    val removeFailure          = RuntimeException("remove failed")
    val existingRepot          = Operation(OperationId("o1"), plant.id, date, repot)
    val getExistingRepotResult = GetOperationResult.Read(existingRepot)
    val getLatestRepotFailure  = () => GetLatestRepotResult.ReadFailed(latestRepotFailure)
    val getOperationReadResult = GetOperationResult.Read(operation)
    val removeOperationFailure = OperationCompensationResult.CompensationFailed(removeFailure)

    val missingResult           = buildLedger(getOperationResult = GetOperationResult.RecordMissing).deleteOperation(OperationId("nope"))
    val unreadableResult        = buildLedger(getOperationResult = GetOperationResult.ReadFailed(readFailure)).deleteOperation(operation.id)
    val latestRepotFailedResult =
      buildLedger(getOperationResult = getExistingRepotResult, getLatestRepotResult = getLatestRepotFailure).deleteOperation(existingRepot.id)
    val writeFailedResult =
      buildLedger(getOperationResult = getOperationReadResult, removeOperationResult = removeOperationFailure).deleteOperation(operation.id)

    assertEquals(missingResult, DeleteOperationResult.OperationMissing)
    assertEquals(unreadableResult, DeleteOperationResult.DeleteFailed(readFailure))
    assertEquals(latestRepotFailedResult, DeleteOperationResult.DeleteFailed(latestRepotFailure))
    assertEquals(writeFailedResult, DeleteOperationResult.DeleteFailed(removeFailure))

  test("should route a repot log through the store's dedicated repot path, propagating its outcome"):
    val cause          = RuntimeException("store down")
    val loggedId       = OperationId("id-2")
    val logRepotResult = AddOperationResult.Logged(loggedId)
    val refs           = Refs()

    val logged = buildLedger(refs, logRepotResult = logRepotResult).logOperation(plant.id, date, repot)
    val failed = buildLedger(refs, logRepotResult = AddOperationResult.LoggingFailed(cause)).logOperation(plant.id, date, repot)

    assertEquals(logged, LogOperationResult.Logged(loggedId))
    assertEquals(failed, LogOperationResult.LoggingFailed(cause))
    assertEquals(refs.recordedOperations.get(), Vector.empty)
    assertEquals(refs.recordedRepots.get(), Vector.fill(2)(Operation(OperationId("id-1"), plant.id, date, repot)))

  test("should route a repot edit through the store's dedicated repot path, propagating its outcome"):
    val cause              = RuntimeException("store down")
    val existingRepot      = operation.copy(details = repot)
    val getOperationResult = GetOperationResult.Read(existingRepot)
    val edited             = EditOperationResult.Edited(existingRepot)
    val editFailed         = EditOperationResult.EditFailed(cause)
    val refs               = Refs()

    val editedResult = buildLedger(refs, getOperationResult = getOperationResult, editRepotResult = edited).editOperation(operation.id, repot)
    val failedResult = buildLedger(refs, getOperationResult = getOperationResult, editRepotResult = editFailed).editOperation(operation.id, repot)

    assertEquals(editedResult, edited)
    assertEquals(failedResult, editFailed)
    assertEquals(refs.updatedOperations.get(), Vector.empty)
    assertEquals(refs.editedRepots.get().size, 2)

  test("should finish concurrent operation logs one at a time"):
    val firstOperation  = Operation(OperationId("id-1"), PlantId("p1"), date, care)
    val secondOperation = Operation(OperationId("id-2"), PlantId("p1"), date.plusNanos(1), care)
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
    val refs   = Refs()
    val ledger = buildLedger(refs, nextId = () => idGen.nextId())

    val firstThread = Thread.ofVirtual().start: () =>
      val _ = ledger.logOperation(PlantId("p1"), date, care)
    assert(firstIdCall.await(1, java.util.concurrent.TimeUnit.SECONDS))
    val secondThread = Thread.ofVirtual().start: () =>
      val _ = ledger.logOperation(PlantId("p1"), date.plusNanos(1), care)
    val overtook = secondIdCall.await(100, MILLISECONDS)
    releaseFirst.countDown()
    firstThread.join()
    secondThread.join()

    assertEquals(overtook, false)
    assertEquals(refs.recordedOperations.get(), Vector(firstOperation, secondOperation))

  test("should delete a non-latest repot"):
    val olderRepot           = Operation(OperationId("o1"), plant.id, date, repot)
    val newerRepot           = Operation(OperationId("o2"), plant.id, date.plusSeconds(60), repot)
    val getOperationResult   = GetOperationResult.Read(olderRepot)
    val getLatestRepotResult = () => GetLatestRepotResult.Read(newerRepot.some)
    val refs                 = Refs()

    val ledger = buildLedger(refs, getOperationResult = getOperationResult, getLatestRepotResult = getLatestRepotResult)

    assertEquals(ledger.deleteOperation(olderRepot.id), DeleteOperationResult.Deleted)
    assertEquals(refs.removedOperations.get(), Vector(olderRepot.id))

  test("should reject deleting a plant's latest repot"):
    val onlyRepot            = Operation(OperationId("o1"), plant.id, date, repot)
    val getOperationResult   = GetOperationResult.Read(onlyRepot)
    val getLatestRepotResult = () => GetLatestRepotResult.Read(onlyRepot.some)
    val refs                 = Refs()

    val ledger = buildLedger(refs, getOperationResult = getOperationResult, getLatestRepotResult = getLatestRepotResult)

    assertEquals(ledger.deleteOperation(onlyRepot.id), DeleteOperationResult.CannotDeleteLatestRepot)
    assertEquals(refs.removedOperations.get(), Vector.empty)

  test("should allow deleting a repot when its plant has no latest repot on record"):
    val orphanRepot        = Operation(OperationId("o1"), plant.id, date, repot)
    val getOperationResult = GetOperationResult.Read(orphanRepot)
    val refs               = Refs()

    val ledger = buildLedger(refs, getOperationResult = getOperationResult)

    assertEquals(ledger.deleteOperation(orphanRepot.id), DeleteOperationResult.Deleted)
    assertEquals(refs.removedOperations.get(), Vector(orphanRepot.id))

  final private case class Refs():
    val requestedOperationWindows: AtomicReference[Vector[(PlantId, OperationWindow)]] = AtomicReference(Vector.empty)
    val requestedDateRanges: AtomicReference[Vector[PlantId]]                          = AtomicReference(Vector.empty)
    val recordedOperations: AtomicReference[Vector[Operation]]                         = AtomicReference(Vector.empty)
    val recordedRepots: AtomicReference[Vector[Operation]]                             = AtomicReference(Vector.empty)
    val updatedOperations: AtomicReference[Vector[(OperationId, OperationDetails)]]    = AtomicReference(Vector.empty)
    val editedRepots: AtomicReference[Vector[(OperationId, OperationDetails.Repot)]]   = AtomicReference(Vector.empty)
    val removedOperations: AtomicReference[Vector[OperationId]]                        = AtomicReference(Vector.empty)

  private def buildLedger(
      refs: Refs = Refs(),
      getPlantResult: GetPlantResult = GetPlantResult.Read(plant),
      getOperationsResult: GetOperationsResult = GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false)),
      operationDateRangeResult: GetOperationDateRangeResult = GetOperationDateRangeResult.Read(OperationDateRange.Empty),
      getLatestRepotResult: () => GetLatestRepotResult = () => GetLatestRepotResult.Read(none),
      getOperationResult: GetOperationResult = GetOperationResult.Read(operation),
      addOperationResult: AddOperationResult = AddOperationResult.Logged(OperationId("id-1")),
      logRepotResult: AddOperationResult = AddOperationResult.Logged(OperationId("id-1")),
      updateOperationResult: EditOperationResult = EditOperationResult.Edited(operation),
      editRepotResult: EditOperationResult = EditOperationResult.Edited(operation),
      removeOperationResult: OperationCompensationResult = OperationCompensationResult.Compensated,
      componentReadResult: GetSubstrateComponentsResult = GetSubstrateComponentsResult.Read(seededComponents),
      pesticideReadResult: GetPesticidesResult = GetPesticidesResult.Read(Vector.empty),
      nextId: () => String = () => "id-1"
  ) =
    val plantStore = new PlantStore:
      override def addPlant(plant: Plant): AddPlantResult          = fail("operations must not add plants")
      override def getPlants(status: PlantStatus): GetPlantsResult = fail("operations must not list plants")
      override def getArchivedCount: ArchivedCountResult           = fail("operations must not count plants")
      override def getPlant(plant: PlantId): GetPlantResult        = getPlantResult
      override def updatePlant(plant: Plant): UpdatePlantResult    = fail("operations must not update plants")
    val store = new OperationStore:
      override def getOperations(plant: PlantId, window: OperationWindow): GetOperationsResult =
        refs.requestedOperationWindows.updateAndGet(_ :+ (plant -> window)).pipe(_ => getOperationsResult)
      override def getOperationDateRange(plant: PlantId): GetOperationDateRangeResult =
        refs.requestedDateRanges.updateAndGet(_ :+ plant).pipe(_ => operationDateRangeResult)
      override def getLatestRepot(plant: PlantId): GetLatestRepotResult     = getLatestRepotResult()
      override def getOperation(operation: OperationId): GetOperationResult = getOperationResult
      override def addOperation(operation: Operation): AddOperationResult   =
        refs.recordedOperations.updateAndGet(_ :+ operation).pipe(_ => addOperationResult)
      override def logRepot(id: OperationId, plant: PlantId, date: Instant, details: OperationDetails.Repot): AddOperationResult =
        refs.recordedRepots.updateAndGet(_ :+ Operation(id, plant, date, details)).pipe(_ => logRepotResult)
      override def updateOperation(operation: OperationId, details: OperationDetails): EditOperationResult =
        refs.updatedOperations.updateAndGet(_ :+ (operation -> details)).pipe(_ => updateOperationResult)
      override def editRepot(operation: OperationId, details: OperationDetails.Repot): EditOperationResult =
        refs.editedRepots.updateAndGet(_ :+ (operation -> details)).pipe(_ => editRepotResult)
      override def removeOperation(operation: OperationId): OperationCompensationResult =
        refs.removedOperations.updateAndGet(_ :+ operation).pipe(_ => removeOperationResult)
    val substrateStore = new SubstrateStore:
      override def getSubstrateComponents: GetSubstrateComponentsResult                         = componentReadResult
      override def getSubstrateComponent(id: SubstrateComponentId): GetSubstrateComponentResult =
        fail("operations must not read a single substrate component")
      override def addSubstrateComponent(component: SubstrateComponent): AddSubstrateComponentResult =
        fail("operations must not write substrate components")
      override def updateSubstrateComponent(component: SubstrateComponent): UpdateSubstrateComponentResult =
        fail("operations must not edit substrate components")
      override def getSubstrateMixes: GetSubstrateMixesResult =
        fail("operations must not read substrate mixes")
      override def saveSubstrateMix(mix: SubstrateMix): SaveSubstrateMixResult =
        fail("operations must not write substrate mixes")
      override def deleteSubstrateMix(id: java.util.UUID): DeleteSubstrateMixResult =
        fail("operations must not delete substrate mixes")
    val pesticideStore = new PesticideStore:
      override def getPesticides: GetPesticidesResult                     = pesticideReadResult
      override def getPesticide(id: PesticideId): GetPesticideResult      = fail("operations must not read a single pesticide")
      override def addPesticide(pesticide: Pesticide): AddPesticideResult =
        fail("operations must not write pesticides")
      override def updatePesticide(pesticide: Pesticide): UpdatePesticideResult =
        fail("operations must not update pesticides")
    OperationLedger.make(using store, plantStore, substrateStore, pesticideStore, () => nextId(), PlantUpdateLock.make)
