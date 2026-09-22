package gardening.app

import gardening.domain.attention.*

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class PeriodicAttentionRefresherComponentTest extends munit.FunSuite:

  test("should refresh attention on demand without awaiting an interval"):
    val refreshes = AtomicInteger()
    val waits     = AtomicInteger()
    val attention = new PlantAttentionRefresh:
      override def refreshAll: RefreshAttentionResult =
        val _ = refreshes.incrementAndGet()
        RefreshAttentionResult.Refreshed(AttentionProjection(Instant.EPOCH, Vector.empty))

    val result =
      PeriodicAttentionRefresher(
        attention,
        () =>
          val _ = waits.incrementAndGet()
          Right(())
      ).refreshAttention()

    assertEquals(result, Right(()))
    assertEquals(refreshes.get(), 1)
    assertEquals(waits.get(), 0)

  test("should await each interval before refreshing attention"):
    val stop      = RuntimeException("stop")
    val refreshes = AtomicInteger()
    val waits     = AtomicInteger()
    val attention = new PlantAttentionRefresh:
      override def refreshAll: RefreshAttentionResult =
        val _ = refreshes.incrementAndGet()
        RefreshAttentionResult.Refreshed(AttentionProjection(Instant.EPOCH, Vector.empty))

    val result =
      PeriodicAttentionRefresher(
        attention,
        () =>
          waits.incrementAndGet() match
            case 1 => Right(())
            case _ => Left(stop)
      ).refreshPeriodically()

    assertEquals(result, Left(stop))
    assertEquals(refreshes.get(), 1)
    assertEquals(waits.get(), 2)

  test("should stop periodic refresh when attention cannot be refreshed"):
    val failure   = RuntimeException("refresh failed")
    val waits     = AtomicInteger()
    val attention = new PlantAttentionRefresh:
      override def refreshAll: RefreshAttentionResult = RefreshAttentionResult.RefreshFailed(failure)

    val result =
      PeriodicAttentionRefresher(
        attention,
        () =>
          val _ = waits.incrementAndGet()
          Right(())
      ).refreshPeriodically()

    assertEquals(result, Left(failure))
    assertEquals(waits.get(), 1)
