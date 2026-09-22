package gardening.app

import cats.syntax.either.*
import gardening.domain.attention.*

import java.time.Instant
import java.util.concurrent.{CountDownLatch, TimeUnit}

class MainComponentTest extends munit.FunSuite:

  test("should start HTTP with the materialized attention projection"):
    val projection  = AttentionProjection(Instant.EPOCH, Vector.empty)
    val keepPolling = CountDownLatch(1)
    val attention   = new PlantAttentionService:
      override def current: AttentionProjection       = projection
      override def refreshAll: RefreshAttentionResult = fail("interval did not elapse")

    val result = Main.start(
      http = () => assertEquals(attention.current, projection),
      plantAttentionService = attention,
      awaitNext = () =>
        val _ = keepPolling.await()
        ().asRight
    )

    assertEquals(result, ().asRight)

  test("should cancel the HTTP server when attention refresh fails"):
    val httpStarted = CountDownLatch(1)
    val httpStopped = CountDownLatch(1)
    val failure     = RuntimeException("attention failed")
    val attention   = new PlantAttentionService:
      override def current: AttentionProjection       = AttentionProjection(Instant.EPOCH, Vector.empty)
      override def refreshAll: RefreshAttentionResult = RefreshAttentionResult.RefreshFailed(failure)

    val result = Main.start(
      http = () =>
        httpStarted.countDown()
        try Thread.sleep(Long.MaxValue)
        finally httpStopped.countDown()
      ,
      plantAttentionService = attention,
      awaitNext = () =>
        val _ = httpStarted.await(1, TimeUnit.SECONDS)
        ().asRight
    )

    assertEquals(result, failure.asLeft)
    assert(httpStopped.await(1, TimeUnit.SECONDS))
