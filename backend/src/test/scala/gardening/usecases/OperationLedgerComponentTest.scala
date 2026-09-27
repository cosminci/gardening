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
    val readFailure = RuntimeException("store down")

    val refs   = Refs()
    val result = buildOperations(
      refs,
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false))
    ).getOperations(plant.id, firstPage)
    val failedResult = buildOperations(getOperationsResult = GetOperationsResult.ReadFailed(readFailure)).getOperations(plant.id, firstPage)

    val expected = GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false))
    assertEquals(result, expected)
    assertEquals(failedResult, GetOperationsResult.ReadFailed(readFailure))
    assertEquals(refs.requestedOperationWindows.get(), Vector(PlantId("p1") -> firstPage))

  test("should read an ordered recorded date range independently of operation pages"):
    val firstRecorded = date
    val lastRecorded  = date.plusSeconds(60)
    val recorded      = OperationDateRange.Recorded(firstRecorded, lastRecorded)
    val failure       = RuntimeException("date read failed")

    val recordedRefs = Refs()

    val recordedResult =
      buildOperations(recordedRefs, operationDateRangeResult = GetOperationDateRangeResult.Read(recorded)).getOperationDateRange(plant.id)
    val emptyResult   = buildOperations().getOperationDateRange(plant.id)
    val missingResult = buildOperations(operationDateRangeResult = GetOperationDateRangeResult.PlantMissing).getOperationDateRange(plant.id)
    val failedResult  = buildOperations(operationDateRangeResult = GetOperationDateRangeResult.ReadFailed(failure)).getOperationDateRange(plant.id)
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
      buildOperations(pesticideReadResult = GetPesticidesResult.Read(pesticides)).logOperation(plant.id, date, selectedCare),
      LogOperationResult.Logged(OperationId("id-1"))
    )

  test("should reject logging a care operation that references unknown or archived pesticides, without recording it"):
    val selectedCare       = care.copy(pesticides = Set(vertabId, neemOilId))
    val archivedPesticides = Vector(
      Pesticide(vertabId, PesticideData(PesticideName("VERTAB"), PesticideType.Insecticide, none), PesticideStatus.Archived),
      Pesticide(neemOilId, PesticideData(PesticideName("Neem oil"), PesticideType.Insecticide, none), PesticideStatus.Archived)
    )
    val refs = Refs()

    val unknownResult  = buildOperations(refs).logOperation(plant.id, date, selectedCare)
    val archivedResult =
      buildOperations(refs, pesticideReadResult = GetPesticidesResult.Read(archivedPesticides)).logOperation(plant.id, date, selectedCare)

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
    val archivedComponents = archivedPerlite +: seededComponents.drop(1)
    val refs               = Refs()

    val unknownResult =
      buildOperations(
        refs,
        componentReadResult = GetSubstrateComponentsResult.Read(Vector.empty)
      ).logOperation(plant.id, date, repotWithUnknownComponents)
    val archivedResult =
      buildOperations(refs, componentReadResult = GetSubstrateComponentsResult.Read(archivedComponents)).logOperation(plant.id, date, repot)

    unknownResult match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown substrate component ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    archivedResult match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown substrate component ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(refs.recordedOperations.get(), Vector.empty)

  test("should preserve pesticide and substrate catalog read failures when validating an operation"):
    val readFailure  = RuntimeException("catalog unavailable")
    val selectedCare = care.copy(pesticides = Set(vertabId))

    val careResult =
      buildOperations(pesticideReadResult = GetPesticidesResult.ReadFailed(readFailure)).logOperation(plant.id, date, selectedCare)
    val repotResult = buildOperations(componentReadResult = GetSubstrateComponentsResult.ReadFailed(readFailure)).logOperation(plant.id, date, repot)

    assertEquals(careResult, LogOperationResult.LoggingFailed(readFailure))
    assertEquals(repotResult, LogOperationResult.LoggingFailed(readFailure))

  test("should reject editing an operation to reference unknown pesticides, without writing"):
    val selectedCare = care.copy(pesticides = Set(vertabId, neemOilId))
    val refs         = Refs()

    buildOperations(refs).editOperation(operation.id, selectedCare) match
      case EditOperationResult.EditFailed(reason) =>
        assert(reason.getMessage.contains(vertabId.value.toString))
        assert(reason.getMessage.contains(neemOilId.value.toString))
      case other => fail(s"expected EditFailed, got $other")
    assertEquals(refs.updatedOperations.get(), Vector.empty)

  test("should record the caller's operation instant"):
    val refs         = Refs()
    val selectedDate = date.plusSeconds(120)

    assertEquals(buildOperations(refs).logOperation(PlantId("p1"), selectedDate, care), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(refs.recordedOperations.get(), Vector(Operation(OperationId("id-1"), PlantId("p1"), selectedDate, care)))
    assertEquals(refs.updatedPlants.get(), Vector.empty)

  test("should reject new care and repot for an archived plant without recording history"):
    val archivedPlant = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))

    val refs        = Refs()
    val operations  = buildOperations(refs, getPlantResult = GetPlantResult.Read(archivedPlant))
    val careResult  = operations.logOperation(plant.id, date, care)
    val repotResult = operations.logOperation(plant.id, date, repot)

    assertEquals(careResult, LogOperationResult.PlantArchived)
    assertEquals(repotResult, LogOperationResult.PlantArchived)
    assertEquals(refs.recordedOperations.get(), Vector.empty)
    assertEquals(refs.updatedPlants.get(), Vector.empty)

  test("should preserve missing and unreadable plant failures when logging"):
    val readFailure = RuntimeException("plant unavailable")
    val refs        = Refs()

    val missing = buildOperations(refs, getPlantResult = GetPlantResult.RecordMissing).logOperation(plant.id, date, care)
    val failed  = buildOperations(refs, getPlantResult = GetPlantResult.ReadFailed(readFailure)).logOperation(plant.id, date, care)

    assertEquals(missing, LogOperationResult.PlantMissing)
    assertEquals(failed, LogOperationResult.LoggingFailed(readFailure))
    assertEquals(refs.recordedOperations.get(), Vector.empty)

  test("should amend care without changing the plant"):
    val refs = Refs()

    assertEquals(buildOperations(refs).editOperation(operation.id, care), EditOperationResult.Edited(operation))
    assertEquals(refs.updatedOperations.get(), Vector(operation.id -> care))
    assertEquals(refs.updatedPlants.get(), Vector.empty)

  test("should reject invalid edit requests before writing"):
    val readFailure = RuntimeException("store down")
    val refs        = Refs()

    val typeMismatchResult = buildOperations(refs).editOperation(operation.id, repot)
    val missingResult      = buildOperations(refs, getOperationResult = GetOperationResult.RecordMissing).editOperation(OperationId("nope"), care)
    val unreadableResult   =
      buildOperations(refs, getOperationResult = GetOperationResult.ReadFailed(readFailure)).editOperation(operation.id, care)

    assertEquals(typeMismatchResult, EditOperationResult.OperationTypeMismatch)
    assertEquals(missingResult, EditOperationResult.OperationMissing)
    assertEquals(unreadableResult, EditOperationResult.EditFailed(readFailure))
    assertEquals(refs.updatedOperations.get(), Vector.empty)

  test("should preserve an unexpected updateOperation outcome when editing"):
    assertEquals(
      buildOperations(updateOperationResult = EditOperationResult.OperationMissing).editOperation(operation.id, care),
      EditOperationResult.OperationMissing
    )

  test("should surface an edit failure from the store"):
    val cause = RuntimeException("store down")
    assertEquals(
      buildOperations(updateOperationResult = EditOperationResult.EditFailed(cause)).editOperation(operation.id, care),
      EditOperationResult.EditFailed(cause)
    )

  test("should delete a care operation"):
    val refs       = Refs()
    val operations = buildOperations(refs, getOperationResult = GetOperationResult.Read(operation))

    assertEquals(operations.deleteOperation(operation.id), DeleteOperationResult.Deleted)
    assertEquals(refs.removedOperations.get(), Vector(operation.id))

  test("should preserve missing, unreadable, and write-failed outcomes when deleting"):
    val readFailure        = RuntimeException("store down")
    val latestRepotFailure = RuntimeException("latest repot unavailable")
    val removeFailure      = RuntimeException("remove failed")
    val existingRepot      = Operation(OperationId("o1"), plant.id, date, repot)

    val missingResult    = buildOperations(getOperationResult = GetOperationResult.RecordMissing).deleteOperation(OperationId("nope"))
    val unreadableResult =
      buildOperations(getOperationResult = GetOperationResult.ReadFailed(readFailure)).deleteOperation(operation.id)
    val latestRepotFailedResult = buildOperations(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getLatestRepotResult = () => GetLatestRepotResult.ReadFailed(latestRepotFailure)
    ).deleteOperation(existingRepot.id)
    val writeFailedResult = buildOperations(
      getOperationResult = GetOperationResult.Read(operation),
      removeOperationResult = OperationCompensationResult.CompensationFailed(removeFailure)
    ).deleteOperation(operation.id)

    assertEquals(missingResult, DeleteOperationResult.OperationMissing)
    assertEquals(unreadableResult, DeleteOperationResult.DeleteFailed(readFailure))
    assertEquals(latestRepotFailedResult, DeleteOperationResult.DeleteFailed(latestRepotFailure))
    assertEquals(writeFailedResult, DeleteOperationResult.DeleteFailed(removeFailure))

  test("should update a plant after recording the latest repot"):
    val newSubstrate    = substrateOf(SubstratePart(lecaId, share = 100))
    val newRepot        = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val loggedOperation = Operation(OperationId("id-1"), PlantId("p1"), date, newRepot)
    val refs            = Refs()
    val operations      = buildOperations(refs, getLatestRepotResult = () => GetLatestRepotResult.Read(loggedOperation.some))

    assertEquals(operations.logOperation(PlantId("p1"), date, newRepot), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(refs.recordedOperations.get(), Vector(loggedOperation))
    assertEquals(refs.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should leave the plant unchanged when the newly logged repot is not the latest"):
    val latestRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val logged      = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val refs        = Refs()

    val operations = buildOperations(
      refs,
      getLatestRepotResult = () => GetLatestRepotResult.Read(latestRepot.some),
      addOperationResult = AddOperationResult.Logged(logged.id),
      nextId = () => logged.id.value
    )
    assertEquals(operations.logOperation(plant.id, date, repot), LogOperationResult.Logged(logged.id))
    assertEquals(refs.updatedPlants.get(), Vector.empty)
    assertEquals(refs.removedOperations.get(), Vector.empty)

  test("should finish concurrent repot logs in timestamp order"):
    val firstSubstrate  = substrateOf(SubstratePart(lecaId, share = 100))
    val secondSubstrate = substrateOf(SubstratePart(sand3to5Id, share = 100))
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
    val repotChecks = AtomicInteger()
    val latestRepot = () =>
      if repotChecks.incrementAndGet().equals(1) then GetLatestRepotResult.Read(firstOperation.some)
      else GetLatestRepotResult.Read(secondOperation.some)
    val refs       = Refs()
    val operations = buildOperations(refs, nextId = () => idGen.nextId(), getLatestRepotResult = latestRepot)

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
    val latestRepotFailure = RuntimeException("latest repot unavailable")
    val readFailure        = RuntimeException("read failed")
    val updateFailure      = RuntimeException("update failed")
    val loggedRepot        = Operation(OperationId("id-1"), plant.id, date, repot)
    val isLatest           = () => GetLatestRepotResult.Read(loggedRepot.some)
    val refs               = Refs()

    val historyResult =
      buildOperations(refs, getLatestRepotResult = () => GetLatestRepotResult.ReadFailed(latestRepotFailure)).logOperation(plant.id, date, repot)
    val missingResult = buildOperations(
      refs,
      getLatestRepotResult = isLatest,
      getPlantResultAfterLog = GetPlantResult.RecordMissing.some
    ).logOperation(plant.id, date, repot)
    val readResult = buildOperations(
      refs,
      getLatestRepotResult = isLatest,
      getPlantResultAfterLog = GetPlantResult.ReadFailed(readFailure).some
    ).logOperation(plant.id, date, repot)
    val updateResult = buildOperations(
      refs,
      getLatestRepotResult = isLatest,
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure)
    ).logOperation(plant.id, date, repot)

    assertEquals(historyResult, LogOperationResult.LoggingFailed(latestRepotFailure))
    missingResult match
      case LogOperationResult.LoggingFailed(reason) => assertEquals(reason.getMessage, "cannot read plant after repot")
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(readResult, LogOperationResult.LoggingFailed(readFailure))
    assertEquals(updateResult, LogOperationResult.LoggingFailed(updateFailure))
    assertEquals(refs.removedOperations.get(), Vector.fill(4)(OperationId("id-1")))

  test("should report both failures when removing a recorded repot also fails"):
    val updateFailure = RuntimeException("update failed")
    val removeFailure = RuntimeException("remove failed")
    val loggedRepot   = Operation(OperationId("id-1"), PlantId("p1"), date, repot)
    val operations    = buildOperations(
      getLatestRepotResult = () => GetLatestRepotResult.Read(loggedRepot.some),
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
      buildOperations(refs, addOperationResult = AddOperationResult.LoggingFailed(cause)).logOperation(PlantId("p1"), date, repot),
      LogOperationResult.LoggingFailed(cause)
    )
    assertEquals(refs.updatedPlants.get(), Vector.empty)
    assertEquals(refs.removedOperations.get(), Vector.empty)

  test("should update a plant after amending the latest repot"):
    val existingRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val newSubstrate  = substrateOf(SubstratePart(lecaId, share = 100))
    val amended       = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val editResult    = EditOperationResult.Edited(existingRepot.copy(details = amended))
    val refs          = Refs()
    val operations    = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getLatestRepotResult = () => GetLatestRepotResult.Read(existingRepot.some),
      updateOperationResult = editResult
    )

    assertEquals(operations.editOperation(existingRepot.id, amended), editResult)
    assertEquals(refs.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should allow editing an archived repot without changing its archived status"):
    val archivedPlant    = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))
    val existing         = Operation(OperationId("o2"), plant.id, date, repot)
    val amendedSubstrate = substrateOf(SubstratePart(lecaId, share = 100))
    val amended          = OperationDetails.Repot(amendedSubstrate, maybeNote = none)
    val updated          = existing.copy(details = amended)

    val refs       = Refs()
    val operations = buildOperations(
      refs,
      getPlantResult = GetPlantResult.Read(archivedPlant),
      getOperationResult = GetOperationResult.Read(existing),
      getLatestRepotResult = () => GetLatestRepotResult.Read(existing.some),
      updateOperationResult = EditOperationResult.Edited(updated)
    )
    val editResult = operations.editOperation(existing.id, amended)

    val expectedPlant = archivedPlant.copy(details = archivedPlant.details.copy(substrate = amendedSubstrate))
    assertEquals(editResult, EditOperationResult.Edited(updated))
    assertEquals(refs.updatedPlants.get(), Vector(expectedPlant))

  test("should amend an older repot without changing the plant"):
    val olderRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val newerRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val amended    = OperationDetails.Repot(substrate, maybeNote = none)
    val editResult = EditOperationResult.Edited(olderRepot.copy(details = amended))
    val refs       = Refs()
    val operations = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(olderRepot),
      getLatestRepotResult = () => GetLatestRepotResult.Read(newerRepot.some),
      updateOperationResult = editResult
    )

    assertEquals(operations.editOperation(olderRepot.id, amended), editResult)
    assertEquals(refs.updatedPlants.get(), Vector.empty)
    assertEquals(refs.restoredOperations.get(), Vector.empty)

  test("should restore an amended repot whenever plant synchronization cannot complete"):
    val existingRepot      = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val latestRepotFailure = RuntimeException("latest repot unavailable")
    val plantFailure       = RuntimeException("plant unavailable")
    val updateFailure      = RuntimeException("plant update failed")
    val isLatest           = () => GetLatestRepotResult.Read(existingRepot.some)
    val refs               = Refs()

    val historyResult = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getLatestRepotResult = () => GetLatestRepotResult.ReadFailed(latestRepotFailure),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    ).editOperation(existingRepot.id, repot)
    val plantResult = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getLatestRepotResult = isLatest,
      getPlantResult = GetPlantResult.ReadFailed(plantFailure),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    ).editOperation(existingRepot.id, repot)
    val updateResult = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getLatestRepotResult = isLatest,
      updateOperationResult = EditOperationResult.Edited(existingRepot),
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure)
    ).editOperation(existingRepot.id, repot)

    assertEquals(historyResult, EditOperationResult.EditFailed(latestRepotFailure))
    assertEquals(plantResult, EditOperationResult.EditFailed(plantFailure))
    assertEquals(updateResult, EditOperationResult.EditFailed(updateFailure))
    assertEquals(refs.updatedOperations.get(), Vector.fill(3)(existingRepot.id -> repot))
    assertEquals(refs.restoredOperations.get(), Vector.fill(3)(existingRepot))

  test("should surface a failed latest repot edit without updating the plant"):
    val existingRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val cause         = RuntimeException("store down")
    val refs          = Refs()
    val operations    = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(existingRepot),
      updateOperationResult = EditOperationResult.EditFailed(cause)
    )

    assertEquals(operations.editOperation(existingRepot.id, repot), EditOperationResult.EditFailed(cause))
    assertEquals(refs.updatedPlants.get(), Vector.empty[Plant])

  test("should report both failures when restoring an amended repot also fails"):
    val existingRepot  = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val updateFailure  = RuntimeException("update failed")
    val restoreFailure = RuntimeException("restore failed")
    val operations     = buildOperations(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getLatestRepotResult = () => GetLatestRepotResult.Read(existingRepot.some),
      updateOperationResult = EditOperationResult.Edited(existingRepot),
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure),
      restoreOperationResult = OperationCompensationResult.CompensationFailed(restoreFailure)
    )

    operations.editOperation(existingRepot.id, repot) match
      case EditOperationResult.EditFailed(reason) =>
        assertEquals(reason.getCause, updateFailure)
        assertEquals(reason.getSuppressed.toList, List(restoreFailure))
      case other => fail(s"expected EditFailed, got $other")

  test("should delete a non-latest repot"):
    val olderRepot = Operation(OperationId("o1"), plant.id, date, repot)
    val newerRepot = Operation(OperationId("o2"), plant.id, date.plusSeconds(60), repot)
    val refs       = Refs()
    val operations = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(olderRepot),
      getLatestRepotResult = () => GetLatestRepotResult.Read(newerRepot.some)
    )

    assertEquals(operations.deleteOperation(olderRepot.id), DeleteOperationResult.Deleted)
    assertEquals(refs.removedOperations.get(), Vector(olderRepot.id))

  test("should reject deleting a plant's latest repot"):
    val onlyRepot = Operation(OperationId("o1"), plant.id, date, repot)
    val refs      = Refs()
    val result    = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(onlyRepot),
      getLatestRepotResult = () => GetLatestRepotResult.Read(onlyRepot.some)
    ).deleteOperation(onlyRepot.id)

    assertEquals(result, DeleteOperationResult.CannotDeleteLatestRepot)
    assertEquals(refs.removedOperations.get(), Vector.empty)

  test("should allow deleting a repot when its plant has no latest repot on record"):
    val orphanRepot = Operation(OperationId("o1"), plant.id, date, repot)
    val refs        = Refs()
    val operations  = buildOperations(refs, getOperationResult = GetOperationResult.Read(orphanRepot))

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
      operationDateRangeResult: GetOperationDateRangeResult = GetOperationDateRangeResult.Read(OperationDateRange.Empty),
      getLatestRepotResult: () => GetLatestRepotResult = () => GetLatestRepotResult.Read(none),
      getOperationResult: GetOperationResult = GetOperationResult.Read(operation),
      addOperationResult: AddOperationResult = AddOperationResult.Logged(OperationId("id-1")),
      updateOperationResult: EditOperationResult = EditOperationResult.Edited(operation),
      removeOperationResult: OperationCompensationResult = OperationCompensationResult.Compensated,
      restoreOperationResult: OperationCompensationResult = OperationCompensationResult.Compensated,
      updatePlantResult: UpdatePlantResult = UpdatePlantResult.Updated,
      componentReadResult: GetSubstrateComponentsResult = GetSubstrateComponentsResult.Read(seededComponents),
      pesticideReadResult: GetPesticidesResult = GetPesticidesResult.Read(Vector.empty),
      nextId: () => String = () => "id-1"
  ) =
    val plantReads = AtomicInteger(0)
    val plantStore = new PlantStore:
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
        refs.requestedOperationWindows.updateAndGet(_ :+ (plant -> window)).pipe(_ => getOperationsResult)
      override def getOperationDateRange(plant: PlantId): GetOperationDateRangeResult =
        refs.requestedDateRanges.updateAndGet(_ :+ plant).pipe(_ => operationDateRangeResult)
      override def getLatestRepot(plant: PlantId): GetLatestRepotResult     = getLatestRepotResult()
      override def getOperation(operation: OperationId): GetOperationResult = getOperationResult
      override def addOperation(operation: Operation): AddOperationResult   =
        refs.recordedOperations.updateAndGet(_ :+ operation).pipe(_ => addOperationResult)
      override def updateOperation(operation: OperationId, details: OperationDetails): EditOperationResult =
        refs.updatedOperations.updateAndGet(_ :+ (operation -> details)).pipe(_ => updateOperationResult)
      override def removeOperation(operation: OperationId): OperationCompensationResult =
        refs.removedOperations.updateAndGet(_ :+ operation).pipe(_ => removeOperationResult)
      override def restoreOperation(operation: Operation): OperationCompensationResult =
        refs.restoredOperations.updateAndGet(_ :+ operation).pipe(_ => restoreOperationResult)
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
