package gardening.adapters.http

import cats.syntax.option.*
import io.github.iltotore.iron.autoRefine
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.substrate.SubstrateComponentUpdateResult
import gardening.usecases.{AddSubstrateMixResult, SubstrateCatalog}
import io.circe.parser.parse
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{Response, SttpBackend, basicRequest}
import sttp.model.{StatusCode, Uri}
import sttp.shared.Identity
import sttp.tapir.server.stub.TapirStubInterpreter

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class SubstrateApiSeamIntegrationTest extends munit.FunSuite:

  private val componentId           = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val componentData         = SubstrateComponentData(SubstrateComponentName("Pumice"), SubstrateComponentInfo("porous").some)
  private val component             = SubstrateComponent(componentId, componentData, SubstrateComponentStatus.Active)
  private val archivedComponent     = component.copy(status = SubstrateComponentStatus.Archived)
  private val componentDataJson     = """{"name":"Pumice","info":"porous"}"""
  private val componentJson         = s"""{"id":"${componentId.value}","data":$componentDataJson,"status":"active"}"""
  private val archivedComponentJson = s"""{"id":"${componentId.value}","data":$componentDataJson,"status":"archived"}"""
  private val componentReadError    = """{"message":"substrate components could not be read"}"""
  private val componentWriteError   = """{"message":"substrate component could not be saved"}"""
  private val archiveFailedError    = """{"message":"substrate component could not be archived"}"""

  private val mixId       = UUID.fromString("20000000-0000-4000-8000-000000000001")
  private val substrate   = Substrate.of(List(SubstratePart(componentId, share = 100))).getOrElse(fail("invalid test substrate"))
  private val mix         = SubstrateMix(mixId, SubstrateMixName("Cactus mix"), SubstrateMixNotes("Free-draining").some, substrate)
  private val mixDataJson = s"""{"name":"Cactus mix","notes":"Free-draining","substrate":[{"componentId":"${componentId.value}","share":100}]}"""
  private val mixJson     =
    s"""{"id":"$mixId","name":"Cactus mix","notes":"Free-draining","substrate":[{"componentId":"${componentId.value}","share":100}]}"""
  private val mixReadError   = """{"message":"substrate mixes could not be read"}"""
  private val mixWriteError  = """{"message":"substrate mix could not be saved"}"""
  private val mixDeleteError = """{"message":"substrate mix could not be deleted"}"""

  test("should list and add substrate components at their resource path"):
    val refs   = Refs()
    val server = buildServer(refs)

    val listed = get("/substrates/components", server)
    val added  = post("/substrates/components", componentDataJson, server)
    val legacy = get("/substrate/components", server)

    assertResponse(listed, StatusCode.Ok, s"[$componentJson]")
    assertResponse(added, StatusCode.Created, componentJson)
    assertEquals(legacy.code, StatusCode.NotFound)
    assertEquals(refs.addedComponents.get(), Vector(componentData))

  test("should edit substrate components and reject invalid, missing, or archived identifiers"):
    val refs   = Refs()
    val server = buildServer(refs, editResult = SubstrateComponentUpdateResult.Updated(component))

    val edited   = put(s"/substrates/components/${componentId.value}", componentDataJson, server)
    val invalid  = put("/substrates/components/not-a-uuid", componentDataJson, server)
    val missing  = put(s"/substrates/components/${componentId.value}", componentDataJson, buildServer())
    val archived =
      put(
        s"/substrates/components/${componentId.value}",
        componentDataJson,
        buildServer(editResult = SubstrateComponentUpdateResult.ComponentArchived)
      )

    assertResponse(edited, StatusCode.Ok, componentJson)
    assertResponse(invalid, StatusCode.BadRequest, """{"message":"invalid substrate component id"}""")
    assertResponse(missing, StatusCode.NotFound, """{"message":"substrate component not found"}""")
    assertResponse(archived, StatusCode.Conflict, """{"message":"substrate component is archived"}""")
    assertEquals(refs.editedComponents.get(), Vector(componentId -> componentData))

  test("should archive substrate components and reject unknown, already-archived, or invalid identifiers"):
    val refs   = Refs()
    val server = buildServer(refs, archiveResult = SubstrateComponentUpdateResult.Updated(archivedComponent))

    val archived        = post(s"/substrates/components/${componentId.value}/archive", "", server)
    val invalid         = post("/substrates/components/not-a-uuid/archive", "", server)
    val missing         = post(s"/substrates/components/${componentId.value}/archive", "", buildServer())
    val alreadyArchived = post(
      s"/substrates/components/${componentId.value}/archive",
      "",
      buildServer(archiveResult = SubstrateComponentUpdateResult.ComponentArchived)
    )

    assertResponse(archived, StatusCode.Ok, archivedComponentJson)
    assertResponse(invalid, StatusCode.BadRequest, """{"message":"invalid substrate component id"}""")
    assertResponse(missing, StatusCode.NotFound, """{"message":"substrate component not found"}""")
    assertResponse(alreadyArchived, StatusCode.Conflict, """{"message":"substrate component is already archived"}""")
    assertEquals(refs.archivedComponents.get(), Vector(componentId))

  test("should hide substrate component catalog storage failures"):
    val failure = RuntimeException("private details")
    val server  = buildServer(
      readResult = CatalogReadResult.ReadFailed(failure),
      addResult = CatalogAddResult.AddFailed(failure),
      editResult = SubstrateComponentUpdateResult.UpdateFailed(failure),
      archiveResult = SubstrateComponentUpdateResult.UpdateFailed(failure)
    )

    val listed   = get("/substrates/components", server)
    val added    = post("/substrates/components", componentDataJson, server)
    val edited   = put(s"/substrates/components/${componentId.value}", componentDataJson, server)
    val archived = post(s"/substrates/components/${componentId.value}/archive", "", server)

    assertResponse(listed, StatusCode.InternalServerError, componentReadError)
    assertResponse(added, StatusCode.InternalServerError, componentWriteError)
    assertResponse(edited, StatusCode.InternalServerError, componentWriteError)
    assertResponse(archived, StatusCode.InternalServerError, archiveFailedError)

  test("should list, save, and permanently delete substrate mixes at their shared resource path"):
    val refs   = Refs()
    val server = buildServer(refs, mixAddResult = AddSubstrateMixResult.Added(mix))

    val listed  = get("/substrates/mixes", server)
    val added   = post("/substrates/mixes", mixDataJson, server)
    val deleted = delete(s"/substrates/mixes/$mixId", server)

    assertResponse(listed, StatusCode.Ok, s"[$mixJson]")
    assertResponse(added, StatusCode.Created, mixJson)
    assertEquals(deleted.code, StatusCode.NoContent)
    assertEquals(refs.deletedMixes.get(), Vector(mixId))

  test("should reject saving a substrate mix with the same components and shares as an existing one"):
    val server = buildServer(mixAddResult = AddSubstrateMixResult.DuplicateSubstrate)

    val added = post("/substrates/mixes", mixDataJson, server)

    assertResponse(added, StatusCode.Conflict, """{"message":"a substrate mix with these components already exists"}""")

  test("should reject saving a substrate mix with an invalid substrate"):
    val server = buildServer()

    val added = post("/substrates/mixes", """{"name":"Cactus mix","notes":null,"substrate":[]}""", server)

    assertEquals(added.code, StatusCode.BadRequest)

  test("should reject deleting a substrate mix with an invalid identifier"):
    val server = buildServer()

    val deleted = delete("/substrates/mixes/not-a-uuid", server)

    assertResponse(deleted, StatusCode.BadRequest, """{"message":"invalid substrate mix id"}""")

  test("should hide substrate mix catalog storage failures"):
    val failure = RuntimeException("private details")
    val server  = buildServer(
      mixReadResult = CatalogReadResult.ReadFailed(failure),
      mixAddResult = AddSubstrateMixResult.AddFailed(failure),
      mixDeleteResult = CatalogDeleteResult.DeleteFailed(failure)
    )

    val listed  = get("/substrates/mixes", server)
    val added   = post("/substrates/mixes", mixDataJson, server)
    val deleted = delete(s"/substrates/mixes/$mixId", server)

    assertResponse(listed, StatusCode.InternalServerError, mixReadError)
    assertResponse(added, StatusCode.InternalServerError, mixWriteError)
    assertResponse(deleted, StatusCode.InternalServerError, mixDeleteError)

  private case class Refs(
      addedComponents: AtomicReference[Vector[SubstrateComponentData]] = AtomicReference(Vector.empty),
      editedComponents: AtomicReference[Vector[(SubstrateComponentId, SubstrateComponentData)]] = AtomicReference(Vector.empty),
      archivedComponents: AtomicReference[Vector[SubstrateComponentId]] = AtomicReference(Vector.empty),
      deletedMixes: AtomicReference[Vector[UUID]] = AtomicReference(Vector.empty)
  )

  private def buildServer(
      refs: Refs = Refs(),
      readResult: CatalogReadResult[SubstrateComponent] = CatalogReadResult.Read(Vector(component)),
      addResult: CatalogAddResult[SubstrateComponent] = CatalogAddResult.Added(component),
      editResult: SubstrateComponentUpdateResult = SubstrateComponentUpdateResult.ComponentMissing,
      archiveResult: SubstrateComponentUpdateResult = SubstrateComponentUpdateResult.ComponentMissing,
      mixReadResult: CatalogReadResult[SubstrateMix] = CatalogReadResult.Read(Vector(mix)),
      mixAddResult: AddSubstrateMixResult = AddSubstrateMixResult.Added(mix),
      mixDeleteResult: CatalogDeleteResult = CatalogDeleteResult.Deleted
  ) =
    val catalog = new SubstrateCatalog:
      override def getSubstrateComponents: CatalogReadResult[SubstrateComponent]                             = readResult
      override def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent] =
        refs.addedComponents.updateAndGet(_ :+ data).pipe(_ => addResult)
      override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): SubstrateComponentUpdateResult =
        refs.editedComponents.updateAndGet(_ :+ (id -> data)).pipe(_ => editResult)
      override def archiveSubstrateComponent(id: SubstrateComponentId): SubstrateComponentUpdateResult =
        refs.archivedComponents.updateAndGet(_ :+ id).pipe(_ => archiveResult)
      override def getSubstrateMixes: CatalogReadResult[SubstrateMix] = mixReadResult
      override def addSubstrateMix(name: SubstrateMixName, notes: Option[SubstrateMixNotes], substrate: Substrate): AddSubstrateMixResult =
        mixAddResult
      override def deleteSubstrateMix(id: UUID): CatalogDeleteResult =
        refs.deletedMixes.updateAndGet(_ :+ id).pipe(_ => mixDeleteResult)
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointsRunLogic(SubstrateApi.serverEndpoints(using catalog))
      .backend()

  private type TestServer = SttpBackend[Identity, Any]

  private def get(path: String, server: TestServer) =
    basicRequest.get(Uri.unsafeParse(s"http://test$path")).send(server)

  private def post(path: String, body: String, server: TestServer) =
    basicRequest.post(Uri.unsafeParse(s"http://test$path")).body(body).contentType("application/json").send(server)

  private def put(path: String, body: String, server: TestServer) =
    basicRequest.put(Uri.unsafeParse(s"http://test$path")).body(body).contentType("application/json").send(server)

  private def delete(path: String, server: TestServer) =
    basicRequest.delete(Uri.unsafeParse(s"http://test$path")).send(server)

  private def jsonBody(response: Response[Either[String, String]]) = json(response.body.merge)
  private def json(value: String)                                  = parse(value).fold(error => fail(error.message), identity)
  private def assertResponse(response: Response[Either[String, String]], status: StatusCode, body: String) =
    assertEquals(response.code -> jsonBody(response), status -> json(body))
