package gardening.usecases

import cats.syntax.eq.*
import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.plants.*
import gardening.domain.plants.PhotoMediaType.*
import gardening.ports.{PlantStore, PhotoStore, PhotoContentStore}
import gardening.capabilities.TestImplicits
import io.github.iltotore.iron.autoRefine
import scodec.bits.ByteVector

import language.experimental.captureChecking

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class PhotoThumbnailBackfillComponentTest extends munit.FunSuite with TestImplicits:

  private val date             = Instant.parse("2026-01-01T00:00:00Z")
  private val perliteId        = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val substrate        = Substrate.of(List(SubstratePart(perliteId, share = 100))).getOrElse(fail("invalid substrate"))
  private val plantDetails     = PlantDetails(Species("Ficus lyrata"), none, Location("Balcony"), substrate, PlantStatus.Active)
  private val plantId          = PlantId("p1")
  private val plant            = Plant(plantId, plantDetails)
  private val photo1           = PlantPhoto(PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000001")), plantId, date)
  private val originalPhoto    = PhotoContent(ByteVector(Array[Byte](1, 2, 3)), Jpeg)
  private val derivedThumbnail = PhotoContent(ByteVector(Array[Byte](9, 9, 9)), Jpeg)

  test("should derive and store a thumbnail for a photo missing one"):
    val refs = Refs()

    val result = buildBackfill(refs, plants = Vector(plant), photosByPlant = Map(plantId -> Vector(photo1))).run()

    assertEquals(result, PhotoBackfillResult.Completed(Vector(photo1.id), Vector.empty))
    assertEquals(refs.putContents.get(), Vector((photo1.id, originalPhoto, derivedThumbnail)))

  test("should skip a photo that already has a thumbnail without deriving or writing anything"):
    val refs = Refs()

    val result = buildBackfill(
      refs,
      plants = Vector(plant),
      photosByPlant = Map(plantId -> Vector(photo1)),
      thumbnailContent = Map(photo1.id -> PhotoReadResult.Read(derivedThumbnail))
    ).run()

    assertEquals(result, PhotoBackfillResult.Completed(Vector.empty, Vector.empty))
    assertEquals(refs.putContents.get(), Vector.empty)

  test("should skip and report a photo with no original content"):
    val refs = Refs()

    val result = buildBackfill(
      refs,
      plants = Vector(plant),
      photosByPlant = Map(plantId -> Vector(photo1)),
      originalContent = Map(photo1.id -> PhotoReadResult.ContentMissing)
    ).run()

    result match
      case PhotoBackfillResult.Completed(processed, skipped) =>
        assertEquals(processed, Vector.empty)
        assertEquals(skipped.map(_.photo), Vector(photo1.id))
      case other => fail(s"expected Completed, got $other")

  test("should skip and report a photo whose original read fails"):
    val cause = RuntimeException("disk error")
    val refs  = Refs()

    val result = buildBackfill(
      refs,
      plants = Vector(plant),
      photosByPlant = Map(plantId -> Vector(photo1)),
      originalContent = Map(photo1.id -> PhotoReadResult.ReadFailed(cause))
    ).run()

    assertEquals(result, PhotoBackfillResult.Completed(Vector.empty, Vector(PhotoBackfillSkip(photo1.id, cause))))

  test("should skip and report a photo whose thumbnail read fails"):
    val cause = RuntimeException("disk error")
    val refs  = Refs()

    val result = buildBackfill(
      refs,
      plants = Vector(plant),
      photosByPlant = Map(plantId -> Vector(photo1)),
      thumbnailContent = Map(photo1.id -> PhotoReadResult.ReadFailed(cause))
    ).run()

    assertEquals(result, PhotoBackfillResult.Completed(Vector.empty, Vector(PhotoBackfillSkip(photo1.id, cause))))

  test("should skip and report a photo whose original can't be processed into a thumbnail"):
    val cause = RuntimeException("no image reader available for this content")
    val refs  = Refs()

    val result = buildBackfill(
      refs,
      plants = Vector(plant),
      photosByPlant = Map(plantId -> Vector(photo1)),
      deriveResult = ThumbnailDerivationResult.DerivationFailed(cause)
    ).run()

    assertEquals(result, PhotoBackfillResult.Completed(Vector.empty, Vector(PhotoBackfillSkip(photo1.id, cause))))

  test("should skip and report a photo whose derived thumbnail fails to write"):
    val cause = RuntimeException("disk full")
    val refs  = Refs()

    val result = buildBackfill(
      refs,
      plants = Vector(plant),
      photosByPlant = Map(plantId -> Vector(photo1)),
      putResult = PhotoWriteResult.WriteFailed(cause)
    ).run()

    assertEquals(result, PhotoBackfillResult.Completed(Vector.empty, Vector(PhotoBackfillSkip(photo1.id, cause))))

  test("should process photos across both active and archived plants"):
    val archivedPlantId = PlantId("p2")
    val archivedPlant   = Plant(archivedPlantId, plantDetails.copy(status = PlantStatus.Archived))
    val photo2          = PlantPhoto(PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000002")), archivedPlantId, date)
    val refs            = Refs()

    val result = buildBackfill(
      refs,
      plants = Vector(plant, archivedPlant),
      photosByPlant = Map(plantId -> Vector(photo1), archivedPlantId -> Vector(photo2))
    ).run()

    result match
      case PhotoBackfillResult.Completed(processed, _) => assertEquals(processed.toSet, Set(photo1.id, photo2.id))
      case other                                       => fail(s"expected Completed, got $other")

  test("should paginate through more photos than fit on one page for a single plant"):
    val photo2 = PlantPhoto(PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000002")), plantId, date)

    val result = buildBackfill(plants = Vector(plant), pagedPhotosByPlant = Map(plantId -> Vector(Vector(photo1), Vector(photo2)))).run()

    result match
      case PhotoBackfillResult.Completed(processed, _) => assertEquals(processed.toSet, Set(photo1.id, photo2.id))
      case other                                       => fail(s"expected Completed, got $other")

  test("should fail without processing anything when listing plants fails"):
    val cause = RuntimeException("store down")

    val result = buildBackfill(plantsResult = GetPlantsResult.ReadFailed(cause).some).run()

    assertEquals(result, PhotoBackfillResult.BackfillFailed(cause))

  test("should fail without processing other plants when listing one plant's photos fails"):
    val archivedPlantId = PlantId("p2")
    val archivedPlant   = Plant(archivedPlantId, plantDetails.copy(status = PlantStatus.Archived))
    val cause           = RuntimeException("store down")

    val result =
      buildBackfill(plants = Vector(plant, archivedPlant), photosResult = GetPhotosResult.ReadFailed(cause).some).run()

    assertEquals(result, PhotoBackfillResult.BackfillFailed(cause))

  final private case class Refs():
    val putContents: AtomicReference[Vector[(PhotoId, PhotoContent, PhotoContent)]] = AtomicReference(Vector.empty)

  private def buildBackfill(
      refs: Refs = Refs(),
      plants: Vector[Plant] = Vector.empty,
      plantsResult: Option[GetPlantsResult] = none,
      photosByPlant: Map[PlantId, Vector[PlantPhoto]] = Map.empty,
      pagedPhotosByPlant: Map[PlantId, Vector[Vector[PlantPhoto]]] = Map.empty,
      photosResult: Option[GetPhotosResult] = none,
      thumbnailContent: Map[PhotoId, PhotoReadResult] = Map.empty,
      originalContent: Map[PhotoId, PhotoReadResult] = Map.empty,
      deriveResult: ThumbnailDerivationResult = ThumbnailDerivationResult.Derived(derivedThumbnail),
      putResult: PhotoWriteResult = PhotoWriteResult.Written
  ) =
    val pageCallIndex = AtomicReference(Map.empty[PlantId, Int])

    val plantStore = new PlantStore:
      override def addPlant(plant: Plant): AddPlantResult          = AddPlantResult.Added
      override def getArchivedCount: ArchivedCountResult           = ArchivedCountResult.Counted(0)
      override def getPlant(plant: PlantId): GetPlantResult        = GetPlantResult.RecordMissing
      override def updatePlant(plant: Plant): UpdatePlantResult    = UpdatePlantResult.Updated
      override def getPlants(status: PlantStatus): GetPlantsResult =
        plantsResult.getOrElse(GetPlantsResult.Read(plants.filter(_.details.status === status)))

    val store = new PhotoStore:
      override def addPhoto(photo: PlantPhoto): AddPhotoResult                     = AddPhotoResult.Added(photo)
      override def removePhoto(photo: PhotoId): RemovePhotoResult                  = RemovePhotoResult.PhotoMissing
      override def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult =
        photosResult.getOrElse:
          pagedPhotosByPlant.get(plant) match
            case Some(pages) =>
              val index = pageCallIndex.getAndUpdate(counts => counts.updated(plant, counts.getOrElse(plant, 0) + 1)).getOrElse(plant, 0)
              GetPhotosResult.Read(PhotoPage(pages.applyOrElse(index, _ => Vector.empty), hasNextPage = index + 1 < pages.size))
            case None =>
              GetPhotosResult.Read(PhotoPage(photosByPlant.getOrElse(plant, Vector.empty), hasNextPage = false))

    val contentStore = new PhotoContentStore:
      override def put(photo: PhotoId, original: PhotoContent, thumbnail: PhotoContent): PhotoWriteResult =
        refs.putContents.updateAndGet(_ :+ (photo, original, thumbnail)).pipe(_ => putResult)
      override def get(photo: PhotoId, variant: PhotoVariant): PhotoReadResult = variant match
        case PhotoVariant.Thumbnail => thumbnailContent.getOrElse(photo, PhotoReadResult.ContentMissing)
        case PhotoVariant.Original  => originalContent.getOrElse(photo, PhotoReadResult.Read(originalPhoto))
      override def delete(photo: PhotoId): PhotoWriteResult = PhotoWriteResult.Written

    val thumbnail = new PhotoThumbnail:
      override def derive(original: PhotoContent): ThumbnailDerivationResult = deriveResult

    PhotoThumbnailBackfill.make(using plantStore, store, contentStore, thumbnail)
