package gardening.adapters.http

import cats.syntax.option.*
import gardening.domain.*
import io.circe.parser.parse
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{Response, basicRequest}
import sttp.model.{StatusCode, Uri}
import sttp.tapir.server.stub.TapirStubInterpreter

import java.util.UUID

class JournalCatalogApiSeamIntegrationTest extends munit.FunSuite:

  private val componentId   = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val pesticideId   = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
  private val componentData = SubstrateComponentData(NomenclatureName("Pumice"), NomenclatureInfo("porous").some)
  private val pesticideData = PesticideData(NomenclatureName("Sulfur"), PesticideType("Fungicide"), NomenclatureInfo("2g/L").some)
  private val component     = SubstrateComponent(componentId, componentData)
  private val pesticide     = Pesticide(pesticideId, pesticideData)

  test("should list and add catalog records"):
    val journal = JournalStub(
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
    val journal = JournalStub(
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
      put(s"/substrate-components/${componentId.value}", componentDataJson, JournalStub()),
      StatusCode.NotFound,
      """{"message":"nomenclature not found"}"""
    )
    assertResponse(
      put("/pesticides/not-a-uuid", pesticideDataJson, journal),
      StatusCode.BadRequest,
      """{"message":"invalid nomenclature id"}"""
    )
    assertResponse(
      put(s"/pesticides/${pesticideId.value}", pesticideDataJson, JournalStub()),
      StatusCode.NotFound,
      """{"message":"nomenclature not found"}"""
    )

  test("should hide catalog storage failures"):
    val failure = RuntimeException("private details")
    val journal = JournalStub(
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

  private val componentDataJson = """{"name":"Pumice","info":"porous"}"""
  private val pesticideDataJson = """{"name":"Sulfur","type":"Fungicide","info":"2g/L"}"""
  private val componentJson     = s"""{"id":"${componentId.value}","data":$componentDataJson}"""
  private val pesticideJson     = s"""{"id":"${pesticideId.value}","data":$pesticideDataJson}"""
  private val catalogReadError  = """{"message":"nomenclatures could not be read"}"""
  private val catalogWriteError = """{"message":"nomenclature could not be saved"}"""

  private def get(path: String, journal: PlantJournal) =
    basicRequest.get(Uri.unsafeParse(s"http://test$path")).send(backend(journal))

  private def post(path: String, body: String, journal: PlantJournal) =
    basicRequest.post(Uri.unsafeParse(s"http://test$path")).body(body).contentType("application/json").send(backend(journal))

  private def put(path: String, body: String, journal: PlantJournal) =
    basicRequest.put(Uri.unsafeParse(s"http://test$path")).body(body).contentType("application/json").send(backend(journal))

  private def backend(journal: PlantJournal) =
    TapirStubInterpreter(SttpBackendStub.synchronous).whenServerEndpointsRunLogic(JournalApi.serverEndpoints(using journal)).backend()

  private def assertResponse(response: Response[Either[String, String]], status: StatusCode, body: String): Unit =
    assertEquals(response.code -> parse(response.body.merge), status -> parse(body))

  final private case class JournalStub(
      componentReadResult: CatalogReadResult[SubstrateComponent] = CatalogReadResult.Read(Vector.empty),
      componentAddResult: CatalogAddResult[SubstrateComponent] = CatalogAddResult.Added(component),
      componentEditResult: CatalogEditResult[SubstrateComponent] = CatalogEditResult.RecordMissing,
      pesticideReadResult: CatalogReadResult[Pesticide] = CatalogReadResult.Read(Vector.empty),
      pesticideAddResult: CatalogAddResult[Pesticide] = CatalogAddResult.Added(pesticide),
      pesticideEditResult: CatalogEditResult[Pesticide] = CatalogEditResult.RecordMissing
  ) extends PlantJournal:
    override def getPlants: GetPlantsResult                                                    = GetPlantsResult.Read(Vector.empty)
    override def getOperations(plantId: PlantId): GetOperationsResult                          = GetOperationsResult.Read(Vector.empty)
    override def logOperation(plantId: PlantId, details: OperationDetails): LogOperationResult =
      LogOperationResult.LoggingFailed(RuntimeException("unused"))
    override def editOperation(id: OperationId, details: OperationDetails): EditOperationResult            = EditOperationResult.OperationMissing
    override def getSubstrateComponents: CatalogReadResult[SubstrateComponent]                             = componentReadResult
    override def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent] = componentAddResult
    override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
      componentEditResult
    override def getPesticides: CatalogReadResult[Pesticide]                                       = pesticideReadResult
    override def addPesticide(data: PesticideData): CatalogAddResult[Pesticide]                    = pesticideAddResult
    override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] = pesticideEditResult
