package gardening.adapters.http

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.PesticideCatalog
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

  private val pesticideId       = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
  private val pesticideData     = PesticideData(NomenclatureName("Sulfur"), PesticideType.Fungicide, NomenclatureInfo("2g/L").some)
  private val pesticide         = Pesticide(pesticideId, pesticideData)
  private val pesticideDataJson = """{"name":"Sulfur","type":"fungicide","info":"2g/L"}"""
  private val pesticideJson     = s"""{"id":"${pesticideId.value}","data":$pesticideDataJson}"""
  private val catalogReadError  = """{"message":"nomenclatures could not be read"}"""
  private val catalogWriteError = """{"message":"nomenclature could not be saved"}"""

  test("should list and add pesticides with their existing wire shape"):
    val refs   = Refs()
    val server = buildServer(refs)

    val listed = get("/pesticides", server)
    val added  = post("/pesticides", pesticideDataJson, server)

    assertResponse(listed, StatusCode.Ok, s"[$pesticideJson]")
    assertResponse(added, StatusCode.Created, pesticideJson)
    assertEquals(refs.added.get(), Vector(pesticideData))

  test("should edit pesticides and reject invalid or missing identifiers"):
    val refs   = Refs()
    val server = buildServer(refs, editResult = CatalogEditResult.Edited(pesticide))

    val edited  = put(s"/pesticides/${pesticideId.value}", pesticideDataJson, server)
    val invalid = put("/pesticides/not-a-uuid", pesticideDataJson, server)
    val missing = put(s"/pesticides/${pesticideId.value}", pesticideDataJson, buildServer())

    assertResponse(edited, StatusCode.Ok, pesticideJson)
    assertResponse(invalid, StatusCode.BadRequest, """{"message":"invalid nomenclature id"}""")
    assertResponse(missing, StatusCode.NotFound, """{"message":"nomenclature not found"}""")
    assertEquals(refs.edited.get(), Vector(pesticideId -> pesticideData))

  test("should reject malformed pesticide data without invoking the catalog"):
    val refs   = Refs()
    val server = buildServer(refs)

    val invalidType = post("/pesticides", """{"name":"Sulfur","type":"unknown","info":null}""", server)
    val invalidBody = post("/pesticides", """{"type":"fungicide","info":null}""", server)

    assertEquals(invalidType.code, StatusCode.BadRequest)
    assertEquals(invalidBody.code, StatusCode.BadRequest)
    assertEquals(refs.added.get(), Vector.empty)

  test("should hide pesticide storage failures"):
    val failure = RuntimeException("private details")
    val server  = buildServer(
      readResult = CatalogReadResult.ReadFailed(failure),
      addResult = CatalogAddResult.AddFailed(failure),
      editResult = CatalogEditResult.EditFailed(failure)
    )

    val listed = get("/pesticides", server)
    val added  = post("/pesticides", pesticideDataJson, server)
    val edited = put(s"/pesticides/${pesticideId.value}", pesticideDataJson, server)

    assertResponse(listed, StatusCode.InternalServerError, catalogReadError)
    assertResponse(added, StatusCode.InternalServerError, catalogWriteError)
    assertResponse(edited, StatusCode.InternalServerError, catalogWriteError)

  private case class Refs(
      added: AtomicReference[Vector[PesticideData]] = AtomicReference(Vector.empty),
      edited: AtomicReference[Vector[(PesticideId, PesticideData)]] = AtomicReference(Vector.empty)
  )

  private def buildServer(
      refs: Refs = Refs(),
      readResult: CatalogReadResult[Pesticide] = CatalogReadResult.Read(Vector(pesticide)),
      addResult: CatalogAddResult[Pesticide] = CatalogAddResult.Added(pesticide),
      editResult: CatalogEditResult[Pesticide] = CatalogEditResult.RecordMissing
  ) =
    val catalog = new PesticideCatalog:
      override def getPesticides: CatalogReadResult[Pesticide]                    = readResult
      override def addPesticide(data: PesticideData): CatalogAddResult[Pesticide] =
        refs.added.updateAndGet(_ :+ data).pipe(_ => addResult)
      override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
        refs.edited.updateAndGet(_ :+ (id -> data)).pipe(_ => editResult)
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
