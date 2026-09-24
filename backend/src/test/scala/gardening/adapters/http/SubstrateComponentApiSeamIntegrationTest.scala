package gardening.adapters.http

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.substrate.SubstrateComponentCatalog
import io.circe.parser.parse
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{Response, SttpBackend, basicRequest}
import sttp.model.{StatusCode, Uri}
import sttp.shared.Identity
import sttp.tapir.server.stub.TapirStubInterpreter

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class SubstrateComponentApiSeamIntegrationTest extends munit.FunSuite:

  private val componentId       = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val componentData     = SubstrateComponentData(NomenclatureName("Pumice"), NomenclatureInfo("porous").some)
  private val component         = SubstrateComponent(componentId, componentData)
  private val componentDataJson = """{"name":"Pumice","info":"porous"}"""
  private val componentJson     = s"""{"id":"${componentId.value}","data":$componentDataJson}"""
  private val catalogReadError  = """{"message":"nomenclatures could not be read"}"""
  private val catalogWriteError = """{"message":"nomenclature could not be saved"}"""

  test("should list and add substrate components at their resource path"):
    val refs = Refs(
      readResult = CatalogReadResult.Read(Vector(component)),
      addResult = CatalogAddResult.Added(component)
    )
    val server = buildServer(refs)

    val listed = get("/substrate/components", server)
    val added  = post("/substrate/components", componentDataJson, server)
    val legacy = get("/substrate-components", server)

    assertResponse(listed, StatusCode.Ok, s"[$componentJson]")
    assertResponse(added, StatusCode.Created, componentJson)
    assertEquals(legacy.code, StatusCode.NotFound)
    assertEquals(refs.added.get(), Vector(componentData))

  test("should edit substrate components and reject invalid or missing identifiers"):
    val refs   = Refs(editResult = CatalogEditResult.Edited(component))
    val server = buildServer(refs)

    val edited  = put(s"/substrate/components/${componentId.value}", componentDataJson, server)
    val invalid = put("/substrate/components/not-a-uuid", componentDataJson, server)
    val missing = put(s"/substrate/components/${componentId.value}", componentDataJson, buildServer(Refs()))

    assertResponse(edited, StatusCode.Ok, componentJson)
    assertResponse(invalid, StatusCode.BadRequest, """{"message":"invalid nomenclature id"}""")
    assertResponse(missing, StatusCode.NotFound, """{"message":"nomenclature not found"}""")
    assertEquals(refs.edited.get(), Vector(componentId -> componentData))

  test("should hide substrate catalog storage failures"):
    val failure = RuntimeException("private details")
    val refs    = Refs(
      readResult = CatalogReadResult.ReadFailed(failure),
      addResult = CatalogAddResult.AddFailed(failure),
      editResult = CatalogEditResult.EditFailed(failure)
    )
    val server = buildServer(refs)

    val listed = get("/substrate/components", server)
    val added  = post("/substrate/components", componentDataJson, server)
    val edited = put(s"/substrate/components/${componentId.value}", componentDataJson, server)

    assertResponse(listed, StatusCode.InternalServerError, catalogReadError)
    assertResponse(added, StatusCode.InternalServerError, catalogWriteError)
    assertResponse(edited, StatusCode.InternalServerError, catalogWriteError)

  private case class Refs(
      readResult: CatalogReadResult[SubstrateComponent] = CatalogReadResult.Read(Vector.empty),
      addResult: CatalogAddResult[SubstrateComponent] = CatalogAddResult.Added(component),
      editResult: CatalogEditResult[SubstrateComponent] = CatalogEditResult.RecordMissing,
      added: AtomicReference[Vector[SubstrateComponentData]] = AtomicReference(Vector.empty),
      edited: AtomicReference[Vector[(SubstrateComponentId, SubstrateComponentData)]] = AtomicReference(Vector.empty)
  )

  private def buildServer(refs: Refs) =
    val catalog = new SubstrateComponentCatalog:
      override def getSubstrateComponents: CatalogReadResult[SubstrateComponent]                             = refs.readResult
      override def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent] =
        refs.added.updateAndGet(_ :+ data).pipe(_ => refs.addResult)
      override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
        refs.edited.updateAndGet(_ :+ (id -> data)).pipe(_ => refs.editResult)
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointsRunLogic(SubstrateComponentApi.serverEndpoints(using catalog))
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
