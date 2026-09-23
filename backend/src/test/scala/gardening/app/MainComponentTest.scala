package gardening.app

import cats.syntax.either.*
import gardening.domain.attention.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}

class MainComponentTest extends munit.FunSuite:

  test("should start HTTP with the materialized attention projection"):
    val projection  = AttentionProjection(Instant.EPOCH, Vector.empty)
    val keepPolling = CountDownLatch(1)
    val attention   = new PlantAttentionMonitor:
      override def current: AttentionProjection       = projection
      override def refreshAll: RefreshAttentionResult = fail("interval did not elapse")

    val result = Main.start(
      http = () => assertEquals(attention.current, projection),
      plantAttentionMonitor = attention,
      awaitNext = () =>
        val _ = keepPolling.await()
        ().asRight
    )

    assertEquals(result, ().asRight)

  test("should keep serving after an attention refresh fails"):
    val httpStarted = CountDownLatch(1)
    val httpStopped = CountDownLatch(1)
    val refreshes   = AtomicInteger(0)
    val pollFailure = RuntimeException("polling failed")
    val attention   = new PlantAttentionMonitor:
      override def current: AttentionProjection       = AttentionProjection(Instant.EPOCH, Vector.empty)
      override def refreshAll: RefreshAttentionResult =
        refreshes.incrementAndGet()
        RefreshAttentionResult.RefreshFailed(RuntimeException("attention failed"))

    val result = Main.start(
      http = () =>
        httpStarted.countDown()
        try Thread.sleep(Long.MaxValue)
        finally httpStopped.countDown()
      ,
      plantAttentionMonitor = attention,
      awaitNext = () =>
        val _ = httpStarted.await(1, TimeUnit.SECONDS)
        if refreshes.get().equals(0) then ().asRight else pollFailure.asLeft
    )

    assertEquals(result, pollFailure.asLeft)
    assertEquals(refreshes.get(), 1)
    assert(httpStopped.await(1, TimeUnit.SECONDS))
