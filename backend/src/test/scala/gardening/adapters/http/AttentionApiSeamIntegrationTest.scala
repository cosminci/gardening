package gardening.adapters.http

import cats.syntax.option.*
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
    val server = buildServer(projection)

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
    val attention = new PlantAttentionMonitor:
      override def current: AttentionProjection       = projection
      override def refreshAll: RefreshAttentionResult = fail("HTTP must not refresh attention")

    val endpoints = supervised(AttentionApi.serverEndpoints(using attention))

    assertEquals(endpoints.size, 2)

  test("should push the current projection immediately on connect, then again only once it changes"):
    val calls         = AtomicInteger(0)
    val stableRepeats = 5
    val initial       = AttentionProjection(date, Vector.empty)
    val changed       = AttentionProjection(date.plusSeconds(1), Vector.empty)
    val attention     = new PlantAttentionMonitor:
      override def current: AttentionProjection       = if calls.getAndIncrement() < stableRepeats then initial else changed
      override def refreshAll: RefreshAttentionResult = fail("the push adapter must not trigger recomputation")

    val pushed = supervised:
      val feed           = AttentionApi.AttentionFeed.startBroadcasting(attention, pollInterval = 1.milli)
      val stillConnected = Flow.tick(1.hour, "still connected")
      feed.subscribe()(stillConnected).take(2).runToList()
    val expectedPushed = List(initial, changed)

    assertEquals(pushed, expectedPushed)

  test("should replay the last broadcast projection immediately to a newly connected browser"):
    val projection = AttentionProjection(date, Vector.empty)
    val attention  = new PlantAttentionMonitor:
      override def current: AttentionProjection       = projection
      override def refreshAll: RefreshAttentionResult = fail("the push adapter must not trigger recomputation")

    val (firstConnectionPushed, secondConnectionPushed) = supervised:
      val feed             = AttentionApi.AttentionFeed.startBroadcasting(attention, pollInterval = 1.milli)
      val firstConnection  = Flow.tick(1.hour, "still connected")
      val first            = feed.subscribe()(firstConnection).take(1).runToList()
      val secondConnection = Flow.tick(1.hour, "still connected")
      val second           = feed.subscribe()(secondConnection).take(1).runToList()
      (first, second)

    assertEquals(firstConnectionPushed, List(projection))
    assertEquals(secondConnectionPushed, List(projection))

  private def buildServer(projection: AttentionProjection) =
    val attention = new PlantAttentionMonitor:
      override def current: AttentionProjection       = projection
      override def refreshAll: RefreshAttentionResult = fail("HTTP must not refresh attention")
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointsRunLogic(List(AttentionApi.httpServerEndpoint(using attention)))
      .backend()

  private def jsonBody(response: Response[Either[String, String]]) = parse(response.body.merge).fold(error => fail(error.message), identity)
  private def json(value: String)                                  = parse(value).fold(error => fail(error.message), identity)
