package gardening.usecases

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.plants.*
import gardening.domain.plants.PhotoMediaType.*
import gardening.domain.substrate.{AddSubstrateComponentResult, DeleteSubstrateMixResult, GetSubstrateComponentResult, GetSubstrateComponentsResult, GetSubstrateMixesResult, SaveSubstrateMixResult, UpdateSubstrateComponentResult}
import gardening.ports.{SubstrateStore, PlantStore, PhotoContentStore, PlantManagerMetricsApi}
import gardening.capabilities.{IdGenerator, Clock, PlantUpdateLock, TestImplicits}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import scodec.bits.ByteVector

import language.experimental.captureChecking

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import scala.util.chaining.scalaUtilChainingOps

class PlantManagerComponentTest extends munit.FunSuite with TestImplicits:

  private given metrics: PlantManagerMetricsApi = new PlantManagerMetricsApi:
    def setPlantsCount(status: PlantStatus, count: Long): Unit                              = ()
    def incrementSubstrateComponent(component: SubstrateComponentId): Unit                  = ()
    def setPlantsDisplayNames(status: PlantStatus, plants: Vector[(PlantId, String)]): Unit = ()

  private val date       = Instant.parse("2026-01-01T00:00:00Z")
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

  private val photoUuid         = UUID.fromString("00000000-0000-4000-8002-000000000001")
  private val photo             = PlantPhoto(PhotoId(photoUuid), plant.id, date)
  private val photoContent      = decodableJpeg()
  private val expectedThumbnail =
    PhotoThumbnail.derive(photoContent).getOrElse(fail("test fixture photo must produce a thumbnail"))
  private val firstPhotoPage = PhotoWindow(offset = 0, size = 3)

  // addPhoto now derives a real thumbnail via ImageIO, so this fixture must be genuinely
  // decodable - unlike before, arbitrary placeholder bytes would fail derivation.
  private def decodableJpeg(): PhotoContent =
    val image  = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
    val output = ByteArrayOutputStream()
    val _      = ImageIO.write(image, "jpg", output)
    PhotoContent(ByteVector(output.toByteArray), Jpeg)

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

  test("should store photo content then metadata and return the added photo with its server-assigned timestamp"):
    val captureInstant = date.plusSeconds(5)
    val expectedPhoto  = PlantPhoto(PhotoId(photoUuid), plant.id, captureInstant)
    val refs           = Refs()

    val result = buildManager(
      refs,
      nextId = () => photoUuid.toString,
      addPhotoResult = AddPhotoResult.Added(expectedPhoto),
      captureTime = captureInstant
    ).addPhoto(plant.id, photoContent)

    assertEquals(result, AddPhotoResult.Added(expectedPhoto))
    assertEquals(refs.putPhotoContents.get(), Vector((PhotoId(photoUuid), photoContent, expectedThumbnail)))
    assertEquals(refs.addedPhotos.get(), Vector(expectedPhoto))

  test("should fail the upload without writing anything when a thumbnail cannot be derived"):
    val undecodable = PhotoContent(ByteVector(Array[Byte](1, 2, 3)), Jpeg)
    val refs        = Refs()

    val result = buildManager(refs, nextId = () => photoUuid.toString).addPhoto(plant.id, undecodable)

    result match
      case AddPhotoResult.AddFailed(_) => ()
      case other                       => fail(s"expected AddFailed, got $other")
    assertEquals(refs.putPhotoContents.get(), Vector.empty)
    assertEquals(refs.addedPhotos.get(), Vector.empty)

  test("should surface a content write failure without touching metadata"):
    val cause = RuntimeException("disk full")
    val refs  = Refs()

    val result = buildManager(
      refs,
      nextId = () => photoUuid.toString,
      putContentResult = PhotoWriteResult.WriteFailed(cause)
    ).addPhoto(plant.id, photoContent)

    assertEquals(result, AddPhotoResult.AddFailed(cause))
    assertEquals(refs.addedPhotos.get(), Vector.empty)

  test("should compensate by deleting content when metadata write fails"):
    val cause = RuntimeException("metadata store down")
    val refs  = Refs()

    val result = buildManager(
      refs,
      nextId = () => photoUuid.toString,
      addPhotoResult = AddPhotoResult.AddFailed(cause)
    ).addPhoto(plant.id, photoContent)

    assertEquals(result, AddPhotoResult.AddFailed(cause))
    assertEquals(refs.deletedPhotoContentIds.get(), Vector(PhotoId(photoUuid)))

  test("should compensate by deleting content when plant is missing during metadata write"):
    val refs = Refs()

    val result = buildManager(
      refs,
      nextId = () => photoUuid.toString,
      addPhotoResult = AddPhotoResult.PlantMissing
    ).addPhoto(plant.id, photoContent)

    assertEquals(result, AddPhotoResult.PlantMissing)
    assertEquals(refs.deletedPhotoContentIds.get(), Vector(PhotoId(photoUuid)))

  test("should report both failures when metadata write fails and compensation content delete also fails"):
    val primary      = RuntimeException("metadata write failed")
    val compensation = RuntimeException("content delete failed too")
    val refs         = Refs()

    buildManager(
      refs,
      nextId = () => photoUuid.toString,
      addPhotoResult = AddPhotoResult.AddFailed(primary),
      deleteContentResult = PhotoWriteResult.WriteFailed(compensation)
    ).addPhoto(plant.id, photoContent) match
      case AddPhotoResult.AddFailed(reason) =>
        assertEquals(reason.getCause, primary)
        assertEquals(reason.getSuppressed.toList, List(compensation))
      case other => fail(s"expected AddFailed, got $other")

  test("should report both failures when plant is missing and compensation content delete also fails"):
    val compensation = RuntimeException("content delete failed too")
    val refs         = Refs()

    buildManager(
      refs,
      nextId = () => photoUuid.toString,
      addPhotoResult = AddPhotoResult.PlantMissing,
      deleteContentResult = PhotoWriteResult.WriteFailed(compensation)
    ).addPhoto(plant.id, photoContent) match
      case AddPhotoResult.AddFailed(reason) =>
        assert(reason.getCause.getMessage.contains("plant missing while adding photo"))
        assertEquals(reason.getSuppressed.toList, List(compensation))
      case other => fail(s"expected AddFailed, got $other")

  test("should delete content after metadata removal and return the removed photo"):
    val refs = Refs()

    val result = buildManager(refs).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.Removed(photo))
    assertEquals(refs.removedPhotoIds.get(), Vector(photo.id))
    assertEquals(refs.deletedPhotoContentIds.get(), Vector(photo.id))

  test("should surface PhotoMissing without touching the content store"):
    val refs = Refs()

    val result = buildManager(refs, removePhotoResult = RemovePhotoResult.PhotoMissing).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.PhotoMissing)
    assertEquals(refs.deletedPhotoContentIds.get(), Vector.empty)

  test("should surface RemoveFailed without touching the content store"):
    val cause = RuntimeException("store down")
    val refs  = Refs()

    val result = buildManager(refs, removePhotoResult = RemovePhotoResult.RemoveFailed(cause)).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.RemoveFailed(cause))
    assertEquals(refs.deletedPhotoContentIds.get(), Vector.empty)

  test("should restore metadata and surface the content delete reason when content deletion fails"):
    val contentDeleteCause = RuntimeException("content store down")
    val refs               = Refs()

    val result = buildManager(refs, deleteContentResult = PhotoWriteResult.WriteFailed(contentDeleteCause)).removePhoto(photo.id)

    assertEquals(result, RemovePhotoResult.RemoveFailed(contentDeleteCause))
    assertEquals(refs.addedPhotos.get(), Vector(photo))

  test("should report both failures when content deletion fails and metadata restore also fails"):
    val contentDeleteCause = RuntimeException("content store down")
    val compensationCause  = RuntimeException("metadata restore failed")
    val refs               = Refs()

    buildManager(
      refs,
      deleteContentResult = PhotoWriteResult.WriteFailed(contentDeleteCause),
      addPhotoResult = AddPhotoResult.AddFailed(compensationCause)
    ).removePhoto(photo.id) match
      case RemovePhotoResult.RemoveFailed(reason) =>
        assertEquals(reason.getCause, contentDeleteCause)
        assertEquals(reason.getSuppressed.toList, List(compensationCause))
      case other => fail(s"expected RemoveFailed, got $other")

  test("should report both failures when content deletion fails and plant is missing during metadata restore"):
    val contentDeleteCause = RuntimeException("content store down")
    val refs               = Refs()

    buildManager(
      refs,
      deleteContentResult = PhotoWriteResult.WriteFailed(contentDeleteCause),
      addPhotoResult = AddPhotoResult.PlantMissing
    ).removePhoto(photo.id) match
      case RemovePhotoResult.RemoveFailed(reason) =>
        assertEquals(reason.getCause, contentDeleteCause)
        assert(reason.getSuppressed.head.getMessage.contains("plant missing while restoring photo metadata"))
      case other => fail(s"expected RemoveFailed, got $other")

  test("should delegate photo listing to the store and return the page"):
    val page = PhotoPage(Vector(photo), hasNextPage = false)
    val refs = Refs()

    val result = buildManager(refs, getPhotosResult = GetPhotosResult.Read(page)).getPhotos(plant.id, firstPhotoPage)

    assertEquals(result, GetPhotosResult.Read(page))
    assertEquals(refs.requestedPhotoWindows.get(), Vector(plant.id -> firstPhotoPage))

  test("should surface a photo listing failure from the store"):
    val cause = RuntimeException("store unavailable")

    val result = buildManager(getPhotosResult = GetPhotosResult.ReadFailed(cause)).getPhotos(plant.id, firstPhotoPage)

    assertEquals(result, GetPhotosResult.ReadFailed(cause))

  test("should pass photo content reads through to the content store, thumbnail variant included"):
    val refs = Refs()
    assertEquals(buildManager(refs).getPhotoContent(photo.id, PhotoVariant.Original), PhotoReadResult.Read(photoContent))
    assertEquals(
      buildManager(getContentResult = PhotoReadResult.ContentMissing).getPhotoContent(photo.id, PhotoVariant.Thumbnail),
      PhotoReadResult.ContentMissing
    )
    assertEquals(refs.requestedContentVariants.get(), Vector(PhotoVariant.Original))

  final private case class Refs():
    val createdPlants: AtomicReference[Vector[Plant]]                                    = AtomicReference(Vector.empty)
    val requestedStatuses: AtomicReference[Vector[PlantStatus]]                          = AtomicReference(Vector.empty)
    val updatedPlants: AtomicReference[Vector[Plant]]                                    = AtomicReference(Vector.empty)
    val addedPhotos: AtomicReference[Vector[PlantPhoto]]                                 = AtomicReference(Vector.empty)
    val removedPhotoIds: AtomicReference[Vector[PhotoId]]                                = AtomicReference(Vector.empty)
    val requestedPhotoWindows: AtomicReference[Vector[(PlantId, PhotoWindow)]]           = AtomicReference(Vector.empty)
    val putPhotoContents: AtomicReference[Vector[(PhotoId, PhotoContent, PhotoContent)]] = AtomicReference(Vector.empty)
    val deletedPhotoContentIds: AtomicReference[Vector[PhotoId]]                         = AtomicReference(Vector.empty)
    val requestedContentVariants: AtomicReference[Vector[PhotoVariant]]                  = AtomicReference(Vector.empty)

  private def buildManager(
      refs: Refs = Refs(),
      addPlantResult: AddPlantResult = AddPlantResult.Added,
      getPlantsResult: GetPlantsResult = GetPlantsResult.Read(Vector.empty),
      archivedCountResult: ArchivedCountResult = ArchivedCountResult.Counted(0),
      getPlantResult: GetPlantResult = GetPlantResult.Read(plant),
      updatePlantResult: UpdatePlantResult = UpdatePlantResult.Updated,
      componentReadResult: GetSubstrateComponentsResult = GetSubstrateComponentsResult.Read(seededComponents),
      nextId: () => String = () => "id-1",
      addPhotoResult: AddPhotoResult = AddPhotoResult.Added(photo),
      removePhotoResult: RemovePhotoResult = RemovePhotoResult.Removed(photo),
      getPhotosResult: GetPhotosResult = GetPhotosResult.Read(PhotoPage(Vector.empty, hasNextPage = false)),
      putContentResult: PhotoWriteResult = PhotoWriteResult.Written,
      deleteContentResult: PhotoWriteResult = PhotoWriteResult.Written,
      getContentResult: PhotoReadResult = PhotoReadResult.Read(photoContent),
      captureTime: Instant = date
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
      override def addPhoto(photo: PlantPhoto): AddPhotoResult =
        refs.addedPhotos.updateAndGet(_ :+ photo).pipe(_ => addPhotoResult)
      override def removePhoto(photo: PhotoId): RemovePhotoResult =
        refs.removedPhotoIds.updateAndGet(_ :+ photo).pipe(_ => removePhotoResult)
      override def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult =
        refs.requestedPhotoWindows.updateAndGet(_ :+ (plant -> window)).pipe(_ => getPhotosResult)
    val contentStore = new PhotoContentStore:
      override def put(photo: PhotoId, original: PhotoContent, thumbnail: PhotoContent): PhotoWriteResult =
        refs.putPhotoContents.updateAndGet(_ :+ (photo, original, thumbnail)).pipe(_ => putContentResult)
      override def get(photo: PhotoId, variant: PhotoVariant): PhotoReadResult =
        refs.requestedContentVariants.updateAndGet(_ :+ variant).pipe(_ => getContentResult)
      override def delete(photo: PhotoId): PhotoWriteResult =
        refs.deletedPhotoContentIds.updateAndGet(_ :+ photo).pipe(_ => deleteContentResult)
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
    PlantManager.make(using store, contentStore, substrateStore, () => nextId(), () => captureTime, PlantUpdateLock.make)
