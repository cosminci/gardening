package gardening.application

import gardening.capabilities.{Clock, Database, DatabaseProbe}
import gardening.domain.HealthStatus

import java.time.Instant

class HealthServiceSuite extends munit.FunSuite:
  private val fixedClock: Clock = new Clock:
    def now(): Instant = Instant.EPOCH

  private def database(reachable: Boolean): Database =
    Database.make(
      new DatabaseProbe:
        def probe(): Boolean = reachable
    )

  test("check reports Healthy when the database probe succeeds"):
    given Database = database(reachable = true)
    given Clock    = fixedClock
    val report     = HealthService.check("1.2.3")
    assertEquals(report.status, HealthStatus.Healthy)
    assertEquals(report.version, "1.2.3")
    assertEquals(report.database, true)
    assertEquals(report.checkedAt, Instant.EPOCH)

  test("check reports Degraded when the database probe fails"):
    given Database = database(reachable = false)
    given Clock    = fixedClock
    val report     = HealthService.check("9")
    assertEquals(report.status, HealthStatus.Degraded)
    assertEquals(report.database, false)
