package gardening.domain.journal

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.substrate.SubstrateComponentStore
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
  private val addedPesticide = Pesticide(
    PesticideId(UUID.fromString("20000000-0000-4000-8000-000000000001")),
    PesticideData(NomenclatureName("Neem"), PesticideType.Treatment, none)
  )

  test("should return operation history while preserving read failures"):
    val readFailure = RuntimeException("store down")

    val refs         = Refs(getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false)))
    val failedRefs   = Refs(getOperationsResult = GetOperationsResult.ReadFailed(readFailure))
    val result       = buildJournal(refs).getOperations(plant.id, firstPage)
    val failedResult = buildJournal(failedRefs).getOperations(plant.id, firstPage)

    val expected = GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false))
    assertEquals(result, expected)
    assertEquals(failedResult, GetOperationsResult.ReadFailed(readFailure))
    assertEquals(refs.requestedOperationWindows.get(), Vector(PlantId("p1") -> firstPage))

  test("should read the recorded date range independently of operation pages"):
    val firstRecorded = date
    val lastRecorded  = date.plusSeconds(60)
    val recorded      = OperationDateRange.Recorded(firstRecorded, lastRecorded)
    val failure       = RuntimeException("date read failed")

    val recordedRefs = Refs(operationDateRangeResult = GetOperationDateRangeResult.Read(recorded))
    val emptyRefs    = Refs(operationDateRangeResult = GetOperationDateRangeResult.Read(OperationDateRange.Empty))
    val missingRefs  = Refs(operationDateRangeResult = GetOperationDateRangeResult.PlantMissing)
    val failedRefs   = Refs(operationDateRangeResult = GetOperationDateRangeResult.ReadFailed(failure))

    val recordedResult = buildJournal(recordedRefs).getOperationDateRange(plant.id)
    val emptyResult    = buildJournal(emptyRefs).getOperationDateRange(plant.id)
    val missingResult  = buildJournal(missingRefs).getOperationDateRange(plant.id)
    val failedResult   = buildJournal(failedRefs).getOperationDateRange(plant.id)

    val expectedRecorded = GetOperationDateRangeResult.Read(recorded)
    val expectedEmpty    = GetOperationDateRangeResult.Read(OperationDateRange.Empty)
    assertEquals(recordedResult, expectedRecorded)
    assertEquals(emptyResult, expectedEmpty)
    assertEquals(missingResult, GetOperationDateRangeResult.PlantMissing)
    assertEquals(failedResult, GetOperationDateRangeResult.ReadFailed(failure))
    assertEquals(recordedRefs.requestedDateRanges.get(), Vector(plant.id))
    assertEquals(recordedRefs.requestedOperationWindows.get(), Vector.empty)

  test("should return current plants by status and surface read failures"):
    val failure    = RuntimeException("plant read failed")
    val refs       = Refs(getPlantsResult = GetPlantsResult.Read(Vector(plant)))
    val failedRefs = Refs(getPlantsResult = GetPlantsResult.ReadFailed(failure))

    val journal       = buildJournal(refs)
    val failedJournal = buildJournal(failedRefs)

    val activeResult   = journal.getPlants(PlantStatus.Active)
    val archivedResult = failedJournal.getPlants(PlantStatus.Archived)

    assertEquals(activeResult, GetPlantsResult.Read(Vector(plant)))
    assertEquals(refs.requestedStatuses.get(), Vector(PlantStatus.Active))
    assertEquals(archivedResult, GetPlantsResult.ReadFailed(failure))

  test("should read the archived count independently of plant lists"):
    val failure = RuntimeException("count unavailable")

    val refs       = Refs(archivedCountResult = ArchivedCountResult.Counted(4))
    val failedRefs = Refs(archivedCountResult = ArchivedCountResult.ReadFailed(failure))

    val countResult  = buildJournal(refs).getArchivedCount
    val failedResult = buildJournal(failedRefs).getArchivedCount

    assertEquals(countResult, ArchivedCountResult.Counted(4))
    assertEquals(failedResult, ArchivedCountResult.ReadFailed(failure))
    assertEquals(refs.requestedStatuses.get(), Vector.empty)

  test("should archive an active plant exactly once and preserve distinct failure results"):
    val failure = RuntimeException("write unavailable")

    val activeRefs   = Refs()
    val missingRefs  = Refs(archivePlantResult = ArchivePlantResult.PlantMissing)
    val archivedRefs = Refs(archivePlantResult = ArchivePlantResult.AlreadyArchived)
    val failedRefs   = Refs(archivePlantResult = ArchivePlantResult.ArchiveFailed(failure))

    val activeResult  = buildJournal(activeRefs).archivePlant(plant.id)
    val missingResult = buildJournal(missingRefs).archivePlant(plant.id)
    val repeatResult  = buildJournal(archivedRefs).archivePlant(plant.id)
    val failedResult  = buildJournal(failedRefs).archivePlant(plant.id)

    assertEquals(activeResult, ArchivePlantResult.Archived)
    assertEquals(missingResult, ArchivePlantResult.PlantMissing)
    assertEquals(repeatResult, ArchivePlantResult.AlreadyArchived)
    assertEquals(failedResult, ArchivePlantResult.ArchiveFailed(failure))
    assertEquals(activeRefs.archivedPlants.get(), Vector(plant.id))

  test("should assign pesticide identifiers and delegate nomenclature operations"):
    val pesticideId = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
    val refs        = Refs(nextId = () => pesticideId.value.toString)
    val journal     = buildJournal(refs)
    val pesticide   = PesticideData(NomenclatureName("Soap"), PesticideType.Treatment, none)

    assertEquals(journal.getPesticides, refs.pesticideReadResult)
    assertEquals(journal.addPesticide(pesticide), refs.pesticideAddResult)
    assertEquals(journal.editPesticide(pesticideId, pesticide), refs.pesticideEditResult)
    assertEquals(refs.addedPesticides.get(), Vector(Pesticide(pesticideId, pesticide)))
    assertEquals(refs.editedPesticides.get(), Vector(pesticideId -> pesticide))

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
    val validRefs = Refs(pesticideReadResult = CatalogReadResult.Read(pesticides))
    assertEquals(buildJournal(validRefs).logOperation(plant.id, date, selectedCare), LogOperationResult.Logged(OperationId("id-1")))

    val unknownPesticideRefs = Refs()
    buildJournal(unknownPesticideRefs).logOperation(plant.id, date, selectedCare) match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown pesticide ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(unknownPesticideRefs.recordedOperations.get(), Vector.empty)

    val readFailure          = RuntimeException("catalog unavailable")
    val unreadablePesticides = Refs(pesticideReadResult = CatalogReadResult.ReadFailed(readFailure))
    val unreadableResult     = buildJournal(unreadablePesticides).logOperation(plant.id, date, selectedCare)
    assertEquals(unreadableResult, LogOperationResult.LoggingFailed(readFailure))

    val unknownComponentRefs = Refs(componentReadResult = CatalogReadResult.Read(Vector.empty))
    val unknownComponents    = Substrate
      .of(List(SubstratePart(perliteId, 50), SubstratePart(lecaId, 50)))
      .getOrElse(fail("invalid test substrate"))
    val repotWithUnknownComponents = OperationDetails.Repot(unknownComponents, none)
    buildJournal(unknownComponentRefs).logOperation(plant.id, date, repotWithUnknownComponents) match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown substrate component ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(unknownComponentRefs.recordedOperations.get(), Vector.empty)

    val unreadableComponents = Refs(componentReadResult = CatalogReadResult.ReadFailed(readFailure))
    assertEquals(buildJournal(unreadableComponents).logOperation(plant.id, date, repot), LogOperationResult.LoggingFailed(readFailure))

    buildJournal(unknownPesticideRefs).editOperation(operation.id, selectedCare) match
      case EditOperationResult.EditFailed(reason) =>
        assert(reason.getMessage.contains(vertabId.value.toString))
        assert(reason.getMessage.contains(neemOilId.value.toString))
      case other => fail(s"expected EditFailed, got $other")
    assertEquals(unknownPesticideRefs.updatedOperations.get(), Vector.empty)

  test("should record the caller's operation instant"):
    val refs         = Refs()
    val selectedDate = date.plusSeconds(120)

    assertEquals(buildJournal(refs).logOperation(PlantId("p1"), selectedDate, care), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(refs.recordedOperations.get(), Vector(Operation(OperationId("id-1"), PlantId("p1"), selectedDate, care)))
    assertEquals(refs.updatedPlants.get(), Vector.empty)

  test("should reject new care and repot for an archived plant without recording history"):
    val archivedPlant = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))

    val refs        = Refs(getPlantResult = GetPlantResult.Read(archivedPlant))
    val journal     = buildJournal(refs)
    val careResult  = journal.logOperation(plant.id, date, care)
    val repotResult = journal.logOperation(plant.id, date, repot)

    assertEquals(careResult, LogOperationResult.PlantArchived)
    assertEquals(repotResult, LogOperationResult.PlantArchived)
    assertEquals(refs.recordedOperations.get(), Vector.empty)
    assertEquals(refs.updatedPlants.get(), Vector.empty)

  test("should preserve missing and unreadable plant failures when logging"):
    val readFailure = RuntimeException("plant unavailable")

    val missingRefs = Refs(getPlantResult = GetPlantResult.RecordMissing)
    val failedRefs  = Refs(getPlantResult = GetPlantResult.ReadFailed(readFailure))
    val missing     = buildJournal(missingRefs).logOperation(plant.id, date, care)
    val failed      = buildJournal(failedRefs).logOperation(plant.id, date, care)

    assertEquals(missing, LogOperationResult.PlantMissing)
    assertEquals(failed, LogOperationResult.LoggingFailed(readFailure))
    assertEquals(missingRefs.recordedOperations.get(), Vector.empty)
    assertEquals(failedRefs.recordedOperations.get(), Vector.empty)

  test("should update a plant after recording the latest repot"):
    val newSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val newRepot     = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val refs         = Refs()

    assertEquals(buildJournal(refs).logOperation(PlantId("p1"), date, newRepot), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(refs.recordedOperations.get(), Vector(Operation(OperationId("id-1"), PlantId("p1"), date, newRepot)))
    assertEquals(refs.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should leave the plant unchanged when a recorded repot loses the ordering tie-break"):
    val newerRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val logged     = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val refs       = Refs(
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(newerRepot, logged), hasNextPage = false)),
      addOperationResult = LogOperationResult.Logged(logged.id),
      nextId = () => logged.id.value
    )

    assertEquals(buildJournal(refs).logOperation(plant.id, date, repot), LogOperationResult.Logged(logged.id))
    assertEquals(refs.updatedPlants.get(), Vector.empty)
    assertEquals(refs.removedOperations.get(), Vector.empty)

  test("should preserve the current substrate when logging a historical repot"):
    val newerSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val olderSubstrate = Substrate.of(List(SubstratePart(sand3to5Id, share = 100))).getOrElse(fail("invalid test substrate"))
    val newerRepot     = Operation(OperationId("newer"), plant.id, date, OperationDetails.Repot(newerSubstrate, none))
    val olderRepot     = OperationDetails.Repot(olderSubstrate, none)
    val refs           = Refs(
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(newerRepot), hasNextPage = false))
    )

    assertEquals(buildJournal(refs).logOperation(plant.id, date.minusSeconds(60), olderRepot), LogOperationResult.Logged(OperationId("id-1")))
    assertEquals(refs.updatedPlants.get(), Vector.empty)

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
    val refs    = Refs(nextId = () => idGen.nextId())
    val journal = buildJournal(refs)

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
    assertEquals(refs.updatedPlants.get().map(_.details.substrate), Vector(firstSubstrate, secondSubstrate))

  test("should remove a recorded repot whenever its plant cannot reflect it"):
    val historyFailure    = RuntimeException("history failed")
    val readFailure       = RuntimeException("read failed")
    val updateFailure     = RuntimeException("update failed")
    val unreadableHistory = Refs(getOperationsResult = GetOperationsResult.ReadFailed(historyFailure))
    val missingPlant      = Refs(getPlantResultAfterLog = GetPlantResult.RecordMissing.some)
    val unreadable        = Refs(getPlantResultAfterLog = GetPlantResult.ReadFailed(readFailure).some)
    val notUpdated        = Refs(updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure))

    val historyResult = buildJournal(unreadableHistory).logOperation(plant.id, date, repot)
    val missingResult = buildJournal(missingPlant).logOperation(plant.id, date, repot)
    val readResult    = buildJournal(unreadable).logOperation(plant.id, date, repot)
    val updateResult  = buildJournal(notUpdated).logOperation(plant.id, date, repot)

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
    val refs          = Refs(
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure),
      removeOperationResult = OperationCompensationResult.CompensationFailed(removeFailure)
    )

    buildJournal(refs).logOperation(PlantId("p1"), date, repot) match
      case LogOperationResult.LoggingFailed(reason) =>
        assertEquals(reason.getCause, updateFailure)
        assertEquals(reason.getSuppressed.toList, List(removeFailure))
      case other => fail(s"expected LoggingFailed, got $other")

  test("should leave the plant unchanged when recording a repot fails"):
    val cause = RuntimeException("store down")
    val refs  = Refs(addOperationResult = LogOperationResult.LoggingFailed(cause))

    assertEquals(buildJournal(refs).logOperation(PlantId("p1"), date, repot), LogOperationResult.LoggingFailed(cause))
    assertEquals(refs.updatedPlants.get(), Vector.empty)
    assertEquals(refs.removedOperations.get(), Vector.empty)

  test("should update a plant after amending the latest repot"):
    val existingRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val tiedRepot     = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val newSubstrate  = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val amended       = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val editResult    = EditOperationResult.Edited(existingRepot.copy(details = amended))
    val refs          = Refs(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot, tiedRepot), hasNextPage = false)),
      updateOperationResult = editResult
    )

    assertEquals(buildJournal(refs).editOperation(existingRepot.id, amended), editResult)
    assertEquals(refs.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should allow editing an archived repot without changing its archived status"):
    val archivedPlant    = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))
    val existing         = Operation(OperationId("o2"), plant.id, date, repot)
    val amendedSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val amended          = OperationDetails.Repot(amendedSubstrate, maybeNote = none)
    val updated          = existing.copy(details = amended)

    val refs = Refs(
      getPlantResult = GetPlantResult.Read(archivedPlant),
      getOperationResult = GetOperationResult.Read(existing),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existing), hasNextPage = false)),
      updateOperationResult = EditOperationResult.Edited(updated)
    )
    val editResult = buildJournal(refs).editOperation(existing.id, amended)

    val expectedPlant = archivedPlant.copy(details = archivedPlant.details.copy(substrate = amendedSubstrate))
    assertEquals(editResult, EditOperationResult.Edited(updated))
    assertEquals(refs.updatedPlants.get(), Vector(expectedPlant))

  test("should update the plant after amending its latest repot despite newer care operations"):
    val existingRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val newerCare     = Vector.tabulate(10)(index =>
      Operation(OperationId(s"care-$index"), PlantId("p1"), date.plusSeconds(index.toLong + 1), care)
    )
    val newSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val amended      = OperationDetails.Repot(newSubstrate, maybeNote = none)
    val editResult   = EditOperationResult.Edited(existingRepot.copy(details = amended))
    val refs         = Refs(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(newerCare, hasNextPage = true)),
      nextOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)).some,
      updateOperationResult = editResult
    )

    assertEquals(buildJournal(refs).editOperation(existingRepot.id, amended), editResult)
    assertEquals(refs.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should amend an older repot without changing the plant"):
    val olderRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val newerRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val amended    = OperationDetails.Repot(substrate, maybeNote = none)
    val editResult = EditOperationResult.Edited(olderRepot.copy(details = amended))
    val refs       = Refs(
      getOperationResult = GetOperationResult.Read(olderRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(olderRepot, newerRepot), hasNextPage = false)),
      updateOperationResult = editResult
    )

    assertEquals(buildJournal(refs).editOperation(olderRepot.id, amended), editResult)
    assertEquals(refs.updatedPlants.get(), Vector.empty)
    assertEquals(refs.restoredOperations.get(), Vector.empty)

  test("should restore an amended repot whenever plant synchronization cannot complete"):
    val existingRepot     = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val historyFailure    = RuntimeException("history unavailable")
    val plantFailure      = RuntimeException("plant unavailable")
    val updateFailure     = RuntimeException("plant update failed")
    val unreadableHistory = Refs(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.ReadFailed(historyFailure),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val unreadablePlant = Refs(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      getPlantResult = GetPlantResult.ReadFailed(plantFailure),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val plantNotUpdated = Refs(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      updateOperationResult = EditOperationResult.Edited(existingRepot),
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure)
    )

    val historyResult = buildJournal(unreadableHistory).editOperation(existingRepot.id, repot)
    val plantResult   = buildJournal(unreadablePlant).editOperation(existingRepot.id, repot)
    val updateResult  = buildJournal(plantNotUpdated).editOperation(existingRepot.id, repot)

    assertEquals(historyResult, EditOperationResult.EditFailed(historyFailure))
    assertEquals(plantResult, EditOperationResult.EditFailed(plantFailure))
    assertEquals(updateResult, EditOperationResult.EditFailed(updateFailure))
    List(unreadableHistory, unreadablePlant, plantNotUpdated).foreach: refs =>
      assertEquals(refs.updatedOperations.get(), Vector(existingRepot.id -> repot))
      assertEquals(refs.restoredOperations.get(), Vector(existingRepot))

  test("should surface a failed latest repot edit without updating the plant"):
    val existingRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val cause         = RuntimeException("store down")
    val refs          = Refs(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      updateOperationResult = EditOperationResult.EditFailed(cause)
    )

    assertEquals(buildJournal(refs).editOperation(existingRepot.id, repot), EditOperationResult.EditFailed(cause))
    assertEquals(refs.updatedPlants.get(), Vector.empty[Plant])

  test("should report both failures when restoring an amended repot also fails"):
    val existingRepot  = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val updateFailure  = RuntimeException("update failed")
    val restoreFailure = RuntimeException("restore failed")
    val refs           = Refs(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      updateOperationResult = EditOperationResult.Edited(existingRepot),
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure),
      restoreOperationResult = OperationCompensationResult.CompensationFailed(restoreFailure)
    )

    buildJournal(refs).editOperation(existingRepot.id, repot) match
      case EditOperationResult.EditFailed(reason) =>
        assertEquals(reason.getCause, updateFailure)
        assertEquals(reason.getSuppressed.toList, List(restoreFailure))
      case other => fail(s"expected EditFailed, got $other")

  test("should amend care without changing the plant"):
    val refs = Refs()

    assertEquals(buildJournal(refs).editOperation(operation.id, care), EditOperationResult.Edited(operation))
    assertEquals(refs.updatedOperations.get(), Vector(operation.id -> care))
    assertEquals(refs.updatedPlants.get(), Vector.empty)

  test("should reject invalid edit requests before writing"):
    val readFailure  = RuntimeException("store down")
    val typeMismatch = Refs()
    val missing      = Refs(getOperationResult = GetOperationResult.RecordMissing)
    val unreadable   = Refs(getOperationResult = GetOperationResult.ReadFailed(readFailure))

    assertEquals(buildJournal(typeMismatch).editOperation(operation.id, repot), EditOperationResult.OperationTypeMismatch)
    assertEquals(buildJournal(missing).editOperation(OperationId("nope"), care), EditOperationResult.OperationMissing)
    assertEquals(buildJournal(unreadable).editOperation(operation.id, care), EditOperationResult.EditFailed(readFailure))
    List(typeMismatch, missing, unreadable).foreach: refs =>
      assertEquals(refs.updatedOperations.get(), Vector.empty)

  test("should surface an edit failure from the store"):
    val cause = RuntimeException("store down")
    val refs  = Refs(updateOperationResult = EditOperationResult.EditFailed(cause))

    assertEquals(buildJournal(refs).editOperation(operation.id, care), EditOperationResult.EditFailed(cause))

  final private case class Refs(
      getPlantsResult: GetPlantsResult = GetPlantsResult.Read(Vector.empty),
      archivedCountResult: ArchivedCountResult = ArchivedCountResult.Counted(0),
      archivePlantResult: ArchivePlantResult = ArchivePlantResult.Archived,
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
      componentReadResult: CatalogReadResult[SubstrateComponent] = CatalogReadResult.Read(seededComponents),
      pesticideReadResult: CatalogReadResult[Pesticide] = CatalogReadResult.Read(Vector.empty),
      pesticideAddResult: CatalogAddResult[Pesticide] = CatalogAddResult.Added(addedPesticide),
      pesticideEditResult: CatalogEditResult[Pesticide] = CatalogEditResult.RecordMissing,
      nextId: () => String = () => "id-1"
  ):
    val operationReads: AtomicInteger                                                  = AtomicInteger(0)
    val plantReads: AtomicInteger                                                      = AtomicInteger(0)
    val requestedStatuses: AtomicReference[Vector[PlantStatus]]                        = AtomicReference(Vector.empty)
    val archivedPlants: AtomicReference[Vector[PlantId]]                               = AtomicReference(Vector.empty)
    val requestedOperationWindows: AtomicReference[Vector[(PlantId, OperationWindow)]] = AtomicReference(Vector.empty)
    val requestedDateRanges: AtomicReference[Vector[PlantId]]                          = AtomicReference(Vector.empty)
    val recordedOperations: AtomicReference[Vector[Operation]]                         = new AtomicReference(Vector.empty)
    val updatedOperations: AtomicReference[Vector[(OperationId, OperationDetails)]]    = new AtomicReference(Vector.empty)
    val removedOperations: AtomicReference[Vector[OperationId]]                        = new AtomicReference(Vector.empty)
    val restoredOperations: AtomicReference[Vector[Operation]]                         = new AtomicReference(Vector.empty)
    val updatedPlants: AtomicReference[Vector[Plant]]                                  = new AtomicReference(Vector.empty)
    val addedPesticides: AtomicReference[Vector[Pesticide]]                            = new AtomicReference(Vector.empty)
    val editedPesticides: AtomicReference[Vector[(PesticideId, PesticideData)]]        = new AtomicReference(Vector.empty)

  private def buildJournal(refs: Refs) =
    val store = new PlantJournalStore:
      override def getPlants(status: PlantStatus): GetPlantsResult =
        refs.requestedStatuses.updateAndGet(_ :+ status)
        refs.getPlantsResult
      override def getArchivedCount: ArchivedCountResult         = refs.archivedCountResult
      override def archivePlant(id: PlantId): ArchivePlantResult =
        refs.archivedPlants.updateAndGet(_ :+ id).pipe(_ => refs.archivePlantResult)
      override def getPlant(id: PlantId): GetPlantResult =
        if refs.plantReads.getAndIncrement().equals(0) then refs.getPlantResult
        else refs.getPlantResultAfterLog.getOrElse(refs.getPlantResult)
      override def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult =
        refs.requestedOperationWindows.updateAndGet(_ :+ (plantId -> window))
        if refs.operationReads.getAndIncrement().equals(0) then refs.getOperationsResult
        else refs.nextOperationsResult.getOrElse(refs.getOperationsResult)
      override def getOperationDateRange(plantId: PlantId): GetOperationDateRangeResult =
        refs.requestedDateRanges.updateAndGet(_ :+ plantId).pipe(_ => refs.operationDateRangeResult)
      override def getOperation(id: OperationId): GetOperationResult      = refs.getOperationResult
      override def addOperation(operation: Operation): LogOperationResult =
        refs.recordedOperations.updateAndGet(_ :+ operation).pipe(_ => refs.addOperationResult)
      override def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult =
        refs.updatedOperations.updateAndGet(_ :+ (id -> details)).pipe(_ => refs.updateOperationResult)
      override def removeOperation(id: OperationId): OperationCompensationResult =
        refs.removedOperations.updateAndGet(_ :+ id).pipe(_ => refs.removeOperationResult)
      override def restoreOperation(operation: Operation): OperationCompensationResult =
        refs.restoredOperations.updateAndGet(_ :+ operation).pipe(_ => refs.restoreOperationResult)
      override def updatePlant(plant: Plant): UpdatePlantResult =
        refs.updatedPlants.updateAndGet(_ :+ plant).pipe(_ => refs.updatePlantResult)
      override def getPesticides: CatalogReadResult[Pesticide]                     = refs.pesticideReadResult
      override def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide] =
        refs.addedPesticides.updateAndGet(_ :+ pesticide).pipe(_ => refs.pesticideAddResult)
      override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
        refs.editedPesticides.updateAndGet(_ :+ (id -> data)).pipe(_ => refs.pesticideEditResult)
    val substrateStore = new SubstrateComponentStore:
      override def getSubstrateComponents: CatalogReadResult[SubstrateComponent]                              = refs.componentReadResult
      override def addSubstrateComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent] =
        fail("journal must not write substrate components")
      override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
        fail("journal must not edit substrate components")
    PlantJournal.make(using store, substrateStore, () => refs.nextId())
