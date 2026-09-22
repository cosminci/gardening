package gardening.app

import gardening.domain.attention.*

import java.time.Instant
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

class MainComponentTest extends munit.FunSuite:

  test("should publish initial attention before starting the HTTP server"):
    val refreshes = AtomicInteger()
    val stop      = RuntimeException("stop")
    val attention = new PlantAttentionService:
      override def current: GetAttentionProjectionResult = GetAttentionProjectionResult.Unavailable
      override def refreshAll: RefreshAttentionResult    =
        val _ = refreshes.incrementAndGet()
        RefreshAttentionResult.Refreshed(AttentionProjection(Instant.EPOCH, Vector.empty))

    val result = Main.start(
      http = () => assertEquals(refreshes.get(), 1),
      plantAttentionService = attention,
      awaitNext = () => Left(stop)
    )

    assertEquals(result, Left(stop))

  test("should not start the HTTP server when initial attention refresh fails"):
    val httpStarts = AtomicInteger()
    val failure    = RuntimeException("initial attention failed")
    val attention  = new PlantAttentionService:
      override def current: GetAttentionProjectionResult = GetAttentionProjectionResult.Unavailable
      override def refreshAll: RefreshAttentionResult    = RefreshAttentionResult.RefreshFailed(failure)

    val result = Main.start(
      http = () =>
        val _ = httpStarts.incrementAndGet()
      ,
      plantAttentionService = attention,
      awaitNext = () => Right(())
    )

    assertEquals(result, Left(failure))
    assertEquals(httpStarts.get(), 0)

  test("should cancel the HTTP server when attention refresh fails"):
    val httpStarted = CountDownLatch(1)
    val httpStopped = CountDownLatch(1)
    val failure     = RuntimeException("attention failed")
    val refreshes   = AtomicInteger()
    val attention   = new PlantAttentionService:
      override def current: GetAttentionProjectionResult = GetAttentionProjectionResult.Unavailable
      override def refreshAll: RefreshAttentionResult    =
        refreshes.incrementAndGet() match
          case 1 => RefreshAttentionResult.Refreshed(AttentionProjection(Instant.EPOCH, Vector.empty))
          case _ => RefreshAttentionResult.RefreshFailed(failure)

    val result = Main.start(
      http = () =>
        httpStarted.countDown()
        try Thread.sleep(Long.MaxValue)
        finally httpStopped.countDown()
      ,
      plantAttentionService = attention,
      awaitNext = () =>
        val _ = httpStarted.await(1, TimeUnit.SECONDS)
        Right(())
    )

    assertEquals(result, Left(failure))
    assert(httpStopped.await(1, TimeUnit.SECONDS))
