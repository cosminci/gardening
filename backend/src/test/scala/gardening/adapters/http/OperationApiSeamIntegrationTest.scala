package gardening.adapters.http

import cats.syntax.option.*
import gardening.domain.*
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
import scala.util.chaining.*

class OperationApiSeamIntegrationTest extends munit.FunSuite:

  private val date           = Instant.parse("2026-01-01T00:00:00Z")
  private val perliteId      = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  private val pesticideId    = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
  private val substrate      = Substrate.of(List(SubstratePart(perliteId, 100))).getOrElse(fail("invalid substrate"))
  private val plantId        = PlantId("p1")
  private val care           = OperationDetails.Care(Set(ActionType.Watered, ActionType.Pruned), Set.empty, MoistureLevel.Wet, Note("dry").some)
  private val repot          = OperationDetails.Repot(substrate, Note("fresh").some)
  private val careOperation  = Operation(OperationId("care"), plantId, date, care.copy(pesticides = Set(pesticideId)))
  private val repotOperation = Operation(OperationId("repot"), plantId, date.plusSeconds(1), repot)
  private val careRequest    = """{"kind":"care","actions":["watered","pruned"],"pesticides":[],"moisture":"wet","notes":"dry"}"""
  private val repotRequest   =
    """{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":100}],"notes":"fresh"}"""
  private val loggedCareRequest = s"""{"plantId":"${plantId.value}","date":"$date","details":$careRequest}"""
  private val operationsJson    =
    s"""{"operations":[{"id":"care","plantId":"p1","date":"2026-01-01T00:00:00Z","details":{"kind":"care","actions":["pruned","watered"],"pesticides":["${pesticideId.value}"],"moisture":"wet","notes":"dry"}},{"id":"repot","plantId":"p1","date":"2026-01-01T00:00:01Z","details":{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":100}],"notes":"fresh"}}],"hasNextPage":true}"""
  private val repotJson =
    """{"id":"repot","plantId":"p1","date":"2026-01-01T00:00:01Z","details":{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":100}],"notes":"fresh"}}"""
  test("should return the requested plant's care history"):
    val refs   = Refs()
    val server = buildOperationApi(
      refs,
      getOperationsResult = GetOperationsResult.Read(OperationPage(Vector(careOperation, repotOperation), hasNextPage = true))
    )

    val operationsResponse = getOperations(server)

    assertEquals(operationsResponse.code -> jsonBody(operationsResponse), StatusCode.Ok -> json(operationsJson))
    assertEquals(refs.requestedWindows.get(), Vector(OperationWindow(offset = 3, size = 10)))

  test("should reject invalid operation windows"):
    val server = buildOperationApi()

    assertEquals(get("/operations?offset=0&pageSize=3", server).code, StatusCode.BadRequest)
    assertEquals(get("/operations?plantId=", server).code, StatusCode.BadRequest)
    assertEquals(getOperations(server, offset = -1, pageSize = 3).code, StatusCode.BadRequest)
    assertEquals(getOperations(server, offset = 0, pageSize = 0).code, StatusCode.BadRequest)
    assertEquals(getOperations(server, offset = 0, pageSize = 10).code, StatusCode.Ok)
    assertEquals(getOperations(server, offset = 0, pageSize = 11).code, StatusCode.BadRequest)

  test("should use the recent-operation window by default"):
    val refs     = Refs()
    val response = get(s"/operations?plantId=${plantId.value}", buildOperationApi(refs))

    assertEquals(response.code, StatusCode.Ok)
    assertEquals(refs.requestedWindows.get(), Vector(OperationWindow(offset = 0, size = 3)))

  test("should expose the entire recorded date range or an explicit empty history"):
    val lastDate      = date.plusSeconds(60)
    val rangePath     = s"/operations/date-range?plantId=${plantId.value}"
    val recordedRange = OperationDateRange.Recorded(date, lastDate)

    val recordedRefs     = Refs()
    val recordedResponse = get(rangePath, buildOperationApi(recordedRefs, operationDateRangeResult = GetOperationDateRangeResult.Read(recordedRange)))
    val emptyResponse    = get(rangePath, buildOperationApi())

    val recordedJson     = s"""{"kind":"recorded","first":"$date","last":"$lastDate"}"""
    val expectedRecorded = StatusCode.Ok         -> json(recordedJson)
    val actualRecorded   = recordedResponse.code -> jsonBody(recordedResponse)
    val expectedEmpty    = StatusCode.Ok         -> json("""{"kind":"empty"}""")
    val actualEmpty      = emptyResponse.code    -> jsonBody(emptyResponse)
    assertEquals(actualRecorded, expectedRecorded)
    assertEquals(actualEmpty, expectedEmpty)
    assertEquals(recordedRefs.requestedDateRanges.get(), Vector(plantId))
    assertEquals(recordedRefs.requestedWindows.get(), Vector.empty)

  test("should distinguish an unknown plant from a failed date-range read"):
    val rangePath       = s"/operations/date-range?plantId=${plantId.value}"
    val missingResponse = get(rangePath, buildOperationApi(operationDateRangeResult = GetOperationDateRangeResult.PlantMissing))
    val failedResponse  =
      get(rangePath, buildOperationApi(operationDateRangeResult = GetOperationDateRangeResult.ReadFailed(RuntimeException("secret"))))

    val expectedMissing = StatusCode.NotFound            -> json("""{"message":"plant not found"}""")
    val expectedFailed  = StatusCode.InternalServerError -> json("""{"message":"operation dates could not be read"}""")
    val actualMissing   = missingResponse.code           -> jsonBody(missingResponse)
    val actualFailed    = failedResponse.code            -> jsonBody(failedResponse)
    assertEquals(actualMissing, expectedMissing)
    assertEquals(actualFailed, expectedFailed)

  test("should reject new operations on archived plants with a conflict"):
    val response = post("/operations", loggedCareRequest, buildOperationApi(logOperationResult = LogOperationResult.PlantArchived))

    val expected = StatusCode.Conflict -> json("""{"message":"plant already archived"}""")
    assertEquals(response.code -> jsonBody(response), expected)

  test("should report when logging is attempted for an unknown plant"):
    val response = post("/operations", loggedCareRequest, buildOperationApi(logOperationResult = LogOperationResult.PlantMissing))

    val expected = StatusCode.NotFound -> json("""{"message":"plant not found"}""")
    assertEquals(response.code -> jsonBody(response), expected)

  test("should log care and replace the details of an existing repot"):
    val refs   = Refs()
    val server = buildOperationApi(refs, editOperationResult = EditOperationResult.Edited(repotOperation))

    val logResponse  = post("/operations", loggedCareRequest, server)
    val editResponse = put(s"/operations/${repotOperation.id.value}", repotRequest, server)

    assertEquals(logResponse.code  -> jsonBody(logResponse), StatusCode.Created -> json("""{"id":"logged"}"""))
    assertEquals(editResponse.code -> jsonBody(editResponse), StatusCode.Ok     -> json(repotJson))
    assertEquals(refs.loggedOperations.get(), Vector((plantId, date, care)))
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
    val malformedDate = s"""{"plantId":"${plantId.value}","date":"tomorrow","details":$careRequest}"""
    val invalidDates  = List(
      s"""{"plantId":"${plantId.value}","details":$careRequest}""",
      malformedDate,
      s"""{"plantId":"${plantId.value}","date":"2026-01-01T00:00","details":$careRequest}"""
    )
    val invalidBodies =
      invalidDates ++ invalidDetails.map(details => s"""{"plantId":"${plantId.value}","date":"$date","details":$details}""") :+
        s"""{"date":"$date","details":$careRequest}""" :+
        s"""{"plantId":"","date":"$date","details":$careRequest}"""
    val refs   = Refs()
    val server = buildOperationApi(refs)

    val responses = invalidBodies.map(body => post("/operations", body, server).code)

    assertEquals(responses, List.fill(invalidBodies.size)(StatusCode.BadRequest))
    val malformed = post("/operations", malformedDate, server)
    assert(malformed.body.merge.contains("Invalid value for: body"))
    assertEquals(refs.loggedOperations.get(), Vector.empty)

  test("should hide storage failures returned by read operations"):
    val server = buildOperationApi(getOperationsResult = GetOperationsResult.ReadFailed(RuntimeException("offline")))

    val operationsResponse = getOperations(server)
    val expected           = StatusCode.InternalServerError -> json("""{"message":"journal could not be read"}""")
    assertEquals(operationsResponse.code -> jsonBody(operationsResponse), expected)

  test("should hide the cause when logging an operation fails"):
    val server   = buildOperationApi(logOperationResult = LogOperationResult.LoggingFailed(RuntimeException("offline")))
    val response = post("/operations", loggedCareRequest, server)
    val expected = StatusCode.InternalServerError -> json("""{"message":"operation could not be logged"}""")
    assertEquals(response.code -> jsonBody(response), expected)

  test("should report when the operation to edit does not exist"):
    val response =
      put(s"/operations/${repotOperation.id.value}", careRequest, buildOperationApi())
    assertEquals(response.code -> jsonBody(response), StatusCode.NotFound -> json("""{"message":"operation not found"}"""))

  test("should reject changing an operation to another type"):
    val response =
      put(
        s"/operations/${repotOperation.id.value}",
        careRequest,
        buildOperationApi(editOperationResult = EditOperationResult.OperationTypeMismatch)
      )
    assertEquals(response.code -> jsonBody(response), StatusCode.Conflict -> json("""{"message":"operation type cannot be changed"}"""))

  test("should hide storage failures returned when editing"):
    val response = put(
      s"/operations/${repotOperation.id.value}",
      careRequest,
      buildOperationApi(editOperationResult = EditOperationResult.EditFailed(RuntimeException("offline")))
    )
    val expected = StatusCode.InternalServerError -> json("""{"message":"operation could not be edited"}""")
    assertEquals(response.code -> jsonBody(response), expected)

  test("should delete an operation"):
    val refs   = Refs()
    val server = buildOperationApi(refs, deleteOperationResult = DeleteOperationResult.Deleted)

    val response = delete(s"/operations/${careOperation.id.value}", server)

    assertEquals(response.code, StatusCode.NoContent)
    assertEquals(refs.deletedOperations.get(), Vector(careOperation.id))

  test("should report when the operation to delete does not exist"):
    val response = delete(s"/operations/${repotOperation.id.value}", buildOperationApi())
    assertEquals(response.code -> jsonBody(response), StatusCode.NotFound -> json("""{"message":"operation not found"}"""))

  test("should reject deleting a plant's current latest repot with a conflict"):
    val response =
      delete(s"/operations/${repotOperation.id.value}", buildOperationApi(deleteOperationResult = DeleteOperationResult.CannotDeleteLatestRepot))
    val expected = StatusCode.Conflict -> json("""{"message":"cannot delete the plant's current latest repot"}""")
    assertEquals(response.code -> jsonBody(response), expected)

  test("should hide storage failures returned when deleting"):
    val response = delete(
      s"/operations/${repotOperation.id.value}",
      buildOperationApi(deleteOperationResult = DeleteOperationResult.DeleteFailed(RuntimeException("offline")))
    )
    val expected = StatusCode.InternalServerError -> json("""{"message":"operation could not be deleted"}""")
    assertEquals(response.code -> jsonBody(response), expected)

  private case class Refs(
      requestedWindows: AtomicReference[Vector[OperationWindow]] = AtomicReference(Vector.empty),
      requestedDateRanges: AtomicReference[Vector[PlantId]] = AtomicReference(Vector.empty),
      loggedOperations: AtomicReference[Vector[(PlantId, Instant, OperationDetails)]] = AtomicReference(Vector.empty),
      editedOperations: AtomicReference[Vector[(OperationId, OperationDetails)]] = AtomicReference(Vector.empty),
      deletedOperations: AtomicReference[Vector[OperationId]] = AtomicReference(Vector.empty)
  )

  private def buildOperationApi(
      refs: Refs = Refs(),
      getOperationsResult: GetOperationsResult = GetOperationsResult.Read(OperationPage(Vector.empty, hasNextPage = false)),
      operationDateRangeResult: GetOperationDateRangeResult = GetOperationDateRangeResult.Read(OperationDateRange.Empty),
      logOperationResult: LogOperationResult = LogOperationResult.Logged(OperationId("logged")),
      editOperationResult: EditOperationResult = EditOperationResult.OperationMissing,
      deleteOperationResult: DeleteOperationResult = DeleteOperationResult.OperationMissing
  ) =
    val journal = new PlantJournal:
      override def createPlant(species: Species, maybeNickname: Option[Nickname], location: Location, substrate: Substrate): CreatePlantResult =
        fail("operation HTTP must not create plants")
      override def getPlants(status: PlantStatus): GetPlantsResult                               = fail("operation HTTP must not read plants")
      override def getArchivedCount: ArchivedCountResult                                         = fail("operation HTTP must not count plants")
      override def editPlant(id: PlantId, revise: PlantDetails => PlantDetails): EditPlantResult = fail("operation HTTP must not edit plants")
      override def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult =
        refs.requestedWindows.updateAndGet(_ :+ window)
        getOperationsResult
      override def getOperationDateRange(plantId: PlantId): GetOperationDateRangeResult =
        refs.requestedDateRanges.updateAndGet(_ :+ plantId).pipe(_ => operationDateRangeResult)
      override def logOperation(plantId: PlantId, at: Instant, details: OperationDetails): LogOperationResult =
        refs.loggedOperations.updateAndGet(_ :+ ((plantId, at, details))).pipe(_ => logOperationResult)
      override def editOperation(id: OperationId, details: OperationDetails): EditOperationResult =
        refs.editedOperations.updateAndGet(_ :+ (id -> details)).pipe(_ => editOperationResult)
      override def deleteOperation(id: OperationId): DeleteOperationResult =
        refs.deletedOperations.updateAndGet(_ :+ id).pipe(_ => deleteOperationResult)
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointsRunLogic(OperationApi.serverEndpoints(using journal))
      .backend()

  private type TestServer = SttpBackend[Identity, Any]

  private def getOperations(server: TestServer, offset: Int = 3, pageSize: Int = 10) =
    basicRequest
      .get(uri"http://test/operations?plantId=${plantId.value}&offset=$offset&pageSize=$pageSize")
      .send(server)

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
