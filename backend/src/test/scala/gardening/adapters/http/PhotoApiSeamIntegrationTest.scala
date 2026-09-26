package gardening.adapters.http

import gardening.domain.*
import gardening.domain.plants.*
import gardening.domain.plants.PhotoMediaType.*
import io.circe.parser.parse
import io.github.iltotore.iron.autoRefine
import ox.supervised
import scodec.bits.ByteVector
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{HttpClientSyncBackend, Response, SttpBackend, UriContext, basicRequest, multipart}
import sttp.model.{HeaderNames, StatusCode, Uri}
import sttp.shared.Identity
import sttp.tapir.server.netty.sync.NettySyncServer
import sttp.tapir.server.stub.TapirStubInterpreter

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.*

class PhotoApiSeamIntegrationTest extends munit.FunSuite:

  private val date          = Instant.parse("2026-01-01T00:00:00Z")
  private val plantId       = PlantId("p1")
  private val photoUuid     = UUID.fromString("00000000-0000-4000-8002-000000000001")
  private val photoId       = PhotoId(photoUuid)
  private val photo         = PlantPhoto(photoId, plantId, date)
  private val jpegBytes     = Array[Byte](0xff.toByte, 0xd8.toByte, 0x01)
  private val pngBytes      = Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47)
  private val webpBytes     = Array[Byte](0x52, 0x49, 0x46, 0x46)
  private val addedJson     = s"""{"id":"$photoUuid","capturedAt":"$date"}"""
  private val photoItemJson = s"""{"id":"$photoUuid","capturedAt":"$date"}"""
  private val pageJson      = s"""{"photos":[$photoItemJson],"hasNextPage":false}"""

  // Multipart bodies don't round-trip through TapirStubInterpreter/SttpBackendStub in this tapir
  // version (binary parts decode as corrupt), so upload tests run against a real Netty server.

  test("should upload a jpeg photo and return the added photo's id and timestamp"):
    val refs = Refs()

    val response = withLivePhotoServer(refs, addPhotoResult = AddPhotoResult.Added(photo)):
      uploadPhoto(plantId.value, jpegBytes, "image/jpeg")

    assertEquals(response.code -> jsonBody(response), StatusCode.Created -> json(addedJson))
    assertEquals(refs.addedContents.get().map(_.mediaType), Vector(Jpeg))

  test("should upload a png photo"):
    val response = withLivePhotoServer(addPhotoResult = AddPhotoResult.Added(photo)):
      uploadPhoto(plantId.value, pngBytes, "image/png")
    assertEquals(response.code, StatusCode.Created)

  test("should upload a webp photo"):
    val response = withLivePhotoServer(addPhotoResult = AddPhotoResult.Added(photo)):
      uploadPhoto(plantId.value, webpBytes, "image/webp")
    assertEquals(response.code, StatusCode.Created)

  test("should accept image/jpg as a jpeg alias"):
    val response = withLivePhotoServer(addPhotoResult = AddPhotoResult.Added(photo)):
      uploadPhoto(plantId.value, jpegBytes, "image/jpg")
    assertEquals(response.code, StatusCode.Created)

  test("should reject an unsupported media type with 415"):
    val response = withLivePhotoServer():
      uploadPhoto(plantId.value, Array[Byte](1, 2), "image/bmp")
    assertEquals(
      response.code                   -> jsonBody(response),
      StatusCode.UnsupportedMediaType -> json("""{"message":"unsupported media type: only image/jpeg, image/png, and image/webp are accepted"}""")
    )

  test("should reject a photo exceeding the 20 MiB size limit with 413"):
    val oversized = Array.fill(20 * 1024 * 1024 + 1)(0.toByte)
    val response  = withLivePhotoServer():
      uploadPhoto(plantId.value, oversized, "image/jpeg")
    assertEquals(response.code -> jsonBody(response), StatusCode.PayloadTooLarge -> json("""{"message":"photo exceeds the 20 MiB size limit"}"""))

  test("should return 404 when the plant does not exist"):
    val response = withLivePhotoServer(addPhotoResult = AddPhotoResult.PlantMissing):
      uploadPhoto(plantId.value, jpegBytes, "image/jpeg")
    assertEquals(response.code -> jsonBody(response), StatusCode.NotFound -> json("""{"message":"plant not found"}"""))

  test("should return 500 when adding a photo fails"):
    val response = withLivePhotoServer(addPhotoResult = AddPhotoResult.AddFailed(RuntimeException("store down"))):
      uploadPhoto(plantId.value, jpegBytes, "image/jpeg")
    assertEquals(response.code -> jsonBody(response), StatusCode.InternalServerError -> json("""{"message":"photo could not be added"}"""))

  test("should list photos for a plant"):
    val server   = buildPhotoApi(getPhotosResult = GetPhotosResult.Read(PhotoPage(Vector(photo), hasNextPage = false)))
    val response = get(s"/plants/${plantId.value}/photos", server)
    assertEquals(response.code -> jsonBody(response), StatusCode.Ok -> json(pageJson))

  test("should pass offset and pageSize to plants"):
    val refs   = Refs()
    val server = buildPhotoApi(refs, getPhotosResult = GetPhotosResult.Read(PhotoPage(Vector.empty, hasNextPage = false)))
    val _      = get(s"/plants/${plantId.value}/photos?offset=5&pageSize=10", server)
    assertEquals(refs.requestedWindows.get(), Vector(PhotoWindow(offset = 5, size = 10)))

  test("should return 400 for invalid photo listing parameters"):
    val server = buildPhotoApi()
    assertEquals(get(s"/plants/${plantId.value}/photos?offset=-1", server).code, StatusCode.BadRequest)
    assertEquals(get(s"/plants/${plantId.value}/photos?pageSize=0", server).code, StatusCode.BadRequest)
    assertEquals(get(s"/plants/${plantId.value}/photos?pageSize=25", server).code, StatusCode.BadRequest)

  test("should return 500 when listing photos fails"):
    val server   = buildPhotoApi(getPhotosResult = GetPhotosResult.ReadFailed(RuntimeException("store down")))
    val response = get(s"/plants/${plantId.value}/photos", server)
    assertEquals(response.code -> jsonBody(response), StatusCode.InternalServerError -> json("""{"message":"photos could not be read"}"""))

  test("should delete a photo"):
    val refs     = Refs()
    val server   = buildPhotoApi(refs, removePhotoResult = RemovePhotoResult.Removed(photo))
    val response = delete(s"/photos/${photoUuid}", server)
    assertEquals(response.code, StatusCode.NoContent)
    assertEquals(refs.removedIds.get(), Vector(photoId))

  test("should return 404 when removing an unknown photo"):
    val server   = buildPhotoApi(removePhotoResult = RemovePhotoResult.PhotoMissing)
    val response = delete(s"/photos/${photoUuid}", server)
    assertEquals(response.code -> jsonBody(response), StatusCode.NotFound -> json("""{"message":"photo not found"}"""))

  test("should return 500 when photo removal fails"):
    val server   = buildPhotoApi(removePhotoResult = RemovePhotoResult.RemoveFailed(RuntimeException("store down")))
    val response = delete(s"/photos/${photoUuid}", server)
    assertEquals(response.code -> jsonBody(response), StatusCode.InternalServerError -> json("""{"message":"photo could not be removed"}"""))

  test("should return photo content with the correct Content-Type header"):
    val jpegContent = PhotoContent(ByteVector(jpegBytes), Jpeg)
    val pngContent  = PhotoContent(ByteVector(pngBytes), Png)
    val webpContent = PhotoContent(ByteVector(webpBytes), Webp)

    val jpegServer = buildPhotoApi(getContentResult = PhotoReadResult.Read(jpegContent))
    val pngServer  = buildPhotoApi(getContentResult = PhotoReadResult.Read(pngContent))
    val webpServer = buildPhotoApi(getContentResult = PhotoReadResult.Read(webpContent))

    val jpegResponse = get(s"/photos/${photoUuid}/content", jpegServer)
    val pngResponse  = get(s"/photos/${photoUuid}/content", pngServer)
    val webpResponse = get(s"/photos/${photoUuid}/content", webpServer)

    assertEquals(jpegResponse.code, StatusCode.Ok)
    assertEquals(jpegResponse.header(HeaderNames.ContentType), Some("image/jpeg"))
    assertEquals(pngResponse.header(HeaderNames.ContentType), Some("image/png"))
    assertEquals(webpResponse.header(HeaderNames.ContentType), Some("image/webp"))

  test("should return 404 when photo content is missing"):
    val server   = buildPhotoApi(getContentResult = PhotoReadResult.ContentMissing)
    val response = get(s"/photos/${photoUuid}/content", server)
    assertEquals(response.code -> jsonBody(response), StatusCode.NotFound -> json("""{"message":"photo content not found"}"""))

  test("should return 500 when reading photo content fails"):
    val server   = buildPhotoApi(getContentResult = PhotoReadResult.ReadFailed(RuntimeException("store down")))
    val response = get(s"/photos/${photoUuid}/content", server)
    assertEquals(response.code -> jsonBody(response), StatusCode.InternalServerError -> json("""{"message":"photo content could not be read"}"""))

  private case class Refs(
      addedContents: AtomicReference[Vector[PhotoContent]] = AtomicReference(Vector.empty),
      removedIds: AtomicReference[Vector[PhotoId]] = AtomicReference(Vector.empty),
      requestedWindows: AtomicReference[Vector[PhotoWindow]] = AtomicReference(Vector.empty)
  )

  private def fakePlants(
      refs: Refs,
      addPhotoResult: AddPhotoResult,
      removePhotoResult: RemovePhotoResult,
      getPhotosResult: GetPhotosResult,
      getContentResult: PhotoReadResult
  ): Plants =
    new Plants:
      override def createPlant(species: Species, maybeNickname: Option[Nickname], location: Location, substrate: Substrate): CreatePlantResult =
        fail("photo HTTP must not create plants")
      override def getPlants(status: PlantStatus): GetPlantsResult                                  = fail("photo HTTP must not read plants")
      override def getArchivedCount: ArchivedCountResult                                            = fail("photo HTTP must not count plants")
      override def editPlant(plant: PlantId, revise: PlantDetails => PlantDetails): EditPlantResult = fail("photo HTTP must not edit plants")
      override def addPhoto(plant: PlantId, content: PhotoContent): AddPhotoResult                  =
        refs.addedContents.updateAndGet(_ :+ content).pipe(_ => addPhotoResult)
      override def removePhoto(photo: PhotoId): RemovePhotoResult =
        refs.removedIds.updateAndGet(_ :+ photo).pipe(_ => removePhotoResult)
      override def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult =
        refs.requestedWindows.updateAndGet(_ :+ window).pipe(_ => getPhotosResult)
      override def getPhotoContent(photo: PhotoId): PhotoReadResult = getContentResult

  private def buildPhotoApi(
      refs: Refs = Refs(),
      addPhotoResult: AddPhotoResult = AddPhotoResult.PlantMissing,
      removePhotoResult: RemovePhotoResult = RemovePhotoResult.PhotoMissing,
      getPhotosResult: GetPhotosResult = GetPhotosResult.Read(PhotoPage(Vector.empty, hasNextPage = false)),
      getContentResult: PhotoReadResult = PhotoReadResult.ContentMissing
  ) =
    val plants = fakePlants(refs, addPhotoResult, removePhotoResult, getPhotosResult, getContentResult)
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointsRunLogic(PhotoApi.serverEndpoints(using plants))
      .backend()

  private type TestServer = SttpBackend[Identity, Any]

  private def withLivePhotoServer[A](
      refs: Refs = Refs(),
      addPhotoResult: AddPhotoResult = AddPhotoResult.PlantMissing,
      removePhotoResult: RemovePhotoResult = RemovePhotoResult.PhotoMissing,
      getPhotosResult: GetPhotosResult = GetPhotosResult.Read(PhotoPage(Vector.empty, hasNextPage = false)),
      getContentResult: PhotoReadResult = PhotoReadResult.ContentMissing
  )(action: Int => A): A =
    val plants = fakePlants(refs, addPhotoResult, removePhotoResult, getPhotosResult, getContentResult)
    supervised:
      val binding =
        NettySyncServer().host("127.0.0.1").port(0).addEndpoints(PhotoApi.serverEndpoints(using plants)).start()
      try action(binding.port)
      finally binding.stop()

  private def uploadPhoto(plantId: String, bytes: Array[Byte], contentType: String)(port: Int) =
    basicRequest
      .post(uri"http://127.0.0.1:$port/plants/$plantId/photos")
      .multipartBody(multipart("file", bytes).contentType(contentType))
      .send(HttpClientSyncBackend())

  private def get(path: String, server: TestServer) =
    basicRequest.get(Uri.unsafeParse(s"http://test$path")).send(server)

  private def delete(path: String, server: TestServer) =
    basicRequest.delete(Uri.unsafeParse(s"http://test$path")).send(server)

  private def jsonBody(response: Response[Either[String, String]]) = json(response.body.merge)
  private def json(value: String)                                  = parse(value).fold(error => fail(error.message), identity)
