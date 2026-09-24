package gardening.adapters.http

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.attention.*
import io.circe.parser.parse
import io.github.iltotore.iron.autoRefine
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{Response, basicRequest}
import sttp.model.{StatusCode, Uri}
import sttp.tapir.server.stub.TapirStubInterpreter

import java.time.Instant
import scala.concurrent.duration.*

class AttentionApiSeamIntegrationTest extends munit.FunSuite:

  private val date       = Instant.parse("2026-01-01T00:00:00Z")
  private val projection = AttentionProjection(
    measuredAt = date,
    plants = Vector(
      PlantAttention(PlantId("unknown"), WateringAttention.Unavailable(sampleCount = 4, maybeElapsed = 12.hours.some)),
      PlantAttention(PlantId("p1"), WateringAttention.RedAlert(sampleCount = 5, averageInterval = 24.hours, elapsed = 49.hours)),
      PlantAttention(PlantId("current"), WateringAttention.Current(sampleCount = 5, averageInterval = 24.hours, elapsed = 12.hours)),
      PlantAttention(PlantId("zero-average"), WateringAttention.Overdue(sampleCount = 5, averageInterval = 0.millis, elapsed = 1.milli))
    )
  )

  test("should expose the attention projection with its existing wire shape"):
    val refs   = Refs(projection)
    val server = buildServer(refs)

    val response = basicRequest.get(Uri.unsafeParse("http://test/attention")).send(server)

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
    assertEquals(response.code -> jsonBody(response), StatusCode.Ok -> json(expected))

  private case class Refs(projection: AttentionProjection)

  private def buildServer(refs: Refs) =
    val attention = new PlantAttentionMonitor:
      override def current: AttentionProjection           = refs.projection
      override def refreshAll: RefreshAttentionResult     = fail("HTTP must not refresh attention")
      override def removeArchivedPlant(id: PlantId): Unit =
        fail("reading attention must not remove plants")
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointsRunLogic(AttentionApi.serverEndpoints(using attention))
      .backend()

  private def jsonBody(response: Response[Either[String, String]]) = parse(response.body.merge).fold(error => fail(error.message), identity)
  private def json(value: String)                                  = parse(value).fold(error => fail(error.message), identity)
