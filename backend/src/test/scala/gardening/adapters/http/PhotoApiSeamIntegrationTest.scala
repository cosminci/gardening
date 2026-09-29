package gardening.adapters.http

import gardening.domain.*
import gardening.domain.plants.*
import gardening.usecases.PhotoManager
import gardening.domain.plants.PhotoMediaType.*
import io.circe.parser.parse
import io.github.iltotore.iron.autoRefine
import ox.{discard, supervised}
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
  // version (binary parts decode as corrupt), so upload tests run against a real Netty server. All
  // upload scenarios share a single live server: starting/stopping a real Netty server per test
  // (as this suite used to) is dominated by Netty's graceful-shutdown wait, and multiplying that by
  // one test per scenario made this suite the single largest cost in the whole build.
  test("should handle photo upload status codes and side effects over a live server"):
    val observedContent   = AtomicReference[Option[PhotoContent]](None)
    val observedIdemKey   = AtomicReference[Option[String]](None)
    val addPhotoResultRef = AtomicReference[AddPhotoResult](AddPhotoResult.Added(photo))
    val photos            = new PhotoManager:
      override def addPhoto(plant: PlantId, content: PhotoContent, idempotencyKey: String): AddPhotoResult =
        observedContent.set(Some(content))
        observedIdemKey.set(Some(idempotencyKey))
        addPhotoResultRef.get()
      override def removePhoto(photo: PhotoId): RemovePhotoResult                          = sys.error("not exercised by upload scenarios")
      override def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult         = sys.error("not exercised by upload scenarios")
      override def getPhotoContent(photo: PhotoId, variant: PhotoVariant): PhotoReadResult =
        sys.error("not exercised by upload scenarios")

    supervised:
      val binding =
        NettySyncServer()
          .modifyConfig(_.noGracefulShutdown)
          .host("127.0.0.1")
          .port(0)
          .addEndpoints(PhotoApi.serverEndpoints(using photos))
          .start()
      try
        val jpegResponse = uploadPhoto(plantId.value, jpegBytes, "image/jpeg", idempotencyKey = "upload-1")(binding.port)
        assertEquals(jpegResponse.code -> jsonBody(jpegResponse), StatusCode.Created -> json(addedJson))
        assertEquals(observedContent.get().map(_.mediaType), Some(Jpeg))
        assertEquals(observedIdemKey.get(), Some("upload-1"))

        val pngResponse = uploadPhoto(plantId.value, pngBytes, "image/png")(binding.port)
        assertEquals(pngResponse.code, StatusCode.Created)

        val jpgAliasResponse = uploadPhoto(plantId.value, jpegBytes, "image/jpg")(binding.port)
        assertEquals(jpgAliasResponse.code, StatusCode.Created)

        val webpResponse = uploadPhoto(plantId.value, webpBytes, "image/webp")(binding.port)
        assertEquals(webpResponse.code, StatusCode.UnsupportedMediaType)

        val bmpResponse = uploadPhoto(plantId.value, Array[Byte](1, 2), "image/bmp")(binding.port)
        assertEquals(
          bmpResponse.code                -> jsonBody(bmpResponse),
          StatusCode.UnsupportedMediaType -> json("""{"message":"unsupported media type: only image/jpeg and image/png are accepted"}""")
        )

        val oversized         = Array.fill(20 * 1024 * 1024 + 1)(0.toByte)
        val oversizedResponse = uploadPhoto(plantId.value, oversized, "image/jpeg")(binding.port)
        assertEquals(
          oversizedResponse.code     -> jsonBody(oversizedResponse),
          StatusCode.PayloadTooLarge -> json("""{"message":"photo exceeds the 20 MiB size limit"}""")
        )

        addPhotoResultRef.set(AddPhotoResult.PlantMissing)
        val missingPlantResponse = uploadPhoto(plantId.value, jpegBytes, "image/jpeg")(binding.port)
        assertEquals(
          missingPlantResponse.code -> jsonBody(missingPlantResponse),
          StatusCode.NotFound       -> json("""{"message":"plant not found"}""")
        )

        addPhotoResultRef.set(AddPhotoResult.AddFailed(RuntimeException("store down")))
        val failedResponse = uploadPhoto(plantId.value, jpegBytes, "image/jpeg")(binding.port)
        assertEquals(
          failedResponse.code            -> jsonBody(failedResponse),
          StatusCode.InternalServerError -> json("""{"message":"photo could not be added"}""")
        )
      finally binding.stop()

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

    val jpegServer = buildPhotoApi(getContentResult = PhotoReadResult.Read(jpegContent))
    val pngServer  = buildPhotoApi(getContentResult = PhotoReadResult.Read(pngContent))

    val jpegResponse = get(s"/photos/${photoUuid}/content", jpegServer)
    val pngResponse  = get(s"/photos/${photoUuid}/content", pngServer)

    assertEquals(jpegResponse.code, StatusCode.Ok)
    assertEquals(jpegResponse.header(HeaderNames.ContentType), Some("image/jpeg"))
    assertEquals(pngResponse.header(HeaderNames.ContentType), Some("image/png"))

  test("should default to the original variant and accept both variants requested explicitly"):
    val refs          = Refs()
    val defaultServer = buildPhotoApi(refs, getContentResult = PhotoReadResult.Read(PhotoContent(ByteVector(jpegBytes), Jpeg)))
    val _             = get(s"/photos/${photoUuid}/content", defaultServer)
    val _             = get(s"/photos/${photoUuid}/content?variant=original", defaultServer)
    val _             = get(s"/photos/${photoUuid}/content?variant=thumbnail", defaultServer)
    assertEquals(refs.requestedVariants.get(), Vector(PhotoVariant.Original, PhotoVariant.Original, PhotoVariant.Thumbnail))

  test("should return 400 for an unrecognized variant"):
    val server = buildPhotoApi()
    assertEquals(get(s"/photos/${photoUuid}/content?variant=huge", server).code, StatusCode.BadRequest)

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
      addedIdempotencyKeys: AtomicReference[Vector[String]] = AtomicReference(Vector.empty),
      removedIds: AtomicReference[Vector[PhotoId]] = AtomicReference(Vector.empty),
      requestedWindows: AtomicReference[Vector[PhotoWindow]] = AtomicReference(Vector.empty),
      requestedVariants: AtomicReference[Vector[PhotoVariant]] = AtomicReference(Vector.empty)
  )

  private def fakePhotos(
      refs: Refs,
      addPhotoResult: AddPhotoResult,
      removePhotoResult: RemovePhotoResult,
      getPhotosResult: GetPhotosResult,
      getContentResult: PhotoReadResult
  ): PhotoManager =
    new PhotoManager:
      override def addPhoto(plant: PlantId, content: PhotoContent, idempotencyKey: String): AddPhotoResult =
        refs.addedContents.updateAndGet(_ :+ content).discard
        refs.addedIdempotencyKeys.updateAndGet(_ :+ idempotencyKey).pipe(_ => addPhotoResult)
      override def removePhoto(photo: PhotoId): RemovePhotoResult =
        refs.removedIds.updateAndGet(_ :+ photo).pipe(_ => removePhotoResult)
      override def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult =
        refs.requestedWindows.updateAndGet(_ :+ window).pipe(_ => getPhotosResult)
      override def getPhotoContent(photo: PhotoId, variant: PhotoVariant): PhotoReadResult =
        refs.requestedVariants.updateAndGet(_ :+ variant).pipe(_ => getContentResult)

  private def buildPhotoApi(
      refs: Refs = Refs(),
      addPhotoResult: AddPhotoResult = AddPhotoResult.PlantMissing,
      removePhotoResult: RemovePhotoResult = RemovePhotoResult.PhotoMissing,
      getPhotosResult: GetPhotosResult = GetPhotosResult.Read(PhotoPage(Vector.empty, hasNextPage = false)),
      getContentResult: PhotoReadResult = PhotoReadResult.ContentMissing
  ) =
    val photos = fakePhotos(refs, addPhotoResult, removePhotoResult, getPhotosResult, getContentResult)
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointsRunLogic(PhotoApi.serverEndpoints(using photos))
      .backend()

  private type TestServer = SttpBackend[Identity, Any]

  private def uploadPhoto(plantId: String, bytes: Array[Byte], contentType: String, idempotencyKey: String = "idem-1")(port: Int) =
    basicRequest
      .post(uri"http://127.0.0.1:$port/plants/$plantId/photos")
      .multipartBody(multipart("file", bytes).contentType(contentType), multipart("idempotencyKey", idempotencyKey))
      .send(HttpClientSyncBackend())

  private def get(path: String, server: TestServer) =
    basicRequest.get(Uri.unsafeParse(s"http://test$path")).send(server)

  private def delete(path: String, server: TestServer) =
    basicRequest.delete(Uri.unsafeParse(s"http://test$path")).send(server)

  private def jsonBody(response: Response[Either[String, String]]) = json(response.body.merge)
  private def json(value: String)                                  = parse(value).fold(error => fail(error.message), identity)
