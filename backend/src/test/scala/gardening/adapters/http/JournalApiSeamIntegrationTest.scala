package gardening.adapters.http

import cats.data.NonEmptyList
import cats.syntax.option.*
import gardening.domain.*
import io.circe.parser.parse
import io.github.iltotore.iron.autoRefine
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{Response, UriContext, basicRequest}
import sttp.model.StatusCode
import sttp.tapir.server.stub.TapirStubInterpreter

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.*

class JournalApiSeamIntegrationTest extends munit.FunSuite:

  private val date      = Instant.parse("2026-01-01T00:00:00Z")
  private val species   = Species("Ficus lyrata")
  private val nickname  = Nickname("Fern").some
  private val location  = Location("Balcony")
  private val substrate = Substrate.of(List(SubstratePart(TestNomenclatureIds.Perlite, 100))).getOrElse(fail("invalid substrate"))
  private val plant     = Plant(PlantId("p1"), PlantDetails(species, nickname, location, substrate, PlantStatus.Active))
  private val care      =
    OperationDetails.Care(Set(ActionType.Watered, ActionType.Pruned), Set.empty, MoistureLevel.Wet, Note("dry").some)
  private val repot          = OperationDetails.Repot(substrate, Note("fresh").some)
  private val careOperation  = Operation(OperationId("care"), plant.id, date, care)
  private val repotOperation = Operation(OperationId("repot"), plant.id, date.plusSeconds(1), repot)
  private val careRequest    = """{"kind":"care","actions":["watered","pruned"],"pesticides":[],"moisture":"wet","notes":"dry"}"""
  private val repotRequest   =
    """{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":100}],"notes":"fresh"}"""
  private val plantsJson =
    """[{"id":"p1","details":{"species":"Ficus lyrata","nickname":"Fern","location":"Balcony","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":100}],"status":"active"}}]"""
  private val operationsJson =
    """[{"id":"care","plantId":"p1","date":"2026-01-01T00:00:00Z","details":{"kind":"care","actions":["pruned","watered"],"pesticides":[],"moisture":"wet","notes":"dry"}},{"id":"repot","plantId":"p1","date":"2026-01-01T00:00:01Z","details":{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":100}],"notes":"fresh"}}]"""
  private val repotJson =
    """{"id":"repot","plantId":"p1","date":"2026-01-01T00:00:01Z","details":{"kind":"repot","substrate":[{"componentId":"00000000-0000-4000-8000-000000000003","share":100}],"notes":"fresh"}}"""

  test("should return active plants and the requested plant's care history"):
    val requestedPlants = AtomicReference(Vector.empty[PlantId])
    val journal         = buildJournal(
      getPlantsResult = GetPlantsResult.Read(Vector(plant)),
      getOperationsResult = GetOperationsResult.Read(Vector(careOperation, repotOperation)),
      requestedPlants = requestedPlants
    )

    val plantsResponse             = getPlants(journal)
    val operationsResponse         = getOperations(journal)
    assertEquals(plantsResponse.code     -> jsonBody(plantsResponse), StatusCode.Ok     -> json(plantsJson))
    assertEquals(operationsResponse.code -> jsonBody(operationsResponse), StatusCode.Ok -> json(operationsJson))
    assertEquals(requestedPlants.get(), Vector(plant.id))

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

  test("should hide corruption and storage failures returned by read operations"):
    val corruption = JournalCorruption(JournalRecord.Operation(OperationId("o1")), RuntimeException("private details"))
    val journal    = buildJournal(
      getPlantsResult = GetPlantsResult.Corrupted(NonEmptyList.one(corruption)),
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

  test("should hide corruption and storage failures returned when editing"):
    val corruption = JournalCorruption(JournalRecord.Operation(OperationId("o1")), RuntimeException("private details"))
    val corrupted  = editOperation(careRequest, buildJournal(editOperationResult = EditOperationResult.Corrupted(NonEmptyList.one(corruption))))
    val failed     = editOperation(careRequest, buildJournal(editOperationResult = EditOperationResult.EditFailed(RuntimeException("offline"))))
    val expected   = StatusCode.InternalServerError -> json("""{"message":"operation could not be edited"}""")
    assertEquals(corrupted.code -> jsonBody(corrupted), expected)
    assertEquals(failed.code    -> jsonBody(failed), expected)

  private def getPlants(journal: PlantJournal) =
    basicRequest.get(uri"http://test/plants").send(backend(journal))

  private def getOperations(journal: PlantJournal) =
    basicRequest.get(uri"http://test/plants/${plant.id.value}/operations").send(backend(journal))

  private def logOperation(body: String, journal: PlantJournal) =
    basicRequest.post(uri"http://test/plants/${plant.id.value}/operations").body(body).contentType("application/json").send(backend(journal))

  private def editOperation(body: String, journal: PlantJournal) =
    basicRequest.put(uri"http://test/operations/${repotOperation.id.value}").body(body).contentType("application/json").send(backend(journal))

  private def backend(journal: PlantJournal) =
    TapirStubInterpreter(SttpBackendStub.synchronous).whenServerEndpointsRunLogic(JournalApi.serverEndpoints(using journal)).backend()

  private def jsonBody(response: Response[Either[String, String]]) = json(response.body.merge)
  private def json(value: String)                                  = parse(value).fold(error => fail(error.message), identity)
  private def buildJournal(
      getPlantsResult: GetPlantsResult = GetPlantsResult.Read(Vector.empty),
      getOperationsResult: GetOperationsResult = GetOperationsResult.Read(Vector.empty),
      logOperationResult: LogOperationResult = LogOperationResult.Logged(OperationId("logged")),
      editOperationResult: EditOperationResult = EditOperationResult.OperationMissing,
      requestedPlants: AtomicReference[Vector[PlantId]] = AtomicReference(Vector.empty),
      loggedOperations: AtomicReference[Vector[(PlantId, OperationDetails)]] = AtomicReference(Vector.empty),
      editedOperations: AtomicReference[Vector[(OperationId, OperationDetails)]] = AtomicReference(Vector.empty)
  ) = new PlantJournal:
    override def getPlants: GetPlantsResult                           = getPlantsResult
    override def getOperations(plantId: PlantId): GetOperationsResult = requestedPlants.updateAndGet(_ :+ plantId).pipe(_ => getOperationsResult)
    override def logOperation(plantId: PlantId, details: OperationDetails): LogOperationResult =
      loggedOperations.updateAndGet(_ :+ (plantId -> details)).pipe(_ => logOperationResult)
    override def editOperation(id: OperationId, details: OperationDetails): EditOperationResult =
      editedOperations.updateAndGet(_ :+ (id -> details)).pipe(_ => editOperationResult)
    override def getSubstrateComponents: CatalogReadResult[SubstrateComponent]                             = CatalogReadResult.Read(Vector.empty)
    override def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent] =
      CatalogAddResult.AddFailed(RuntimeException("unused"))
    override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
      CatalogEditResult.RecordMissing
    override def getPesticides: CatalogReadResult[Pesticide]                    = CatalogReadResult.Read(Vector.empty)
    override def addPesticide(data: PesticideData): CatalogAddResult[Pesticide] =
      CatalogAddResult.AddFailed(RuntimeException("unused"))
    override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
      CatalogEditResult.RecordMissing
