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

  test("should validate catalog references before writing operations"):
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

    val unknownPesticideRefs = Refs()
    buildOperations(unknownPesticideRefs).logOperation(plant.id, date, selectedCare) match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown pesticide ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(unknownPesticideRefs.recordedOperations.get(), Vector.empty)

    val archivedPesticides    = pesticides.map(_.copy(status = PesticideStatus.Archived))
    val archivedPesticideRefs = Refs()
    val archivedRead          = GetPesticidesResult.Read(archivedPesticides)
    buildOperations(archivedPesticideRefs, pesticideReadResult = archivedRead).logOperation(plant.id, date, selectedCare) match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown pesticide ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(archivedPesticideRefs.recordedOperations.get(), Vector.empty)

    val readFailure      = RuntimeException("catalog unavailable")
    val unreadableResult =
      buildOperations(pesticideReadResult = GetPesticidesResult.ReadFailed(readFailure)).logOperation(plant.id, date, selectedCare)
    assertEquals(unreadableResult, LogOperationResult.LoggingFailed(readFailure))

    val unknownComponents = Substrate
      .of(List(SubstratePart(perliteId, 50), SubstratePart(lecaId, 50)))
      .getOrElse(fail("invalid test substrate"))
    val repotWithUnknownComponents = OperationDetails.Repot(unknownComponents, none)
    val unknownComponentRefs       = Refs()
    buildOperations(
      unknownComponentRefs,
      componentReadResult = GetSubstrateComponentsResult.Read(Vector.empty)
    ).logOperation(plant.id, date, repotWithUnknownComponents) match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown substrate component ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(unknownComponentRefs.recordedOperations.get(), Vector.empty)

    val archivedPerlite =
      SubstrateComponent(perliteId, SubstrateComponentData(SubstrateComponentName(perliteId.value.toString), none), SubstrateComponentStatus.Archived)
    val archivedComponents    = archivedPerlite +: seededComponents.drop(1)
    val archivedComponentRefs = Refs()
    val archivedComponentRead = GetSubstrateComponentsResult.Read(archivedComponents)
    buildOperations(archivedComponentRefs, componentReadResult = archivedComponentRead).logOperation(plant.id, date, repot) match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown substrate component ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(archivedComponentRefs.recordedOperations.get(), Vector.empty)

    assertEquals(
      buildOperations(componentReadResult = GetSubstrateComponentsResult.ReadFailed(readFailure)).logOperation(plant.id, date, repot),
      LogOperationResult.LoggingFailed(readFailure)
    )

    buildOperations(unknownPesticideRefs).editOperation(operation.id, selectedCare) match
      case EditOperationResult.EditFailed(reason) =>
        assert(reason.getMessage.contains(vertabId.value.toString))
        assert(reason.getMessage.contains(neemOilId.value.toString))
      case other => fail(s"expected EditFailed, got $other")
    assertEquals(unknownPesticideRefs.updatedOperations.get(), Vector.empty)

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

  test("should preserve an unexpected addOperation outcome when logging"):
    assertEquals(
      buildOperations(addOperationResult = LogOperationResult.PlantMissing).logOperation(plant.id, date, care),
      LogOperationResult.PlantMissing
    )

  test("should preserve missing and unreadable plant failures when logging"):
    val readFailure = RuntimeException("plant unavailable")

    val missingRefs = Refs()
    val failedRefs  = Refs()
    val missing     = buildOperations(missingRefs, getPlantResult = GetPlantResult.RecordMissing).logOperation(plant.id, date, care)
    val failed      = buildOperations(failedRefs, getPlantResult = GetPlantResult.ReadFailed(readFailure)).logOperation(plant.id, date, care)

    assertEquals(missing, LogOperationResult.PlantMissing)
    assertEquals(failed, LogOperationResult.LoggingFailed(readFailure))
    assertEquals(missingRefs.recordedOperations.get(), Vector.empty)
    assertEquals(failedRefs.recordedOperations.get(), Vector.empty)

  test("should amend care without changing the plant"):
    val refs = Refs()

    assertEquals(buildOperations(refs).editOperation(operation.id, care), EditOperationResult.Edited(operation))
    assertEquals(refs.updatedOperations.get(), Vector(operation.id -> care))
    assertEquals(refs.updatedPlants.get(), Vector.empty)

  test("should reject invalid edit requests before writing"):
    val readFailure  = RuntimeException("store down")
    val typeMismatch = Refs()
    val missing      = Refs()
    val unreadable   = Refs()

    assertEquals(buildOperations(typeMismatch).editOperation(operation.id, repot), EditOperationResult.OperationTypeMismatch)
    assertEquals(
      buildOperations(missing, getOperationResult = GetOperationResult.RecordMissing).editOperation(OperationId("nope"), care),
      EditOperationResult.OperationMissing
    )
    assertEquals(
      buildOperations(unreadable, getOperationResult = GetOperationResult.ReadFailed(readFailure)).editOperation(operation.id, care),
      EditOperationResult.EditFailed(readFailure)
    )
    List(typeMismatch, missing, unreadable).foreach: refs =>
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
    val readFailure    = RuntimeException("store down")
    val historyFailure = RuntimeException("history unavailable")
    val removeFailure  = RuntimeException("remove failed")
    val existingRepot  = Operation(OperationId("o1"), plant.id, date, repot)

    val missingResult    = buildOperations(getOperationResult = GetOperationResult.RecordMissing).deleteOperation(OperationId("nope"))
    val unreadableResult =
      buildOperations(getOperationResult = GetOperationResult.ReadFailed(readFailure)).deleteOperation(operation.id)
    val historyFailedResult = buildOperations(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.ReadFailed(historyFailure)
    ).deleteOperation(existingRepot.id)
    val writeFailedResult = buildOperations(
      getOperationResult = GetOperationResult.Read(operation),
      removeOperationResult = OperationCompensationResult.CompensationFailed(removeFailure)
    ).deleteOperation(operation.id)

    assertEquals(missingResult, DeleteOperationResult.OperationMissing)
    assertEquals(unreadableResult, DeleteOperationResult.DeleteFailed(readFailure))
    assertEquals(historyFailedResult, DeleteOperationResult.DeleteFailed(historyFailure))
    assertEquals(writeFailedResult, DeleteOperationResult.DeleteFailed(removeFailure))

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

  test("should update a plant after amending the latest repot"):
    val existingRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val tiedRepot     = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val newSubstrate  = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val amended       = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val editResult    = EditOperationResult.Edited(existingRepot.copy(details = amended))
    val refs          = Refs()
    val operations    = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot, tiedRepot), hasNextPage = false)),
      updateOperationResult = editResult
    )

    assertEquals(operations.editOperation(existingRepot.id, amended), editResult)
    assertEquals(refs.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should allow editing an archived repot without changing its archived status"):
    val archivedPlant    = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))
    val existing         = Operation(OperationId("o2"), plant.id, date, repot)
    val amendedSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val amended          = OperationDetails.Repot(amendedSubstrate, maybeNote = none)
    val updated          = existing.copy(details = amended)

    val refs       = Refs()
    val operations = buildOperations(
      refs,
      getPlantResult = GetPlantResult.Read(archivedPlant),
      getOperationResult = GetOperationResult.Read(existing),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existing), hasNextPage = false)),
      updateOperationResult = EditOperationResult.Edited(updated)
    )
    val editResult = operations.editOperation(existing.id, amended)

    val expectedPlant = archivedPlant.copy(details = archivedPlant.details.copy(substrate = amendedSubstrate))
    assertEquals(editResult, EditOperationResult.Edited(updated))
    assertEquals(refs.updatedPlants.get(), Vector(expectedPlant))

  test("should update the plant after amending its latest repot despite newer care operations"):
    val existingRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val newerCare     = Vector.tabulate(10)(index =>
      Operation(
        OperationId(s"care-$index"),
        PlantId("p1"),
        date.plusSeconds(index.toLong + 1),
        OperationDetails.Care(Set(ActionType.Watered), Set.empty, MoistureLevel.Wet, Note("dry").some)
      )
    )
    val newSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val amended      = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val editResult   = EditOperationResult.Edited(existingRepot.copy(details = amended))
    val refs         = Refs()
    val operations   = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(newerCare, hasNextPage = true)),
      nextOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)).some,
      updateOperationResult = editResult
    )

    assertEquals(operations.editOperation(existingRepot.id, amended), editResult)
    assertEquals(refs.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should amend an older repot without changing the plant"):
    val olderRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val newerRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val amended    = OperationDetails.Repot(substrate, maybeNote = none)
    val editResult = EditOperationResult.Edited(olderRepot.copy(details = amended))
    val refs       = Refs()
    val operations = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(olderRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(newerRepot, olderRepot), hasNextPage = false)),
      updateOperationResult = editResult
    )

    assertEquals(operations.editOperation(olderRepot.id, amended), editResult)
    assertEquals(refs.updatedPlants.get(), Vector.empty)
    assertEquals(refs.restoredOperations.get(), Vector.empty)

  test("should restore an amended repot whenever plant synchronization cannot complete"):
    val existingRepot     = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val historyFailure    = RuntimeException("history unavailable")
    val plantFailure      = RuntimeException("plant unavailable")
    val updateFailure     = RuntimeException("plant update failed")
    val unreadableHistory = Refs()
    val historyOperations = buildOperations(
      unreadableHistory,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.ReadFailed(historyFailure),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val unreadablePlant = Refs()
    val plantOperations = buildOperations(
      unreadablePlant,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      getPlantResult = GetPlantResult.ReadFailed(plantFailure),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val plantNotUpdated  = Refs()
    val updateOperations = buildOperations(
      plantNotUpdated,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      updateOperationResult = EditOperationResult.Edited(existingRepot),
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure)
    )

    val historyResult = historyOperations.editOperation(existingRepot.id, repot)
    val plantResult   = plantOperations.editOperation(existingRepot.id, repot)
    val updateResult  = updateOperations.editOperation(existingRepot.id, repot)

    assertEquals(historyResult, EditOperationResult.EditFailed(historyFailure))
    assertEquals(plantResult, EditOperationResult.EditFailed(plantFailure))
    assertEquals(updateResult, EditOperationResult.EditFailed(updateFailure))
    List(unreadableHistory, unreadablePlant, plantNotUpdated).foreach: refs =>
      assertEquals(refs.updatedOperations.get(), Vector(existingRepot.id -> repot))
      assertEquals(refs.restoredOperations.get(), Vector(existingRepot))

  test("should surface a failed latest repot edit without updating the plant"):
    val existingRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val cause         = RuntimeException("store down")
    val refs          = Refs()
    val operations    = buildOperations(
      refs,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
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
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
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
      operationDateRangeResult: GetOperationDateRangeResult = GetOperationDateRangeResult.Read(OperationDateRange.Empty),
      nextOperationsResult: Option[GetOperationsResult] = none,
      getOperationResult: GetOperationResult = GetOperationResult.Read(operation),
      addOperationResult: LogOperationResult = LogOperationResult.Logged(OperationId("id-1")),
      updateOperationResult: EditOperationResult = EditOperationResult.Edited(operation),
      removeOperationResult: OperationCompensationResult = OperationCompensationResult.Compensated,
      restoreOperationResult: OperationCompensationResult = OperationCompensationResult.Compensated,
      updatePlantResult: UpdatePlantResult = UpdatePlantResult.Updated,
      componentReadResult: GetSubstrateComponentsResult = GetSubstrateComponentsResult.Read(seededComponents),
      pesticideReadResult: GetPesticidesResult = GetPesticidesResult.Read(Vector.empty),
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
        refs.requestedDateRanges.updateAndGet(_ :+ plant).pipe(_ => operationDateRangeResult)
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
