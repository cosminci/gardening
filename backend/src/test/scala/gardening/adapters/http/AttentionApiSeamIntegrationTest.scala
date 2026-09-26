package gardening.adapters.http

import cats.syntax.option.*
import gardening.adapters.system.SystemClock
import gardening.domain.*
import gardening.domain.attention.*
import io.circe.parser.parse
import io.github.iltotore.iron.autoRefine
import ox.flow.Flow
import ox.supervised
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{Response, basicRequest}
import sttp.model.{StatusCode, Uri}
import sttp.tapir.server.stub.TapirStubInterpreter

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
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
    val server = buildServer(buildAttention(Refs(), Vector(projection)))

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

  test("should expose the HTTP and websocket feed endpoints for the composition root to wire up"):
    val attention = buildAttention(Refs(), Vector(projection))

    val endpoints = AttentionApi.serverEndpoints(using attention, buildHeartbeats)

    val describedEndpoints = endpoints.map: server =>
      s"${server.endpoint.method.getOrElse(fail("endpoint without a method"))} ${server.endpoint.showPathTemplate()}"
    val expectedEndpoints = List("GET /attention", "GET /attention/feed")
    assertEquals(describedEndpoints, expectedEndpoints)

  test("should push the current projection immediately on connect, then again only once it changes"):
    val initial   = AttentionProjection(date, Vector.empty)
    val changed   = AttentionProjection(date.plusSeconds(1), Vector.empty)
    val attention = buildAttention(Refs(), Vector(initial, initial, changed))

    val pollInterval = 1.milli
    val pushed       = supervised:
      val stillConnected = Flow.tick(1.hour, "still connected")
      AttentionApi.attentionFeed(attention, pollInterval, buildHeartbeats)(stillConnected).take(2).runToList()

    val expectedPushed = List(initial, changed)
    assertEquals(pushed, expectedPushed)

  test("should push the current projection immediately to every newly connected browser"):
    val pollInterval = 1.milli
    val heartbeats   = buildHeartbeats

    val (firstConnectionPushed, secondConnectionPushed) = supervised:
      val tick   = Flow.tick(1.hour, "connected")
      val first  = AttentionApi.attentionFeed(buildAttention(Refs(), Vector(projection)), pollInterval, heartbeats)(tick).take(1).runToList()
      val second = AttentionApi.attentionFeed(buildAttention(Refs(), Vector(projection)), pollInterval, heartbeats)(tick).take(1).runToList()
      (first, second)

    assertEquals(firstConnectionPushed, List(projection))
    assertEquals(secondConnectionPushed, List(projection))

  private case class Refs(attentionReads: AtomicInteger = AtomicInteger(0))

  private def buildAttention(refs: Refs, projections: Vector[AttentionProjection]): PlantAttentionMonitor =
    new PlantAttentionMonitor:
      override def current: AttentionProjection =
        projections.lift(refs.attentionReads.getAndIncrement()).orElse(projections.lastOption).getOrElse(fail("missing attention projection"))
      override def refreshAll: RefreshAttentionResult = fail("attention must not be refreshed by a read path")

  private def buildServer(attention: PlantAttentionMonitor) =
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointsRunLogic(List(AttentionApi.httpServerEndpoint(using attention)))
      .backend()

  private def buildHeartbeats = ConnectionHeartbeats.make(staleness = 5.seconds)(using SystemClock)

  private def jsonBody(response: Response[Either[String, String]]) = parse(response.body.merge).fold(error => fail(error.message), identity)
  private def json(value: String)                                  = parse(value).fold(error => fail(error.message), identity)
