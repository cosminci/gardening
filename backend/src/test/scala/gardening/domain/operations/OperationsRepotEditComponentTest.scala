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
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.util.chaining.scalaUtilChainingOps

class OperationsRepotEditComponentTest extends munit.FunSuite with TestImplicits:

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
      getOperationsResult: GetOperationsResult,
      nextOperationsResult: Option[GetOperationsResult] = none,
      getOperationResult: GetOperationResult,
      addOperationResult: LogOperationResult = LogOperationResult.Logged(OperationId("id-1")),
      updateOperationResult: EditOperationResult,
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
