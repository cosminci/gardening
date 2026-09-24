package gardening.adapters.http

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.domain.journal.*
import io.circe.parser.parse
import io.github.iltotore.iron.autoRefine
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{Response, SttpBackend, basicRequest}
import sttp.model.{StatusCode, Uri}
import sttp.shared.Identity
import sttp.tapir.server.stub.TapirStubInterpreter

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

class PlantApiSeamIntegrationTest extends munit.FunSuite:

  private val date      = Instant.parse("2026-01-01T00:00:00Z")
  private val perliteId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val substrate = Substrate.of(List(SubstratePart(perliteId, 100))).getOrElse(fail("invalid substrate"))
  private val plant     = Plant(
    PlantId("p1"),
    PlantDetails(Species("Ficus lyrata"), Nickname("Fern").some, Location("Balcony"), substrate, PlantStatus.Active)
  )

  test("should create a plant without refreshing attention and distinguish creation errors"):
    val body = s"""{"species":"Ficus lyrata","nickname":"Fern","location":"Balcony","substrate":[{"componentId":"${perliteId.value}","share":100}]}"""
    val createdRefs = Refs(createPlantResult = CreatePlantResult.Created(plant))
    val unknownRefs = Refs(createPlantResult = CreatePlantResult.UnknownComponent)
    val catalogRefs = Refs(createPlantResult = CreatePlantResult.CatalogReadFailed(RuntimeException("secret")))
    val failedRefs  = Refs(createPlantResult = CreatePlantResult.CreateFailed(RuntimeException("secret")))

    val created = post(body, buildPlantApi(createdRefs))
    val unknown = post(body, buildPlantApi(unknownRefs))
    val catalog = post(body, buildPlantApi(catalogRefs))
    val failed  = post(body, buildPlantApi(failedRefs))

    val expectedPlant = json(
      s"""{"id":"p1","details":{"species":"Ficus lyrata","nickname":"Fern","location":"Balcony","substrate":[{"componentId":"${perliteId.value}","share":100}],"status":"active"}}"""
    )
    assertEquals(created.code -> jsonBody(created), StatusCode.Created             -> expectedPlant)
    assertEquals(unknown.code -> jsonBody(unknown), StatusCode.UnprocessableEntity -> json("""{"message":"unknown substrate component"}"""))
    assertEquals(catalog.code -> jsonBody(catalog), StatusCode.ServiceUnavailable  -> json("""{"message":"substrate catalog could not be read"}"""))
    assertEquals(failed.code  -> jsonBody(failed), StatusCode.InternalServerError  -> json("""{"message":"plant could not be created"}"""))
    assertEquals(createdRefs.refreshCalls.get(), 0)
    assertEquals(createdRefs.createdDetails.get(), Vector(plant.details))

  test("should reject invalid creation input before reaching the journal"):
    val refs    = Refs()
    val invalid =
      """{"species":" ","nickname":null,"location":"Balcony","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":100}]}"""

    val response = post(invalid, buildPlantApi(refs))

    assertEquals(response.code -> jsonBody(response), StatusCode.BadRequest -> json("""{"message":"plant details must not be blank"}"""))
    assertEquals(refs.createdDetails.get(), Vector.empty)

  test("should list active plants by default and archived plants on request"):
    val archived     = plant.copy(id = PlantId("archived"), details = plant.details.copy(status = PlantStatus.Archived))
    val activeRefs   = Refs(plantsResult = GetPlantsResult.Read(Vector(plant)))
    val archivedRefs = Refs(plantsResult = GetPlantsResult.Read(Vector(archived)))

    val activeResponse   = get("/plants", buildPlantApi(activeRefs))
    val archivedResponse = get("/plants?status=archived", buildPlantApi(archivedRefs))
    val invalidResponse  = get("/plants?status=unknown", buildPlantApi(activeRefs))

    val expectedActive =
      json(
        s"""[{"id":"p1","details":{"species":"Ficus lyrata","nickname":"Fern","location":"Balcony","substrate":[{"componentId":"${perliteId.value}","share":100}],"status":"active"}}]"""
      )
    val expectedArchived =
      json(
        s"""[{"id":"archived","details":{"species":"Ficus lyrata","nickname":"Fern","location":"Balcony","substrate":[{"componentId":"${perliteId.value}","share":100}],"status":"archived"}}]"""
      )
    assertEquals(activeResponse.code, StatusCode.Ok)
    assertEquals(archivedResponse.code, StatusCode.Ok)
    assertEquals(jsonBody(activeResponse), expectedActive)
    assertEquals(jsonBody(archivedResponse), expectedArchived)
    assertEquals(invalidResponse.code, StatusCode.BadRequest)
    assertEquals(activeRefs.requestedStatuses.get(), Vector(PlantStatus.Active))
    assertEquals(archivedRefs.requestedStatuses.get(), Vector(PlantStatus.Archived))

  test("should return a read error when plant details cannot be loaded"):
    val refs = Refs(plantsResult = GetPlantsResult.ReadFailed(RuntimeException("offline")))

    val response = get("/plants", buildPlantApi(refs))

    assertEquals(response.code, StatusCode.InternalServerError)

  test("should read the archived count without loading archived plants"):
    val refs = Refs(archivedCountResult = ArchivedCountResult.Counted(3))

    val response = get("/plants/archived/count", buildPlantApi(refs))

    val expected = StatusCode.Ok -> json("""{"count":3}""")
    assertEquals(response.code -> jsonBody(response), expected)
    assertEquals(refs.requestedStatuses.get(), Vector.empty)

  test("should surface archived-count read failure without guessing a count"):
    val refs = Refs(archivedCountResult = ArchivedCountResult.ReadFailed(RuntimeException("offline")))

    val response = get("/plants/archived/count", buildPlantApi(refs))

    val expected = StatusCode.InternalServerError -> json("""{"message":"archived count could not be read"}""")
    assertEquals(response.code -> jsonBody(response), expected)

  test("should archive a plant and distinguish missing, already archived, and failed writes"):
    val archivePath       = s"/plants/${plant.id.value}"
    val archivePatch      = """[{"op":"replace","path":"/details/status","value":"archived"}]"""
    val archivedRefs      = Refs()
    val missingRefs       = Refs(archivePlantResult = ArchivePlantResult.PlantMissing)
    val repeatRefs        = Refs(archivePlantResult = ArchivePlantResult.AlreadyArchived)
    val failedRefs        = Refs(archivePlantResult = ArchivePlantResult.ArchiveFailed(RuntimeException("secret")))
    val refreshFailedRefs = Refs(refreshResult = RefreshAttentionResult.RefreshFailed(RuntimeException("attention unavailable")))

    val archived      = patch(archivePath, archivePatch, buildPlantApi(archivedRefs))
    val missing       = patch(archivePath, archivePatch, buildPlantApi(missingRefs))
    val repeat        = patch(archivePath, archivePatch, buildPlantApi(repeatRefs))
    val failed        = patch(archivePath, archivePatch, buildPlantApi(failedRefs))
    val refreshedLate = patch(archivePath, archivePatch, buildPlantApi(refreshFailedRefs))

    val expectedMissing = StatusCode.NotFound            -> json("""{"message":"plant not found"}""")
    val expectedRepeat  = StatusCode.Conflict            -> json("""{"message":"plant already archived"}""")
    val expectedFailed  = StatusCode.InternalServerError -> json("""{"message":"plant could not be archived"}""")
    assertEquals(archived.code, StatusCode.NoContent)
    assertEquals(refreshedLate.code, StatusCode.NoContent)
    assertEquals(missing.code -> jsonBody(missing), expectedMissing)
    assertEquals(repeat.code  -> jsonBody(repeat), expectedRepeat)
    assertEquals(failed.code  -> jsonBody(failed), expectedFailed)
    assertEquals(archivedRefs.refreshCalls.get(), 1)
    assertEquals(refreshFailedRefs.refreshCalls.get(), 1)
    assertEquals(missingRefs.refreshCalls.get(), 0)
    assertEquals(repeatRefs.refreshCalls.get(), 0)
    assertEquals(failedRefs.refreshCalls.get(), 0)

  test("should reject unsupported plant patches without archiving or refreshing"):
    val refs   = Refs()
    val server = buildPlantApi(refs)
    val path   = s"/plants/${plant.id.value}"

    val responses = List(
      """[{"op":"replace","path":"/details/status","value":"active"}]""",
      """[{"op":"replace","path":"/details/status","value":true}]""",
      """[{"op":"remove","path":"/details/status"}]""",
      """[{"op":"replace","path":"/details/location","value":"Kitchen"}]""",
      """[{"op":"replace","path":"/details/status","value":"archived"},{"op":"replace","path":"/details/location","value":"Kitchen"}]""",
      """{"status":"archived"}""",
      "[]",
      ""
    ).map(body => patch(path, body, server).code)

    assertEquals(responses, List.fill(8)(StatusCode.BadRequest))
    val unsupported = patch(path, """[{"op":"replace","path":"/details/status","value":"active"}]""", server)
    val malformed   = patch(path, """{"status":"archived"}""", server)
    assertEquals(unsupported.body.merge, "unsupported plant patch")
    assert(malformed.body.merge.contains("Invalid value for: body"))
    assertEquals(refs.refreshCalls.get(), 0)

  test("should require the JSON Patch media type for plant updates"):
    val refs     = Refs()
    val response = basicRequest
      .patch(Uri.unsafeParse(s"http://test/plants/${plant.id.value}"))
      .body("""[{"op":"replace","path":"/details/status","value":"archived"}]""")
      .contentType("application/json")
      .send(buildPlantApi(refs))

    val expected = StatusCode.UnsupportedMediaType -> json("""{"message":"unsupported patch media type"}""")
    assertEquals(response.code -> jsonBody(response), expected)
    assertEquals(refs.refreshCalls.get(), 0)

  private case class Refs(
      createPlantResult: CreatePlantResult = CreatePlantResult.Created(plant),
      plantsResult: GetPlantsResult = GetPlantsResult.Read(Vector.empty),
      archivedCountResult: ArchivedCountResult = ArchivedCountResult.Counted(0),
      archivePlantResult: ArchivePlantResult = ArchivePlantResult.Archived,
      refreshResult: RefreshAttentionResult = RefreshAttentionResult.Refreshed(AttentionProjection(date, Vector.empty)),
      requestedStatuses: AtomicReference[Vector[PlantStatus]] = AtomicReference(Vector.empty),
      refreshCalls: AtomicInteger = AtomicInteger(0)
  ):
    val createdDetails: AtomicReference[Vector[PlantDetails]] = AtomicReference(Vector.empty)

  private def buildPlantApi(refs: Refs) =
    val journal = new PlantJournal:
      override def createPlant(species: Species, maybeNickname: Option[Nickname], location: Location, substrate: Substrate): CreatePlantResult =
        refs.createdDetails.updateAndGet(_ :+ PlantDetails(species, maybeNickname, location, substrate, PlantStatus.Active))
        refs.createPlantResult
      override def getPlants(status: PlantStatus): GetPlantsResult =
        refs.requestedStatuses.updateAndGet(_ :+ status)
        refs.plantsResult
      override def getArchivedCount: ArchivedCountResult                                         = refs.archivedCountResult
      override def archivePlant(id: PlantId): ArchivePlantResult                                 = refs.archivePlantResult
      override def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult =
        fail("plant HTTP must not read operations")
      override def getOperationDateRange(plantId: PlantId): GetOperationDateRangeResult =
        fail("plant HTTP must not read operation dates")
      override def logOperation(plantId: PlantId, at: Instant, details: OperationDetails): LogOperationResult =
        fail("plant HTTP must not log operations")
      override def editOperation(id: OperationId, details: OperationDetails): EditOperationResult =
        fail("plant HTTP must not edit operations")
    val attention = new PlantAttentionMonitor:
      override def current: AttentionProjection       = fail("plant HTTP must not read attention")
      override def refreshAll: RefreshAttentionResult =
        val _ = refs.refreshCalls.incrementAndGet()
        refs.refreshResult
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointsRunLogic(PlantApi.serverEndpoints(using journal, attention))
      .backend()

  private type TestServer = SttpBackend[Identity, Any]

  private def get(path: String, server: TestServer) =
    basicRequest.get(Uri.unsafeParse(s"http://test$path")).send(server)

  private def post(body: String, server: TestServer) =
    basicRequest.post(Uri.unsafeParse("http://test/plants")).body(body).contentType("application/json").send(server)

  private def patch(path: String, body: String, server: TestServer) =
    basicRequest.patch(Uri.unsafeParse(s"http://test$path")).body(body).contentType("application/json-patch+json").send(server)

  private def jsonBody(response: Response[Either[String, String]]) = json(response.body.merge)
  private def json(value: String)                                  = parse(value).fold(error => fail(error.message), identity)
