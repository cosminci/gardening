package gardening.adapters.http

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.pesticide.{AddPesticideResult, GetPesticidesResult, PesticideUpdateResult}
import gardening.usecases.PesticideCatalog
import io.circe.parser.parse
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{Response, SttpBackend, basicRequest}
import sttp.model.{StatusCode, Uri}
import sttp.shared.Identity
import sttp.tapir.server.stub.TapirStubInterpreter

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class PesticideApiSeamIntegrationTest extends munit.FunSuite:

  private val pesticideId           = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
  private val pesticideData         = PesticideData(PesticideName("Sulfur"), PesticideType.Fungicide, PesticideInfo("2g/L").some)
  private val pesticide             = Pesticide(pesticideId, pesticideData, PesticideStatus.Active)
  private val archivedPesticide     = pesticide.copy(status = PesticideStatus.Archived)
  private val pesticideDataJson     = """{"name":"Sulfur","type":"fungicide","info":"2g/L"}"""
  private val pesticideJson         = s"""{"id":"${pesticideId.value}","data":$pesticideDataJson,"status":"active"}"""
  private val archivedPesticideJson = s"""{"id":"${pesticideId.value}","data":$pesticideDataJson,"status":"archived"}"""
  private val catalogReadError      = """{"message":"pesticides could not be read"}"""
  private val catalogWriteError     = """{"message":"pesticide could not be saved"}"""
  private val archiveFailedError    = """{"message":"pesticide could not be archived"}"""

  test("should list and add pesticides with their existing wire shape"):
    val refs   = Refs()
    val server = buildServer(refs)

    val listed = get("/pesticides", server)
    val added  = post("/pesticides", pesticideDataJson, server)

    assertResponse(listed, StatusCode.Ok, s"[$pesticideJson]")
    assertResponse(added, StatusCode.Created, pesticideJson)
    assertEquals(refs.added.get(), Vector(pesticideData))

  test("should edit pesticides and reject invalid, missing, or archived identifiers"):
    val refs   = Refs()
    val server = buildServer(refs, editResult = PesticideUpdateResult.Updated(pesticide))

    val edited   = put(s"/pesticides/${pesticideId.value}", pesticideDataJson, server)
    val invalid  = put("/pesticides/not-a-uuid", pesticideDataJson, server)
    val missing  = put(s"/pesticides/${pesticideId.value}", pesticideDataJson, buildServer())
    val archived = put(s"/pesticides/${pesticideId.value}", pesticideDataJson, buildServer(editResult = PesticideUpdateResult.PesticideArchived))

    assertResponse(edited, StatusCode.Ok, pesticideJson)
    assertResponse(invalid, StatusCode.BadRequest, """{"message":"invalid pesticide id"}""")
    assertResponse(missing, StatusCode.NotFound, """{"message":"pesticide not found"}""")
    assertResponse(archived, StatusCode.Conflict, """{"message":"pesticide is archived"}""")
    assertEquals(refs.edited.get(), Vector(pesticideId -> pesticideData))

  test("should reject malformed pesticide data without invoking the catalog"):
    val refs   = Refs()
    val server = buildServer(refs)

    val invalidType = post("/pesticides", """{"name":"Sulfur","type":"unknown","info":null}""", server)
    val invalidBody = post("/pesticides", """{"type":"fungicide","info":null}""", server)

    assertEquals(invalidType.code, StatusCode.BadRequest)
    assertEquals(invalidBody.code, StatusCode.BadRequest)
    assertEquals(refs.added.get(), Vector.empty)

  test("should archive pesticides and reject unknown, already-archived, or invalid identifiers"):
    val refs   = Refs()
    val server = buildServer(refs, archiveResult = PesticideUpdateResult.Updated(archivedPesticide))

    val archived        = post(s"/pesticides/${pesticideId.value}/archive", "", server)
    val invalid         = post("/pesticides/not-a-uuid/archive", "", server)
    val missing         = post(s"/pesticides/${pesticideId.value}/archive", "", buildServer())
    val alreadyArchived =
      post(s"/pesticides/${pesticideId.value}/archive", "", buildServer(archiveResult = PesticideUpdateResult.PesticideArchived))

    assertResponse(archived, StatusCode.Ok, archivedPesticideJson)
    assertResponse(invalid, StatusCode.BadRequest, """{"message":"invalid pesticide id"}""")
    assertResponse(missing, StatusCode.NotFound, """{"message":"pesticide not found"}""")
    assertResponse(alreadyArchived, StatusCode.Conflict, """{"message":"pesticide is already archived"}""")
    assertEquals(refs.archived.get(), Vector(pesticideId))

  test("should hide pesticide storage failures"):
    val failure = RuntimeException("private details")
    val server  = buildServer(
      readResult = GetPesticidesResult.ReadFailed(failure),
      addResult = AddPesticideResult.AddFailed(failure),
      editResult = PesticideUpdateResult.UpdateFailed(failure),
      archiveResult = PesticideUpdateResult.UpdateFailed(failure)
    )

    val listed   = get("/pesticides", server)
    val added    = post("/pesticides", pesticideDataJson, server)
    val edited   = put(s"/pesticides/${pesticideId.value}", pesticideDataJson, server)
    val archived = post(s"/pesticides/${pesticideId.value}/archive", "", server)

    assertResponse(listed, StatusCode.InternalServerError, catalogReadError)
    assertResponse(added, StatusCode.InternalServerError, catalogWriteError)
    assertResponse(edited, StatusCode.InternalServerError, catalogWriteError)
    assertResponse(archived, StatusCode.InternalServerError, archiveFailedError)

  private case class Refs(
      added: AtomicReference[Vector[PesticideData]] = AtomicReference(Vector.empty),
      edited: AtomicReference[Vector[(PesticideId, PesticideData)]] = AtomicReference(Vector.empty),
      archived: AtomicReference[Vector[PesticideId]] = AtomicReference(Vector.empty)
  )

  private def buildServer(
      refs: Refs = Refs(),
      readResult: GetPesticidesResult = GetPesticidesResult.Read(Vector(pesticide)),
      addResult: AddPesticideResult = AddPesticideResult.Added(pesticide),
      editResult: PesticideUpdateResult = PesticideUpdateResult.PesticideMissing,
      archiveResult: PesticideUpdateResult = PesticideUpdateResult.PesticideMissing
  ) =
    val catalog = new PesticideCatalog:
      override def getPesticides: GetPesticidesResult                    = readResult
      override def addPesticide(data: PesticideData): AddPesticideResult =
        refs.added.updateAndGet(_ :+ data).pipe(_ => addResult)
      override def editPesticide(id: PesticideId, data: PesticideData): PesticideUpdateResult =
        refs.edited.updateAndGet(_ :+ (id -> data)).pipe(_ => editResult)
      override def archivePesticide(id: PesticideId): PesticideUpdateResult =
        refs.archived.updateAndGet(_ :+ id).pipe(_ => archiveResult)
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointsRunLogic(PesticideApi.serverEndpoints(using catalog))
      .backend()

  private type TestServer = SttpBackend[Identity, Any]

  private def get(path: String, server: TestServer) =
    basicRequest.get(Uri.unsafeParse(s"http://test$path")).send(server)

  private def post(path: String, body: String, server: TestServer) =
    basicRequest.post(Uri.unsafeParse(s"http://test$path")).body(body).contentType("application/json").send(server)

  private def put(path: String, body: String, server: TestServer) =
    basicRequest.put(Uri.unsafeParse(s"http://test$path")).body(body).contentType("application/json").send(server)

  private def jsonBody(response: Response[Either[String, String]]) = json(response.body.merge)
  private def json(value: String)                                  = parse(value).fold(error => fail(error.message), identity)
  private def assertResponse(response: Response[Either[String, String]], status: StatusCode, body: String) =
    assertEquals(response.code -> jsonBody(response), status -> json(body))
