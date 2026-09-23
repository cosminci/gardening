package gardening.adapters.http

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.domain.journal.*
import io.circe.parser.parse
import io.github.iltotore.iron.autoRefine
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{Response, SttpBackend, UriContext, basicRequest}
import sttp.model.{StatusCode, Uri}
import sttp.shared.Identity
import sttp.tapir.server.stub.TapirStubInterpreter

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.util.chaining.*

class JournalApiSeamIntegrationTest extends munit.FunSuite:

  private val date           = Instant.parse("2026-01-01T00:00:00Z")
  private val species        = Species("Ficus lyrata")
  private val nickname       = Nickname("Fern").some
  private val location       = Location("Balcony")
  private val perliteId      = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val substrate      = Substrate.of(List(SubstratePart(perliteId, 100))).getOrElse(fail("invalid substrate"))
  private val plant          = Plant(PlantId("p1"), PlantDetails(species, nickname, location, substrate, PlantStatus.Active))
  private val care           = OperationDetails.Care(Set(ActionType.Watered, ActionType.Pruned), Set.empty, MoistureLevel.Wet, Note("dry").some)
  private val repot          = OperationDetails.Repot(substrate, Note("fresh").some)
  private val careOperation  = Operation(OperationId("care"), plant.id, date, care)
  private val repotOperation = Operation(OperationId("repot"), plant.id, date.plusSeconds(1), repot)
  private val careRequest    = """{"kind":"care","actions":["watered","pruned"],"pesticides":[],"moisture":"wet","notes":"dry"}"""
  private val repotRequest   =
    """{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":100}],"notes":"fresh"}"""
  private val loggedCareRequest = s"""{"date":"$date","details":$careRequest}"""
  private val operationsJson    =
    """{"operations":[{"id":"care","plantId":"p1","date":"2026-01-01T00:00:00Z","details":{"kind":"care","actions":["pruned","watered"],"pesticides":[],"moisture":"wet","notes":"dry"}},{"id":"repot","plantId":"p1","date":"2026-01-01T00:00:01Z","details":{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":100}],"notes":"fresh"}}],"hasNextPage":true}"""
  private val repotJson =
    """{"id":"repot","plantId":"p1","date":"2026-01-01T00:00:01Z","details":{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":100}],"notes":"fresh"}}"""
  private val componentId       = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val pesticideId       = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
  private val componentData     = SubstrateComponentData(NomenclatureName("Pumice"), NomenclatureInfo("porous").some)
  private val pesticideData     = PesticideData(NomenclatureName("Sulfur"), PesticideType.Fungicide, NomenclatureInfo("2g/L").some)
  private val component         = SubstrateComponent(componentId, componentData)
  private val pesticide         = Pesticide(pesticideId, pesticideData)
  private val componentDataJson = """{"name":"Pumice","info":"porous"}"""
  private val pesticideDataJson = """{"name":"Sulfur","type":"fungicide","info":"2g/L"}"""
  private val componentJson     = s"""{"id":"${componentId.value}","data":$componentDataJson}"""
  private val pesticideJson     = s"""{"id":"${pesticideId.value}","data":$pesticideDataJson}"""
  private val catalogReadError  = """{"message":"nomenclatures could not be read"}"""
  private val catalogWriteError = """{"message":"nomenclature could not be saved"}"""

  test("should return the requested plant's care history"):
    val refs = Refs(
      getOperationsResult = GetOperationsResult.Read(
        OperationPage(Vector(careOperation, repotOperation), hasNextPage = true)
      )
    )
    val server = buildServer(refs)

    val operationsResponse = getOperations(server)
    assertEquals(operationsResponse.code -> jsonBody(operationsResponse), StatusCode.Ok -> json(operationsJson))
    assertEquals(refs.requestedWindows.get(), Vector(OperationWindow(offset = 3, size = 10)))

  test("should reject invalid operation windows"):
    val server = buildServer(Refs())

    assertEquals(getOperations(server, offset = -1, pageSize = 3).code, StatusCode.BadRequest)
    assertEquals(getOperations(server, offset = 0, pageSize = 0).code, StatusCode.BadRequest)
    assertEquals(getOperations(server, offset = 0, pageSize = 10).code, StatusCode.Ok)
    assertEquals(getOperations(server, offset = 0, pageSize = 11).code, StatusCode.BadRequest)

  test("should expose the attention projection"):
    val unknownPlant     = plant.copy(id = PlantId("unknown"))
    val currentPlant     = plant.copy(id = PlantId("current"))
    val zeroAveragePlant = plant.copy(id = PlantId("zero-average"))
    val projection       = AttentionProjection(
      measuredAt = date,
      plants = Vector(
        PlantAttention(
          plantId = unknownPlant.id,
          watering = WateringAttention.Unavailable(sampleCount = 4, maybeElapsed = 12.hours.some)
        ),
        PlantAttention(
          plantId = plant.id,
          watering = WateringAttention.RedAlert(sampleCount = 5, averageInterval = 24.hours, elapsed = 49.hours)
        ),
        PlantAttention(
          plantId = currentPlant.id,
          watering = WateringAttention.Current(sampleCount = 5, averageInterval = 24.hours, elapsed = 12.hours)
        ),
        PlantAttention(
          plantId = zeroAveragePlant.id,
          watering = WateringAttention.Overdue(sampleCount = 5, averageInterval = 0.millis, elapsed = 1.milli)
        )
      )
    )
    val response = get("/attention", buildServer(Refs(attentionProjection = projection)))
    val expected =
      s"""{
         |  "measuredAt": "$date",
         |  "plants": [
         |    {
         |      "plantId": "unknown",
         |      "watering": {"sampleCount":4,"elapsedMillis":"43200000","kind":"unavailable"}
         |    },
         |    {
         |      "plantId": "p1",
         |      "watering": {"sampleCount":5,"averageIntervalMillis":"86400000","elapsedMillis":"176400000","kind":"redAlert"}
         |    },
         |    {
         |      "plantId": "current",
         |      "watering": {"sampleCount":5,"averageIntervalMillis":"86400000","elapsedMillis":"43200000","kind":"current"}
         |    },
         |    {
         |      "plantId": "zero-average",
         |      "watering": {"sampleCount":5,"averageIntervalMillis":"0","elapsedMillis":"1","kind":"overdue"}
         |    }
         |  ]
         |}""".stripMargin

    assertResponse(response, StatusCode.Ok, expected)

  test("should use the recent-operation window by default"):
    val refs     = Refs()
    val response = get(s"/plants/${plant.id.value}/operations", buildServer(refs))

    assertEquals(response.code, StatusCode.Ok)
    assertEquals(refs.requestedWindows.get(), Vector(OperationWindow(offset = 0, size = 3)))

  test("should list active plants by default and archived plants on request"):
    val active         = plant
    val archived       = plant.copy(id = PlantId("archived"), details = plant.details.copy(status = PlantStatus.Archived))
    val activeRefs     = Refs(plantsResult = GetPlantsResult.Read(Vector(active)))
    val archivedRefs   = Refs(plantsResult = GetPlantsResult.Read(Vector(archived)))
    val activeServer   = buildServer(activeRefs)
    val archivedServer = buildServer(archivedRefs)

    val activeResponse   = get("/plants", activeServer)
    val archivedResponse = get("/plants?status=archived", archivedServer)
    val invalidResponse  = get("/plants?status=unknown", activeServer)
    val expectedActive   =
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
    val refs   = Refs(plantsResult = GetPlantsResult.ReadFailed(RuntimeException("offline")))
    val server = buildServer(refs)

    val response = get("/plants", server)
    assertEquals(response.code, StatusCode.InternalServerError)

  test("should log care and replace the details of an existing repot"):
    val refs = Refs(
      logOperationResult = LogOperationResult.Logged(OperationId("logged")),
      editOperationResult = EditOperationResult.Edited(repotOperation)
    )
    val server = buildServer(refs)

    val logResponse   = post(s"/plants/${plant.id.value}/operations", loggedCareRequest, server)
    val editResponse  = put(s"/operations/${repotOperation.id.value}", repotRequest, server)
    assertEquals(logResponse.code  -> jsonBody(logResponse), StatusCode.Created -> json("""{"id":"logged"}"""))
    assertEquals(editResponse.code -> jsonBody(editResponse), StatusCode.Ok     -> json(repotJson))
    assertEquals(refs.loggedOperations.get(), Vector((plant.id, date, care)))
    assertEquals(refs.editedOperations.get(), Vector(repotOperation.id -> repot))

  test("should reject invalid or missing operation input without logging an operation"):
    val invalidDetails = List(
      """{"kind":"care","actions":["misted"],"pesticides":[],"moisture":"wet","notes":null}""",
      """{"kind":"care","actions":[],"pesticides":["not-a-uuid"],"moisture":"wet","notes":null}""",
      """{"kind":"repot","substrate":[],"notes":null}""",
      """{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":0}],"notes":null}""",
      """{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":50},{"componentId":"00000000-0000-4000-8000-000000000003","share":50}],"notes":null}""",
      """{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":60},{"componentId":"00000000-0000-4000-8000-000000000004","share":60}],"notes":null}""",
      """{"kind":"fertilize","actions":[],"pesticides":[],"moisture":"wet","notes":null}"""
    )
    val invalidDates = List(
      s"""{"details":$careRequest}""",
      s"""{"date":"tomorrow","details":$careRequest}""",
      s"""{"date":"2026-01-01T00:00","details":$careRequest}"""
    )
    val invalidBodies = invalidDates ++ invalidDetails.map(details => s"""{"date":"$date","details":$details}""")
    val refs          = Refs()
    val server        = buildServer(refs)

    val responses = invalidBodies.map(body => post(s"/plants/${plant.id.value}/operations", body, server).code)

    assertEquals(responses, List.fill(invalidBodies.size)(StatusCode.BadRequest))
    assertEquals(refs.loggedOperations.get(), Vector.empty)

  test("should hide storage failures returned by read operations"):
    val refs   = Refs(getOperationsResult = GetOperationsResult.ReadFailed(RuntimeException("offline")))
    val server = buildServer(refs)

    val operationsResponse = getOperations(server)
    val expected           = StatusCode.InternalServerError -> json("""{"message":"journal could not be read"}""")
    assertEquals(operationsResponse.code -> jsonBody(operationsResponse), expected)

  test("should hide the cause when logging an operation fails"):
    val refs     = Refs(logOperationResult = LogOperationResult.LoggingFailed(RuntimeException("offline")))
    val server   = buildServer(refs)
    val response = post(s"/plants/${plant.id.value}/operations", loggedCareRequest, server)
    val expected = StatusCode.InternalServerError -> json("""{"message":"operation could not be logged"}""")
    assertEquals(response.code -> jsonBody(response), expected)

  test("should report when the operation to edit does not exist"):
    val response =
      put(s"/operations/${repotOperation.id.value}", careRequest, buildServer(Refs(editOperationResult = EditOperationResult.OperationMissing)))
    assertEquals(response.code -> jsonBody(response), StatusCode.NotFound -> json("""{"message":"operation not found"}"""))

  test("should reject changing an operation to another type"):
    val response =
      put(s"/operations/${repotOperation.id.value}", careRequest, buildServer(Refs(editOperationResult = EditOperationResult.OperationTypeMismatch)))
    assertEquals(response.code -> jsonBody(response), StatusCode.Conflict -> json("""{"message":"operation type cannot be changed"}"""))

  test("should hide storage failures returned when editing"):
    val response = put(
      s"/operations/${repotOperation.id.value}",
      careRequest,
      buildServer(Refs(editOperationResult = EditOperationResult.EditFailed(RuntimeException("offline"))))
    )
    val expected = StatusCode.InternalServerError -> json("""{"message":"operation could not be edited"}""")
    assertEquals(response.code -> jsonBody(response), expected)

  test("should list and add catalog records"):
    val refs = Refs(
      componentReadResult = CatalogReadResult.Read(Vector(component)),
      componentAddResult = CatalogAddResult.Added(component),
      pesticideReadResult = CatalogReadResult.Read(Vector(pesticide)),
      pesticideAddResult = CatalogAddResult.Added(pesticide)
    )
    val server = buildServer(refs)

    assertResponse(get("/substrate-components", server), StatusCode.Ok, s"[$componentJson]")
    assertResponse(post("/substrate-components", componentDataJson, server), StatusCode.Created, componentJson)
    assertResponse(get("/pesticides", server), StatusCode.Ok, s"[$pesticideJson]")
    assertResponse(post("/pesticides", pesticideDataJson, server), StatusCode.Created, pesticideJson)

  test("should edit catalog records and reject invalid or missing identifiers"):
    val refs = Refs(
      componentEditResult = CatalogEditResult.Edited(component),
      pesticideEditResult = CatalogEditResult.Edited(pesticide)
    )
    val server = buildServer(refs)

    assertResponse(put(s"/substrate-components/${componentId.value}", componentDataJson, server), StatusCode.Ok, componentJson)
    assertResponse(put(s"/pesticides/${pesticideId.value}", pesticideDataJson, server), StatusCode.Ok, pesticideJson)
    assertResponse(
      put("/substrate-components/not-a-uuid", componentDataJson, server),
      StatusCode.BadRequest,
      """{"message":"invalid nomenclature id"}"""
    )
    assertResponse(
      put(s"/substrate-components/${componentId.value}", componentDataJson, buildServer(Refs())),
      StatusCode.NotFound,
      """{"message":"nomenclature not found"}"""
    )
    assertResponse(
      put("/pesticides/not-a-uuid", pesticideDataJson, server),
      StatusCode.BadRequest,
      """{"message":"invalid nomenclature id"}"""
    )
    assertResponse(
      put(s"/pesticides/${pesticideId.value}", pesticideDataJson, buildServer(Refs())),
      StatusCode.NotFound,
      """{"message":"nomenclature not found"}"""
    )

  test("should hide catalog storage failures"):
    val failure = RuntimeException("private details")
    val refs    = Refs(
      componentReadResult = CatalogReadResult.ReadFailed(failure),
      componentAddResult = CatalogAddResult.AddFailed(failure),
      componentEditResult = CatalogEditResult.EditFailed(failure),
      pesticideReadResult = CatalogReadResult.ReadFailed(failure),
      pesticideAddResult = CatalogAddResult.AddFailed(failure),
      pesticideEditResult = CatalogEditResult.EditFailed(failure)
    )
    val server = buildServer(refs)

    assertResponse(get("/substrate-components", server), StatusCode.InternalServerError, catalogReadError)
    assertResponse(post("/substrate-components", componentDataJson, server), StatusCode.InternalServerError, catalogWriteError)
    assertResponse(put(s"/substrate-components/${componentId.value}", componentDataJson, server), StatusCode.InternalServerError, catalogWriteError)
    assertResponse(get("/pesticides", server), StatusCode.InternalServerError, catalogReadError)
    assertResponse(post("/pesticides", pesticideDataJson, server), StatusCode.InternalServerError, catalogWriteError)
    assertResponse(put(s"/pesticides/${pesticideId.value}", pesticideDataJson, server), StatusCode.InternalServerError, catalogWriteError)

  private case class Refs(
      attentionProjection: AttentionProjection = AttentionProjection(date, Vector.empty),
      plantsResult: GetPlantsResult = GetPlantsResult.Read(Vector.empty),
      getOperationsResult: GetOperationsResult = GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false)),
      logOperationResult: LogOperationResult = LogOperationResult.Logged(OperationId("logged")),
      editOperationResult: EditOperationResult = EditOperationResult.OperationMissing,
      componentReadResult: CatalogReadResult[SubstrateComponent] = CatalogReadResult.Read(Vector.empty),
      componentAddResult: CatalogAddResult[SubstrateComponent] = CatalogAddResult.Added(component),
      componentEditResult: CatalogEditResult[SubstrateComponent] = CatalogEditResult.RecordMissing,
      pesticideReadResult: CatalogReadResult[Pesticide] = CatalogReadResult.Read(Vector.empty),
      pesticideAddResult: CatalogAddResult[Pesticide] = CatalogAddResult.Added(pesticide),
      pesticideEditResult: CatalogEditResult[Pesticide] = CatalogEditResult.RecordMissing,
      requestedWindows: AtomicReference[Vector[OperationWindow]] = AtomicReference(Vector.empty),
      requestedStatuses: AtomicReference[Vector[PlantStatus]] = AtomicReference(Vector.empty),
      loggedOperations: AtomicReference[Vector[(PlantId, Instant, OperationDetails)]] = AtomicReference(Vector.empty),
      editedOperations: AtomicReference[Vector[(OperationId, OperationDetails)]] = AtomicReference(Vector.empty)
  )

  private def buildServer(refs: Refs) =
    val journal = new PlantJournal:
      override def getPlants(status: PlantStatus): GetPlantsResult =
        refs.requestedStatuses.updateAndGet(_ :+ status)
        refs.plantsResult
      override def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult =
        refs.requestedWindows.updateAndGet(_ :+ window)
        refs.getOperationsResult
      override def logOperation(plantId: PlantId, at: Instant, details: OperationDetails): LogOperationResult =
        refs.loggedOperations.updateAndGet(_ :+ ((plantId, at, details))).pipe(_ => refs.logOperationResult)
      override def editOperation(id: OperationId, details: OperationDetails): EditOperationResult =
        refs.editedOperations.updateAndGet(_ :+ (id -> details)).pipe(_ => refs.editOperationResult)
      override def getSubstrateComponents: CatalogReadResult[SubstrateComponent]                             = refs.componentReadResult
      override def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent] = refs.componentAddResult
      override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
        refs.componentEditResult
      override def getPesticides: CatalogReadResult[Pesticide]                                       = refs.pesticideReadResult
      override def addPesticide(data: PesticideData): CatalogAddResult[Pesticide]                    = refs.pesticideAddResult
      override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] = refs.pesticideEditResult
    val attention = new PlantAttentionMonitor:
      override def current: AttentionProjection       = refs.attentionProjection
      override def refreshAll: RefreshAttentionResult = fail("HTTP must not refresh attention")
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointsRunLogic(JournalApi.serverEndpoints(using journal, attention))
      .backend()

  private type TestServer = SttpBackend[Identity, Any]

  private def getOperations(server: TestServer, offset: Int = 3, pageSize: Int = 10) =
    basicRequest
      .get(uri"http://test/plants/${plant.id.value}/operations?offset=$offset&pageSize=$pageSize")
      .send(server)

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
