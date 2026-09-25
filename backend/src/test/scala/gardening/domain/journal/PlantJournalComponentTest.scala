package gardening.domain.journal

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.PesticideStore
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

  test("should create an active plant with initial substrate independently of operations"):
    val refs    = Refs()
    val journal = buildJournal(refs)

    val result = journal.createPlant(plant.details.species, plant.details.maybeNickname, plant.details.location, substrate)

    val expectedPlant = plant.copy(id = PlantId("id-1"))
    assertEquals(result, CreatePlantResult.Created(expectedPlant))
    assertEquals(refs.createdPlants.get(), Vector(expectedPlant))
    assertEquals(refs.recordedOperations.get(), Vector.empty)

  test("should distinguish unknown substrate components, catalog reads, and failed writes"):
    val failure        = RuntimeException("unavailable")
    val missingRefs    = Refs()
    val readFailedRefs = Refs()

    val missing = buildJournal(
      missingRefs,
      componentReadResult = CatalogReadResult.Read(Vector.empty)
    ).createPlant(plant.details.species, none, plant.details.location, substrate)
    val readFailed = buildJournal(
      readFailedRefs,
      componentReadResult = CatalogReadResult.ReadFailed(failure)
    ).createPlant(plant.details.species, none, plant.details.location, substrate)
    val writeFailed =
      buildJournal(addPlantResult = AddPlantResult.AddFailed(failure)).createPlant(plant.details.species, none, plant.details.location, substrate)

    assertEquals(missing, CreatePlantResult.UnknownComponent)
    assertEquals(readFailed, CreatePlantResult.CatalogReadFailed(failure))
    assertEquals(writeFailed, CreatePlantResult.CreateFailed(failure))
    assertEquals(missingRefs.createdPlants.get(), Vector.empty)
    assertEquals(readFailedRefs.createdPlants.get(), Vector.empty)

  test("should return operation history while preserving read failures"):
    val readFailure = RuntimeException("store down")

    val refs   = Refs()
    val result = buildJournal(
      refs,
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(operation), hasNextPage = false))
    ).getOperations(plant.id, firstPage)
    val failedResult = buildJournal(getOperationsResult = GetOperationsResult.ReadFailed(readFailure)).getOperations(plant.id, firstPage)

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
      buildJournal(recordedRefs, operationDateRangeResult = GetOperationDateRangeResult.Read(recorded)).getOperationDateRange(plant.id)
    val emptyResult   = buildJournal().getOperationDateRange(plant.id)
    val missingResult = buildJournal(operationDateRangeResult = GetOperationDateRangeResult.PlantMissing).getOperationDateRange(plant.id)
    val failedResult  = buildJournal(operationDateRangeResult = GetOperationDateRangeResult.ReadFailed(failure)).getOperationDateRange(plant.id)
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

  test("should return current plants by status and surface read failures"):
    val failure = RuntimeException("plant read failed")
    val refs    = Refs()

    val journal       = buildJournal(refs, getPlantsResult = GetPlantsResult.Read(Vector(plant)))
    val failedJournal = buildJournal(getPlantsResult = GetPlantsResult.ReadFailed(failure))

    val activeResult   = journal.getPlants(PlantStatus.Active)
    val archivedResult = failedJournal.getPlants(PlantStatus.Archived)

    assertEquals(activeResult, GetPlantsResult.Read(Vector(plant)))
    assertEquals(refs.requestedStatuses.get(), Vector(PlantStatus.Active))
    assertEquals(archivedResult, GetPlantsResult.ReadFailed(failure))

  test("should read a non-negative archived count independently of plant lists"):
    val failure = RuntimeException("count unavailable")

    val refs = Refs()

    val countResult  = buildJournal(refs, archivedCountResult = ArchivedCountResult.Counted(4)).getArchivedCount
    val failedResult = buildJournal(archivedCountResult = ArchivedCountResult.ReadFailed(failure)).getArchivedCount
    val invalidCount = intercept[IllegalArgumentException](ArchivedCountResult.Counted(-1L))

    assertEquals(countResult, ArchivedCountResult.Counted(4))
    assertEquals(failedResult, ArchivedCountResult.ReadFailed(failure))
    assertEquals(invalidCount.getMessage, "requirement failed: archived plant count must be non-negative")
    assertEquals(refs.requestedStatuses.get(), Vector.empty)

  test("should archive an active plant by editing its status alone, without reading the substrate catalog"):
    val archivedPlant = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))
    val refs          = Refs()

    val result = buildJournal(refs).editPlant(plant.id, _.copy(status = PlantStatus.Archived))

    assertEquals(result, EditPlantResult.Edited(archivedPlant))
    assertEquals(refs.updatedPlants.get(), Vector(archivedPlant))
    assertEquals(refs.catalogReads.get(), 0)
    assertEquals(refs.recordedOperations.get(), Vector.empty)

  test("should edit an active plant's details independently of operations"):
    val newSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val refs         = Refs()

    val result = buildJournal(refs).editPlant(
      plant.id,
      _.copy(species = Species("Monstera deliciosa"), maybeNickname = none, location = Location("Living room"), substrate = newSubstrate)
    )

    val expectedPlant = plant.copy(details =
      plant.details.copy(species = Species("Monstera deliciosa"), maybeNickname = none, location = Location("Living room"), substrate = newSubstrate)
    )
    assertEquals(result, EditPlantResult.Edited(expectedPlant))
    assertEquals(refs.updatedPlants.get(), Vector(expectedPlant))
    assertEquals(refs.recordedOperations.get(), Vector.empty)

  test("should reject editing an archived plant regardless of the requested change, and preserve distinct failure results"):
    val failure       = RuntimeException("write unavailable")
    val archivedPlant = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))
    val readFailure   = RuntimeException("read unavailable")

    val archivedRefs                     = Refs()
    val archivedWithUnknownComponentRefs = Refs()
    val missingRefs                      = Refs()
    val unreadable                       = Refs()

    val archivedAgainResult = buildJournal(archivedRefs, getPlantResult = GetPlantResult.Read(archivedPlant))
      .editPlant(plant.id, _.copy(status = PlantStatus.Archived))
    val archivedWithUnknownComponentResult = buildJournal(
      archivedWithUnknownComponentRefs,
      getPlantResult = GetPlantResult.Read(archivedPlant),
      componentReadResult = CatalogReadResult.Read(Vector.empty)
    ).editPlant(plant.id, _.copy(location = Location("Kitchen")))
    val missingResult = buildJournal(missingRefs, getPlantResult = GetPlantResult.RecordMissing)
      .editPlant(plant.id, _.copy(location = Location("Kitchen")))
    val readResult = buildJournal(unreadable, getPlantResult = GetPlantResult.ReadFailed(readFailure))
      .editPlant(plant.id, _.copy(location = Location("Kitchen")))
    val writeFailedResult = buildJournal(updatePlantResult = UpdatePlantResult.UpdateFailed(failure))
      .editPlant(plant.id, _.copy(location = Location("Kitchen")))

    assertEquals(archivedAgainResult, EditPlantResult.PlantArchived)
    assertEquals(archivedWithUnknownComponentResult, EditPlantResult.PlantArchived)
    assertEquals(missingResult, EditPlantResult.PlantMissing)
    assertEquals(readResult, EditPlantResult.EditFailed(readFailure))
    assertEquals(writeFailedResult, EditPlantResult.EditFailed(failure))
    assertEquals(archivedRefs.updatedPlants.get(), Vector.empty)
    assertEquals(missingRefs.updatedPlants.get(), Vector.empty)
    assertEquals(unreadable.updatedPlants.get(), Vector.empty)

  test("should distinguish unknown substrate components and catalog read failures when changing substrate"):
    val newSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val failure      = RuntimeException("unavailable")

    val unknownRefs    = Refs()
    val readFailedRefs = Refs()

    val unknownResult = buildJournal(unknownRefs, componentReadResult = CatalogReadResult.Read(Vector.empty))
      .editPlant(plant.id, _.copy(substrate = newSubstrate))
    val readFailedResult = buildJournal(readFailedRefs, componentReadResult = CatalogReadResult.ReadFailed(failure))
      .editPlant(plant.id, _.copy(substrate = newSubstrate))

    assertEquals(unknownResult, EditPlantResult.UnknownComponent)
    assertEquals(readFailedResult, EditPlantResult.CatalogReadFailed(failure))
    assertEquals(unknownRefs.updatedPlants.get(), Vector.empty)
    assertEquals(readFailedRefs.updatedPlants.get(), Vector.empty)

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
    assertEquals(
      buildJournal(pesticideReadResult = CatalogReadResult.Read(pesticides)).logOperation(plant.id, date, selectedCare),
      LogOperationResult.Logged(OperationId("id-1"))
    )

    val unknownPesticideRefs = Refs()
    buildJournal(unknownPesticideRefs).logOperation(plant.id, date, selectedCare) match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown pesticide ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(unknownPesticideRefs.recordedOperations.get(), Vector.empty)

    val readFailure      = RuntimeException("catalog unavailable")
    val unreadableResult = buildJournal(pesticideReadResult = CatalogReadResult.ReadFailed(readFailure)).logOperation(plant.id, date, selectedCare)
    assertEquals(unreadableResult, LogOperationResult.LoggingFailed(readFailure))

    val unknownComponents = Substrate
      .of(List(SubstratePart(perliteId, 50), SubstratePart(lecaId, 50)))
      .getOrElse(fail("invalid test substrate"))
    val repotWithUnknownComponents = OperationDetails.Repot(unknownComponents, none)
    val unknownComponentRefs       = Refs()
    buildJournal(
      unknownComponentRefs,
      componentReadResult = CatalogReadResult.Read(Vector.empty)
    ).logOperation(plant.id, date, repotWithUnknownComponents) match
      case LogOperationResult.LoggingFailed(reason) => assert(reason.getMessage.contains("unknown substrate component ids"))
      case other                                    => fail(s"expected LoggingFailed, got $other")
    assertEquals(unknownComponentRefs.recordedOperations.get(), Vector.empty)

    assertEquals(
      buildJournal(componentReadResult = CatalogReadResult.ReadFailed(readFailure)).logOperation(plant.id, date, repot),
      LogOperationResult.LoggingFailed(readFailure)
    )

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

    val refs        = Refs()
    val journal     = buildJournal(refs, getPlantResult = GetPlantResult.Read(archivedPlant))
    val careResult  = journal.logOperation(plant.id, date, care)
    val repotResult = journal.logOperation(plant.id, date, repot)

    assertEquals(careResult, LogOperationResult.PlantArchived)
    assertEquals(repotResult, LogOperationResult.PlantArchived)
    assertEquals(refs.recordedOperations.get(), Vector.empty)
    assertEquals(refs.updatedPlants.get(), Vector.empty)

  test("should preserve missing and unreadable plant failures when logging"):
    val readFailure = RuntimeException("plant unavailable")

    val missingRefs = Refs()
    val failedRefs  = Refs()
    val missing     = buildJournal(missingRefs, getPlantResult = GetPlantResult.RecordMissing).logOperation(plant.id, date, care)
    val failed      = buildJournal(failedRefs, getPlantResult = GetPlantResult.ReadFailed(readFailure)).logOperation(plant.id, date, care)

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
    val refs       = Refs()

    val journal = buildJournal(
      refs,
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(newerRepot, logged), hasNextPage = false)),
      addOperationResult = LogOperationResult.Logged(logged.id),
      nextId = () => logged.id.value
    )
    assertEquals(journal.logOperation(plant.id, date, repot), LogOperationResult.Logged(logged.id))
    assertEquals(refs.updatedPlants.get(), Vector.empty)
    assertEquals(refs.removedOperations.get(), Vector.empty)

  test("should preserve the current substrate when logging a historical repot"):
    val newerSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val olderSubstrate = Substrate.of(List(SubstratePart(sand3to5Id, share = 100))).getOrElse(fail("invalid test substrate"))
    val newerRepot     = Operation(OperationId("newer"), plant.id, date, OperationDetails.Repot(newerSubstrate, none))
    val olderRepot     = OperationDetails.Repot(olderSubstrate, none)
    val refs           = Refs()

    val journal = buildJournal(refs, getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(newerRepot), hasNextPage = false)))
    assertEquals(journal.logOperation(plant.id, date.minusSeconds(60), olderRepot), LogOperationResult.Logged(OperationId("id-1")))
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
    val refs    = Refs()
    val journal = buildJournal(refs, nextId = () => idGen.nextId())

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
    val unreadableHistory = Refs()
    val missingPlant      = Refs()
    val unreadable        = Refs()
    val notUpdated        = Refs()

    val historyResult =
      buildJournal(unreadableHistory, getOperationsResult = GetOperationsResult.ReadFailed(historyFailure)).logOperation(plant.id, date, repot)
    val missingResult = buildJournal(missingPlant, getPlantResultAfterLog = GetPlantResult.RecordMissing.some).logOperation(plant.id, date, repot)
    val readResult    =
      buildJournal(unreadable, getPlantResultAfterLog = GetPlantResult.ReadFailed(readFailure).some).logOperation(plant.id, date, repot)
    val updateResult = buildJournal(notUpdated, updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure)).logOperation(plant.id, date, repot)

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
    val journal       = buildJournal(
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure),
      removeOperationResult = OperationCompensationResult.CompensationFailed(removeFailure)
    )

    journal.logOperation(PlantId("p1"), date, repot) match
      case LogOperationResult.LoggingFailed(reason) =>
        assertEquals(reason.getCause, updateFailure)
        assertEquals(reason.getSuppressed.toList, List(removeFailure))
      case other => fail(s"expected LoggingFailed, got $other")

  test("should leave the plant unchanged when recording a repot fails"):
    val cause = RuntimeException("store down")
    val refs  = Refs()

    assertEquals(
      buildJournal(refs, addOperationResult = LogOperationResult.LoggingFailed(cause)).logOperation(PlantId("p1"), date, repot),
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
    val journal       = buildJournal(
      refs,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot, tiedRepot), hasNextPage = false)),
      updateOperationResult = editResult
    )

    assertEquals(journal.editOperation(existingRepot.id, amended), editResult)
    assertEquals(refs.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should allow editing an archived repot without changing its archived status"):
    val archivedPlant    = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))
    val existing         = Operation(OperationId("o2"), plant.id, date, repot)
    val amendedSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val amended          = OperationDetails.Repot(amendedSubstrate, maybeNote = none)
    val updated          = existing.copy(details = amended)

    val refs    = Refs()
    val journal = buildJournal(
      refs,
      getPlantResult = GetPlantResult.Read(archivedPlant),
      getOperationResult = GetOperationResult.Read(existing),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existing), hasNextPage = false)),
      updateOperationResult = EditOperationResult.Edited(updated)
    )
    val editResult = journal.editOperation(existing.id, amended)

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
    val refs         = Refs()
    val journal      = buildJournal(
      refs,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(newerCare, hasNextPage = true)),
      nextOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)).some,
      updateOperationResult = editResult
    )

    assertEquals(journal.editOperation(existingRepot.id, amended), editResult)
    assertEquals(refs.updatedPlants.get(), Vector(plant.copy(details = plant.details.copy(substrate = newSubstrate))))

  test("should amend an older repot without changing the plant"):
    val olderRepot = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val newerRepot = Operation(OperationId("o2"), PlantId("p1"), date, repot)
    val amended    = OperationDetails.Repot(substrate, maybeNote = none)
    val editResult = EditOperationResult.Edited(olderRepot.copy(details = amended))
    val refs       = Refs()
    val journal    = buildJournal(
      refs,
      getOperationResult = GetOperationResult.Read(olderRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(olderRepot, newerRepot), hasNextPage = false)),
      updateOperationResult = editResult
    )

    assertEquals(journal.editOperation(olderRepot.id, amended), editResult)
    assertEquals(refs.updatedPlants.get(), Vector.empty)
    assertEquals(refs.restoredOperations.get(), Vector.empty)

  test("should restore an amended repot whenever plant synchronization cannot complete"):
    val existingRepot     = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val historyFailure    = RuntimeException("history unavailable")
    val plantFailure      = RuntimeException("plant unavailable")
    val updateFailure     = RuntimeException("plant update failed")
    val unreadableHistory = Refs()
    val historyJournal    = buildJournal(
      unreadableHistory,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.ReadFailed(historyFailure),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val unreadablePlant = Refs()
    val plantJournal    = buildJournal(
      unreadablePlant,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      getPlantResult = GetPlantResult.ReadFailed(plantFailure),
      updateOperationResult = EditOperationResult.Edited(existingRepot)
    )
    val plantNotUpdated = Refs()
    val updateJournal   = buildJournal(
      plantNotUpdated,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      updateOperationResult = EditOperationResult.Edited(existingRepot),
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure)
    )

    val historyResult = historyJournal.editOperation(existingRepot.id, repot)
    val plantResult   = plantJournal.editOperation(existingRepot.id, repot)
    val updateResult  = updateJournal.editOperation(existingRepot.id, repot)

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
    val journal       = buildJournal(
      refs,
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      updateOperationResult = EditOperationResult.EditFailed(cause)
    )

    assertEquals(journal.editOperation(existingRepot.id, repot), EditOperationResult.EditFailed(cause))
    assertEquals(refs.updatedPlants.get(), Vector.empty[Plant])

  test("should report both failures when restoring an amended repot also fails"):
    val existingRepot  = Operation(OperationId("o1"), PlantId("p1"), date, repot)
    val updateFailure  = RuntimeException("update failed")
    val restoreFailure = RuntimeException("restore failed")
    val journal        = buildJournal(
      getOperationResult = GetOperationResult.Read(existingRepot),
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(existingRepot), hasNextPage = false)),
      updateOperationResult = EditOperationResult.Edited(existingRepot),
      updatePlantResult = UpdatePlantResult.UpdateFailed(updateFailure),
      restoreOperationResult = OperationCompensationResult.CompensationFailed(restoreFailure)
    )

    journal.editOperation(existingRepot.id, repot) match
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
    val missing      = Refs()
    val unreadable   = Refs()

    assertEquals(buildJournal(typeMismatch).editOperation(operation.id, repot), EditOperationResult.OperationTypeMismatch)
    assertEquals(
      buildJournal(missing, getOperationResult = GetOperationResult.RecordMissing).editOperation(OperationId("nope"), care),
      EditOperationResult.OperationMissing
    )
    assertEquals(
      buildJournal(unreadable, getOperationResult = GetOperationResult.ReadFailed(readFailure)).editOperation(operation.id, care),
      EditOperationResult.EditFailed(readFailure)
    )
    List(typeMismatch, missing, unreadable).foreach: refs =>
      assertEquals(refs.updatedOperations.get(), Vector.empty)

  test("should surface an edit failure from the store"):
    val cause = RuntimeException("store down")
    assertEquals(
      buildJournal(updateOperationResult = EditOperationResult.EditFailed(cause)).editOperation(operation.id, care),
      EditOperationResult.EditFailed(cause)
    )

  final private case class Refs():
    val createdPlants: AtomicReference[Vector[Plant]]                                  = new AtomicReference(Vector.empty)
    val requestedStatuses: AtomicReference[Vector[PlantStatus]]                        = AtomicReference(Vector.empty)
    val requestedOperationWindows: AtomicReference[Vector[(PlantId, OperationWindow)]] = AtomicReference(Vector.empty)
    val requestedDateRanges: AtomicReference[Vector[PlantId]]                          = AtomicReference(Vector.empty)
    val recordedOperations: AtomicReference[Vector[Operation]]                         = new AtomicReference(Vector.empty)
    val updatedOperations: AtomicReference[Vector[(OperationId, OperationDetails)]]    = new AtomicReference(Vector.empty)
    val removedOperations: AtomicReference[Vector[OperationId]]                        = new AtomicReference(Vector.empty)
    val restoredOperations: AtomicReference[Vector[Operation]]                         = new AtomicReference(Vector.empty)
    val updatedPlants: AtomicReference[Vector[Plant]]                                  = new AtomicReference(Vector.empty)
    val catalogReads: AtomicInteger                                                    = AtomicInteger(0)

  private def buildJournal(
      refs: Refs = Refs(),
      addPlantResult: AddPlantResult = AddPlantResult.Added,
      getPlantsResult: GetPlantsResult = GetPlantsResult.Read(Vector.empty),
      archivedCountResult: ArchivedCountResult = ArchivedCountResult.Counted(0),
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
      nextId: () => String = () => "id-1"
  ) =
    val plantReads     = AtomicInteger(0)
    val operationReads = AtomicInteger(0)
    val store          = new PlantJournalStore:
      override def addPlant(plant: Plant): AddPlantResult =
        refs.createdPlants.updateAndGet(_ :+ plant).pipe(_ => addPlantResult)
      override def getPlants(status: PlantStatus): GetPlantsResult =
        refs.requestedStatuses.updateAndGet(_ :+ status)
        getPlantsResult
      override def getArchivedCount: ArchivedCountResult = archivedCountResult
      override def getPlant(id: PlantId): GetPlantResult =
        if plantReads.getAndIncrement().equals(0) then getPlantResult
        else getPlantResultAfterLog.getOrElse(getPlantResult)
      override def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult =
        refs.requestedOperationWindows.updateAndGet(_ :+ (plantId -> window))
        if operationReads.getAndIncrement().equals(0) then getOperationsResult
        else nextOperationsResult.getOrElse(getOperationsResult)
      override def getOperationDateRange(plantId: PlantId): GetOperationDateRangeResult =
        refs.requestedDateRanges.updateAndGet(_ :+ plantId).pipe(_ => operationDateRangeResult)
      override def getOperation(id: OperationId): GetOperationResult      = getOperationResult
      override def addOperation(operation: Operation): LogOperationResult =
        refs.recordedOperations.updateAndGet(_ :+ operation).pipe(_ => addOperationResult)
      override def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult =
        refs.updatedOperations.updateAndGet(_ :+ (id -> details)).pipe(_ => updateOperationResult)
      override def removeOperation(id: OperationId): OperationCompensationResult =
        refs.removedOperations.updateAndGet(_ :+ id).pipe(_ => removeOperationResult)
      override def restoreOperation(operation: Operation): OperationCompensationResult =
        refs.restoredOperations.updateAndGet(_ :+ operation).pipe(_ => restoreOperationResult)
      override def updatePlant(plant: Plant): UpdatePlantResult =
        refs.updatedPlants.updateAndGet(_ :+ plant).pipe(_ => updatePlantResult)
    val substrateStore = new SubstrateComponentStore:
      override def getSubstrateComponents: CatalogReadResult[SubstrateComponent] =
        refs.catalogReads.incrementAndGet().pipe(_ => componentReadResult)
      override def addSubstrateComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent] =
        fail("journal must not write substrate components")
      override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
        fail("journal must not edit substrate components")
    val pesticideStore = new PesticideStore:
      override def getPesticides: CatalogReadResult[Pesticide]                     = pesticideReadResult
      override def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide] =
        fail("journal must not write pesticides")
      override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
        fail("journal must not edit pesticides")
    PlantJournal.make(using store, substrateStore, pesticideStore, () => nextId())
