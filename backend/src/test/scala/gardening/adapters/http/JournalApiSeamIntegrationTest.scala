package gardening.adapters.http

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.domain.journal.*
import io.circe.parser.parse
import io.github.iltotore.iron.autoRefine
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{Response, UriContext, basicRequest}
import sttp.model.{StatusCode, Uri}
import sttp.tapir.server.stub.TapirStubInterpreter

import java.time.Instant
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
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
  private val plantsJson =
    """[{"id":"p1","details":{"species":"Ficus lyrata","nickname":"Fern","location":"Balcony","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":100}],"status":"active"}}]"""
  private val operationsJson =
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

  test("should return active plants and the requested plant's care history"):
    val requestedPlants  = AtomicReference(Vector.empty[PlantId])
    val requestedWindows = AtomicReference(Vector.empty[OperationWindow])
    val journal          = buildJournal(
      getPlantsResult = GetPlantsResult.Read(Vector(plant)),
      getOperationsResult = GetOperationsResult.Read(
        OperationPage(Vector(careOperation, repotOperation), hasNextPage = true)
      ),
      requestedPlants = requestedPlants,
      requestedWindows = requestedWindows
    )

    val plantsResponse             = getPlants(journal)
    val operationsResponse         = getOperations(journal)
    assertEquals(plantsResponse.code     -> jsonBody(plantsResponse), StatusCode.Ok     -> json(plantsJson))
    assertEquals(operationsResponse.code -> jsonBody(operationsResponse), StatusCode.Ok -> json(operationsJson))
    assertEquals(requestedPlants.get(), Vector(plant.id))
    assertEquals(requestedWindows.get(), Vector(OperationWindow(offset = 3, size = 10)))

  test("should reject invalid operation windows"):
    assertEquals(getOperations(buildJournal(), offset = -1, pageSize = 3).code, StatusCode.BadRequest)
    assertEquals(getOperations(buildJournal(), offset = 0, pageSize = 0).code, StatusCode.BadRequest)
    assertEquals(getOperations(buildJournal(), offset = 0, pageSize = 10).code, StatusCode.Ok)
    assertEquals(getOperations(buildJournal(), offset = 0, pageSize = 11).code, StatusCode.BadRequest)

  test("should expose the attention projection"):
    val unknownPlant     = plant.copy(id = PlantId("unknown"))
    val zeroAveragePlant = plant.copy(id = PlantId("zero-average"))
    val attention        = new PlantAttentionMonitor:
      override def current: AttentionProjection =
        AttentionProjection(
          date,
          Vector(
            PlantAttention(unknownPlant, WateringCadence.Unavailable(sampleCount = 4, Some(Duration.ofHours(12)))),
            PlantAttention(
              plant,
              WateringCadence.Inferred(
                sampleCount = 5,
                averageInterval = Duration.ofHours(24),
                elapsed = Duration.ofHours(49),
                urgency = Urgency.Finite(Duration.ofHours(49), Duration.ofHours(24)),
                state = WateringState.RedAlert
              )
            ),
            PlantAttention(
              zeroAveragePlant,
              WateringCadence.Inferred(
                sampleCount = 5,
                averageInterval = Duration.ZERO,
                elapsed = Duration.ofNanos(1),
                urgency = Urgency.Unbounded,
                state = WateringState.Overdue
              )
            )
          )
        )
      override def refreshAll: RefreshAttentionResult = fail("HTTP must not refresh attention")

    val response = get("/attention", buildJournal(), attention)
    val body     = jsonBody(response)
    val plants   = body.hcursor.downField("plants")

    assertEquals(response.code, StatusCode.Ok)
    assertEquals(body.hcursor.get[String]("measuredAt"), Right(date.toString))
    assertEquals(plants.downN(0).downField("plant").get[String]("id"), Right("unknown"))
    assertEquals(plants.downN(0).get[Int]("sampleCount"), Right(4))
    assertEquals(plants.downN(0).get[Boolean]("cadenceAvailable"), Right(false))
    assertEquals(plants.downN(0).get[String]("elapsed"), Right("PT12H"))
    assertEquals(plants.downN(1).downField("plant").get[String]("id"), Right("p1"))
    assertEquals(plants.downN(1).get[Boolean]("cadenceAvailable"), Right(true))
    assertEquals(plants.downN(1).get[String]("averageInterval"), Right("PT24H"))
    assertEquals(plants.downN(1).get[String]("elapsed"), Right("PT49H"))
    assertEquals(plants.downN(1).downField("urgency").get[Boolean]("unbounded"), Right(false))
    assertEquals(plants.downN(1).downField("urgency").get[String]("numeratorNanos"), Right("176400000000000"))
    assertEquals(plants.downN(1).downField("urgency").get[String]("denominatorNanos"), Right("86400000000000"))
    assertEquals(plants.downN(1).get[String]("state"), Right("redAlert"))
    assertEquals(plants.downN(2).downField("plant").get[String]("id"), Right("zero-average"))
    assertEquals(plants.downN(2).downField("urgency").get[Boolean]("unbounded"), Right(true))
    assertEquals(plants.downN(2).downField("urgency").get[Option[String]]("numeratorNanos"), Right(None))

  test("should use the recent-operation window by default"):
    val requestedWindows = AtomicReference(Vector.empty[OperationWindow])
    val response         = get(s"/plants/${plant.id.value}/operations", buildJournal(requestedWindows = requestedWindows))

    assertEquals(response.code, StatusCode.Ok)
    assertEquals(requestedWindows.get(), Vector(OperationWindow(offset = 0, size = 3)))

  test("should log care and replace the details of an existing repot"):
    val logged  = AtomicReference(Vector.empty[(PlantId, OperationDetails)])
    val edited  = AtomicReference(Vector.empty[(OperationId, OperationDetails)])
    val journal = buildJournal(
      logOperationResult = LogOperationResult.Logged(OperationId("logged")),
      editOperationResult = EditOperationResult.Edited(repotOperation),
      loggedOperations = logged,
      editedOperations = edited
    )

    val logResponse   = logOperation(careRequest, journal)
    val editResponse  = editOperation(repotRequest, journal)
    assertEquals(logResponse.code  -> jsonBody(logResponse), StatusCode.Created -> json("""{"id":"logged"}"""))
    assertEquals(editResponse.code -> jsonBody(editResponse), StatusCode.Ok     -> json(repotJson))
    assertEquals(logged.get(), Vector(plant.id -> care))
    assertEquals(edited.get(), Vector(repotOperation.id -> repot))

  test("should reject invalid operation details without logging an operation"):
    val logged        = AtomicReference(Vector.empty[(PlantId, OperationDetails)])
    val journal       = buildJournal(loggedOperations = logged)
    val invalidBodies = List(
      """{"kind":"care","actions":["misted"],"pesticides":[],"moisture":"wet","notes":null}""",
      """{"kind":"care","actions":[],"pesticides":["not-a-uuid"],"moisture":"wet","notes":null}""",
      """{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":0}],"notes":null}""",
      """{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":50},{"componentId":"00000000-0000-4000-8000-000000000003","share":50}],"notes":null}""",
      """{"kind":"fertilize","actions":[],"pesticides":[],"moisture":"wet","notes":null}"""
    )
    invalidBodies.foreach(body => assertEquals(logOperation(body, journal).code, StatusCode.BadRequest))
    assertEquals(logged.get(), Vector.empty)

  test("should hide storage failures returned by read operations"):
    val journal = buildJournal(
      getPlantsResult = GetPlantsResult.ReadFailed(RuntimeException("offline")),
      getOperationsResult = GetOperationsResult.ReadFailed(RuntimeException("offline"))
    )

    val plantsResponse         = getPlants(journal)
    val operationsResponse     = getOperations(journal)
    val expected               = StatusCode.InternalServerError -> json("""{"message":"journal could not be read"}""")
    assertEquals(plantsResponse.code     -> jsonBody(plantsResponse), expected)
    assertEquals(operationsResponse.code -> jsonBody(operationsResponse), expected)

  test("should hide the cause when logging an operation fails"):
    val journal  = buildJournal(logOperationResult = LogOperationResult.LoggingFailed(RuntimeException("offline")))
    val response = logOperation(careRequest, journal)
    val expected = StatusCode.InternalServerError -> json("""{"message":"operation could not be logged"}""")
    assertEquals(response.code -> jsonBody(response), expected)

  test("should report when the operation to edit does not exist"):
    val response = editOperation(careRequest, buildJournal(editOperationResult = EditOperationResult.OperationMissing))
    assertEquals(response.code -> jsonBody(response), StatusCode.NotFound -> json("""{"message":"operation not found"}"""))

  test("should reject changing an operation to another type"):
    val response = editOperation(careRequest, buildJournal(editOperationResult = EditOperationResult.OperationTypeMismatch))
    assertEquals(response.code -> jsonBody(response), StatusCode.Conflict -> json("""{"message":"operation type cannot be changed"}"""))

  test("should hide storage failures returned when editing"):
    val response = editOperation(careRequest, buildJournal(editOperationResult = EditOperationResult.EditFailed(RuntimeException("offline"))))
    val expected = StatusCode.InternalServerError -> json("""{"message":"operation could not be edited"}""")
    assertEquals(response.code -> jsonBody(response), expected)

  test("should list and add catalog records"):
    val journal = buildJournal(
      componentReadResult = CatalogReadResult.Read(Vector(component)),
      componentAddResult = CatalogAddResult.Added(component),
      pesticideReadResult = CatalogReadResult.Read(Vector(pesticide)),
      pesticideAddResult = CatalogAddResult.Added(pesticide)
    )

    assertResponse(get("/substrate-components", journal), StatusCode.Ok, s"[$componentJson]")
    assertResponse(post("/substrate-components", componentDataJson, journal), StatusCode.Created, componentJson)
    assertResponse(get("/pesticides", journal), StatusCode.Ok, s"[$pesticideJson]")
    assertResponse(post("/pesticides", pesticideDataJson, journal), StatusCode.Created, pesticideJson)

  test("should edit catalog records and reject invalid or missing identifiers"):
    val journal = buildJournal(
      componentEditResult = CatalogEditResult.Edited(component),
      pesticideEditResult = CatalogEditResult.Edited(pesticide)
    )

    assertResponse(put(s"/substrate-components/${componentId.value}", componentDataJson, journal), StatusCode.Ok, componentJson)
    assertResponse(put(s"/pesticides/${pesticideId.value}", pesticideDataJson, journal), StatusCode.Ok, pesticideJson)
    assertResponse(
      put("/substrate-components/not-a-uuid", componentDataJson, journal),
      StatusCode.BadRequest,
      """{"message":"invalid nomenclature id"}"""
    )
    assertResponse(
      put(s"/substrate-components/${componentId.value}", componentDataJson, buildJournal()),
      StatusCode.NotFound,
      """{"message":"nomenclature not found"}"""
    )
    assertResponse(
      put("/pesticides/not-a-uuid", pesticideDataJson, journal),
      StatusCode.BadRequest,
      """{"message":"invalid nomenclature id"}"""
    )
    assertResponse(
      put(s"/pesticides/${pesticideId.value}", pesticideDataJson, buildJournal()),
      StatusCode.NotFound,
      """{"message":"nomenclature not found"}"""
    )

  test("should hide catalog storage failures"):
    val failure = RuntimeException("private details")
    val journal = buildJournal(
      componentReadResult = CatalogReadResult.ReadFailed(failure),
      componentAddResult = CatalogAddResult.AddFailed(failure),
      componentEditResult = CatalogEditResult.EditFailed(failure),
      pesticideReadResult = CatalogReadResult.ReadFailed(failure),
      pesticideAddResult = CatalogAddResult.AddFailed(failure),
      pesticideEditResult = CatalogEditResult.EditFailed(failure)
    )

    assertResponse(get("/substrate-components", journal), StatusCode.InternalServerError, catalogReadError)
    assertResponse(post("/substrate-components", componentDataJson, journal), StatusCode.InternalServerError, catalogWriteError)
    assertResponse(put(s"/substrate-components/${componentId.value}", componentDataJson, journal), StatusCode.InternalServerError, catalogWriteError)
    assertResponse(get("/pesticides", journal), StatusCode.InternalServerError, catalogReadError)
    assertResponse(post("/pesticides", pesticideDataJson, journal), StatusCode.InternalServerError, catalogWriteError)
    assertResponse(put(s"/pesticides/${pesticideId.value}", pesticideDataJson, journal), StatusCode.InternalServerError, catalogWriteError)

  private def getPlants(journal: PlantJournal) =
    basicRequest.get(uri"http://test/plants").send(backend(journal))

  private def getOperations(journal: PlantJournal, offset: Int = 3, pageSize: Int = 10) =
    basicRequest
      .get(uri"http://test/plants/${plant.id.value}/operations?offset=$offset&pageSize=$pageSize")
      .send(backend(journal))

  private def logOperation(body: String, journal: PlantJournal) =
    basicRequest.post(uri"http://test/plants/${plant.id.value}/operations").body(body).contentType("application/json").send(backend(journal))

  private def editOperation(body: String, journal: PlantJournal) =
    basicRequest.put(uri"http://test/operations/${repotOperation.id.value}").body(body).contentType("application/json").send(backend(journal))

  private def get(path: String, journal: PlantJournal, attention: PlantAttentionMonitor = emptyAttention) =
    basicRequest.get(Uri.unsafeParse(s"http://test$path")).send(backend(journal, attention))

  private def post(path: String, body: String, journal: PlantJournal) =
    basicRequest.post(Uri.unsafeParse(s"http://test$path")).body(body).contentType("application/json").send(backend(journal))

  private def put(path: String, body: String, journal: PlantJournal) =
    basicRequest.put(Uri.unsafeParse(s"http://test$path")).body(body).contentType("application/json").send(backend(journal))

  private def backend(journal: PlantJournal, attention: PlantAttentionMonitor = emptyAttention) =
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointsRunLogic(JournalApi.serverEndpoints(using journal, attention))
      .backend()

  private val emptyAttention = new PlantAttentionMonitor:
    override def current: AttentionProjection       = AttentionProjection(date, Vector.empty)
    override def refreshAll: RefreshAttentionResult = fail("HTTP must not refresh attention")

  private def jsonBody(response: Response[Either[String, String]]) = json(response.body.merge)
  private def json(value: String)                                  = parse(value).fold(error => fail(error.message), identity)
  private def assertResponse(response: Response[Either[String, String]], status: StatusCode, body: String) =
    assertEquals(response.code -> jsonBody(response), status -> json(body))

  private def buildJournal(
      getPlantsResult: GetPlantsResult = GetPlantsResult.Read(Vector.empty),
      getOperationsResult: GetOperationsResult = GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false)),
      logOperationResult: LogOperationResult = LogOperationResult.Logged(OperationId("logged")),
      editOperationResult: EditOperationResult = EditOperationResult.OperationMissing,
      requestedPlants: AtomicReference[Vector[PlantId]] = AtomicReference(Vector.empty),
      requestedWindows: AtomicReference[Vector[OperationWindow]] = AtomicReference(Vector.empty),
      loggedOperations: AtomicReference[Vector[(PlantId, OperationDetails)]] = AtomicReference(Vector.empty),
      editedOperations: AtomicReference[Vector[(OperationId, OperationDetails)]] = AtomicReference(Vector.empty),
      componentReadResult: CatalogReadResult[SubstrateComponent] = CatalogReadResult.Read(Vector.empty),
      componentAddResult: CatalogAddResult[SubstrateComponent] = CatalogAddResult.Added(component),
      componentEditResult: CatalogEditResult[SubstrateComponent] = CatalogEditResult.RecordMissing,
      pesticideReadResult: CatalogReadResult[Pesticide] = CatalogReadResult.Read(Vector.empty),
      pesticideAddResult: CatalogAddResult[Pesticide] = CatalogAddResult.Added(pesticide),
      pesticideEditResult: CatalogEditResult[Pesticide] = CatalogEditResult.RecordMissing
  ) = new PlantJournal:
    override def getPlants: GetPlantsResult                                                    = getPlantsResult
    override def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult =
      requestedPlants.updateAndGet(_ :+ plantId)
      requestedWindows.updateAndGet(_ :+ window)
      getOperationsResult
    override def logOperation(plantId: PlantId, details: OperationDetails): LogOperationResult =
      loggedOperations.updateAndGet(_ :+ (plantId -> details)).pipe(_ => logOperationResult)
    override def editOperation(id: OperationId, details: OperationDetails): EditOperationResult =
      editedOperations.updateAndGet(_ :+ (id -> details)).pipe(_ => editOperationResult)
    override def getSubstrateComponents: CatalogReadResult[SubstrateComponent]                             = componentReadResult
    override def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent] = componentAddResult
    override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
      componentEditResult
    override def getPesticides: CatalogReadResult[Pesticide]                                       = pesticideReadResult
    override def addPesticide(data: PesticideData): CatalogAddResult[Pesticide]                    = pesticideAddResult
    override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] = pesticideEditResult
