package gardening.usecases

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.plants.*
import gardening.domain.substrate.{AddSubstrateComponentResult, DeleteSubstrateMixResult, GetSubstrateComponentResult, GetSubstrateComponentsResult, GetSubstrateMixesResult, SaveSubstrateMixResult, UpdateSubstrateComponentResult}
import gardening.ports.{SubstrateStore, PlantStore, PlantManagerMetricsApi}
import gardening.capabilities.{IdGenerator, PlantUpdateLock, TestImplicits}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine

import language.experimental.captureChecking

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class PlantManagerComponentTest extends munit.FunSuite with TestImplicits:

  private given metrics: PlantManagerMetricsApi = new PlantManagerMetricsApi:
    def setPlantsCount(status: PlantStatus, count: Long): Unit                              = ()
    def incrementSubstrateComponent(component: SubstrateComponentId): Unit                  = ()
    def setPlantsDisplayNames(status: PlantStatus, plants: Vector[(PlantId, String)]): Unit = ()

  private val perliteId  = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val pineBarkId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000004"))
  private val sand3to5Id = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000005"))
  private val lecaId     = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000007"))

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

  private val seededComponents = Vector(perliteId, pineBarkId, sand3to5Id, lecaId)
    .map(id => SubstrateComponent(id, SubstrateComponentData(SubstrateComponentName(id.value.toString), none), SubstrateComponentStatus.Active))

  test("should create an active plant with initial substrate independently of operations"):
    val refs    = Refs()
    val manager = buildManager(refs)

    val result = manager.createPlant(plant.details.species, plant.details.maybeNickname, plant.details.location, substrate)

    val expectedPlant = plant.copy(id = PlantId("id-1"))
    assertEquals(result, CreatePlantResult.Created(expectedPlant))
    assertEquals(refs.createdPlants.get(), Vector(expectedPlant))

  test("should distinguish unknown substrate components, catalog reads, and failed writes"):
    val failure        = RuntimeException("unavailable")
    val missingRefs    = Refs()
    val readFailedRefs = Refs()

    val missing = buildManager(
      missingRefs,
      componentReadResult = GetSubstrateComponentsResult.Read(Vector.empty)
    ).createPlant(plant.details.species, none, plant.details.location, substrate)
    val readFailed = buildManager(
      readFailedRefs,
      componentReadResult = GetSubstrateComponentsResult.ReadFailed(failure)
    ).createPlant(plant.details.species, none, plant.details.location, substrate)
    val writeFailed =
      buildManager(addPlantResult = AddPlantResult.AddFailed(failure)).createPlant(plant.details.species, none, plant.details.location, substrate)

    assertEquals(missing, CreatePlantResult.UnknownComponent)
    assertEquals(readFailed, CreatePlantResult.CatalogReadFailed(failure))
    assertEquals(writeFailed, CreatePlantResult.CreateFailed(failure))
    assertEquals(missingRefs.createdPlants.get(), Vector.empty)
    assertEquals(readFailedRefs.createdPlants.get(), Vector.empty)

  test("should return current plants by status and surface read failures"):
    val failure = RuntimeException("plant read failed")
    val unnamed = plant.copy(id = PlantId("p2"), details = plant.details.copy(maybeNickname = None))
    val refs    = Refs()

    val active    = buildManager(refs, getPlantsResult = GetPlantsResult.Read(Vector(plant, unnamed)))
    val failedOne = buildManager(getPlantsResult = GetPlantsResult.ReadFailed(failure))

    val activeResult   = active.getPlants(PlantStatus.Active)
    val archivedResult = failedOne.getPlants(PlantStatus.Archived)

    assertEquals(activeResult, GetPlantsResult.Read(Vector(plant, unnamed)))
    assertEquals(refs.requestedStatuses.get(), Vector(PlantStatus.Active))
    assertEquals(archivedResult, GetPlantsResult.ReadFailed(failure))

  test("should read a non-negative archived count independently of plant lists"):
    val failure = RuntimeException("count unavailable")
    val refs    = Refs()

    val countResult  = buildManager(refs, archivedCountResult = ArchivedCountResult.Counted(4)).getArchivedCount
    val failedResult = buildManager(archivedCountResult = ArchivedCountResult.ReadFailed(failure)).getArchivedCount
    val invalidCount = intercept[IllegalArgumentException](ArchivedCountResult.Counted(-1L))

    assertEquals(countResult, ArchivedCountResult.Counted(4))
    assertEquals(failedResult, ArchivedCountResult.ReadFailed(failure))
    assertEquals(invalidCount.getMessage, "requirement failed: archived plant count must be non-negative")
    assertEquals(refs.requestedStatuses.get(), Vector.empty)

  test("should archive an active plant by editing its status alone"):
    val archivedPlant = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))
    val refs          = Refs()

    val result = buildManager(refs).editPlant(plant.id, _.copy(status = PlantStatus.Archived))

    assertEquals(result, EditPlantResult.Edited(archivedPlant))
    assertEquals(refs.updatedPlants.get(), Vector(archivedPlant))

  test("should edit an active plant's details independently of operations"):
    val newSubstrate    = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val revisedSpecies  = Species("Monstera deliciosa")
    val revisedLocation = Location("Living room")
    val refs            = Refs()

    val result        = buildManager(refs).editPlant(plant.id, _.copy(revisedSpecies, none, revisedLocation, newSubstrate))
    val expectedPlant = plant.copy(details = plant.details.copy(revisedSpecies, none, revisedLocation, newSubstrate))

    assertEquals(result, EditPlantResult.Edited(expectedPlant))
    assertEquals(refs.updatedPlants.get(), Vector(expectedPlant))

  test("should reject editing an archived plant regardless of the requested change, and preserve distinct failure results"):
    val failure       = RuntimeException("write unavailable")
    val archivedPlant = plant.copy(details = plant.details.copy(status = PlantStatus.Archived))
    val readFailure   = RuntimeException("read unavailable")
    val refs          = Refs()

    val archivedAgainResult = buildManager(refs, getPlantResult = GetPlantResult.Read(archivedPlant))
      .editPlant(plant.id, _.copy(status = PlantStatus.Archived))
    val archivedWithUnknownComponentResult = buildManager(
      refs,
      getPlantResult = GetPlantResult.Read(archivedPlant),
      componentReadResult = GetSubstrateComponentsResult.Read(Vector.empty)
    ).editPlant(plant.id, _.copy(location = Location("Kitchen")))
    val missingResult = buildManager(refs, getPlantResult = GetPlantResult.RecordMissing)
      .editPlant(plant.id, _.copy(location = Location("Kitchen")))
    val readResult = buildManager(refs, getPlantResult = GetPlantResult.ReadFailed(readFailure))
      .editPlant(plant.id, _.copy(location = Location("Kitchen")))
    val writeFailedResult = buildManager(refs, updatePlantResult = UpdatePlantResult.UpdateFailed(failure))
      .editPlant(plant.id, _.copy(location = Location("Kitchen")))

    assertEquals(archivedAgainResult, EditPlantResult.PlantArchived)
    assertEquals(archivedWithUnknownComponentResult, EditPlantResult.PlantArchived)
    assertEquals(missingResult, EditPlantResult.PlantMissing)
    assertEquals(readResult, EditPlantResult.EditFailed(readFailure))
    assertEquals(writeFailedResult, EditPlantResult.EditFailed(failure))
    val attemptedWrite = plant.copy(details = plant.details.copy(location = Location("Kitchen")))
    assertEquals(refs.updatedPlants.get(), Vector(attemptedWrite))

  test("should distinguish unknown substrate components and catalog read failures when changing substrate"):
    val newSubstrate = Substrate.of(List(SubstratePart(lecaId, share = 100))).getOrElse(fail("invalid test substrate"))
    val failure      = RuntimeException("unavailable")
    val refs         = Refs()

    val unknownResult = buildManager(refs, componentReadResult = GetSubstrateComponentsResult.Read(Vector.empty))
      .editPlant(plant.id, _.copy(substrate = newSubstrate))
    val readFailedResult = buildManager(refs, componentReadResult = GetSubstrateComponentsResult.ReadFailed(failure))
      .editPlant(plant.id, _.copy(substrate = newSubstrate))

    assertEquals(unknownResult, EditPlantResult.UnknownComponent)
    assertEquals(readFailedResult, EditPlantResult.CatalogReadFailed(failure))
    assertEquals(refs.updatedPlants.get(), Vector.empty)

  final private case class Refs():
    val createdPlants: AtomicReference[Vector[Plant]]           = AtomicReference(Vector.empty)
    val requestedStatuses: AtomicReference[Vector[PlantStatus]] = AtomicReference(Vector.empty)
    val updatedPlants: AtomicReference[Vector[Plant]]           = AtomicReference(Vector.empty)

  private def buildManager(
      refs: Refs = Refs(),
      addPlantResult: AddPlantResult = AddPlantResult.Added,
      getPlantsResult: GetPlantsResult = GetPlantsResult.Read(Vector.empty),
      archivedCountResult: ArchivedCountResult = ArchivedCountResult.Counted(0),
      getPlantResult: GetPlantResult = GetPlantResult.Read(plant),
      updatePlantResult: UpdatePlantResult = UpdatePlantResult.Updated,
      componentReadResult: GetSubstrateComponentsResult = GetSubstrateComponentsResult.Read(seededComponents)
  ) =
    val store = new PlantStore:
      override def addPlant(plant: Plant): AddPlantResult =
        refs.createdPlants.updateAndGet(_ :+ plant).pipe(_ => addPlantResult)
      override def getPlants(status: PlantStatus): GetPlantsResult =
        refs.requestedStatuses.updateAndGet(_ :+ status)
        getPlantsResult
      override def getArchivedCount: ArchivedCountResult        = archivedCountResult
      override def getPlant(plant: PlantId): GetPlantResult     = getPlantResult
      override def updatePlant(plant: Plant): UpdatePlantResult =
        refs.updatedPlants.updateAndGet(_ :+ plant).pipe(_ => updatePlantResult)
    val substrateStore = new SubstrateStore:
      override def getSubstrateComponents: GetSubstrateComponentsResult                         = componentReadResult
      override def getSubstrateComponent(id: SubstrateComponentId): GetSubstrateComponentResult =
        fail("plants must not read a single substrate component")
      override def addSubstrateComponent(component: SubstrateComponent): AddSubstrateComponentResult =
        fail("plants must not write substrate components")
      override def updateSubstrateComponent(component: SubstrateComponent): UpdateSubstrateComponentResult =
        fail("plants must not edit substrate components")
      override def getSubstrateMixes: GetSubstrateMixesResult =
        fail("plants must not read substrate mixes")
      override def saveSubstrateMix(mix: SubstrateMix): SaveSubstrateMixResult =
        fail("plants must not write substrate mixes")
      override def deleteSubstrateMix(id: java.util.UUID): DeleteSubstrateMixResult =
        fail("plants must not delete substrate mixes")
    PlantManager.make(using store, substrateStore, () => "id-1", PlantUpdateLock.make)
