package gardening.domain

import java.time.Instant

class HealthReportSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-01-01T00:00:00Z")

  test("from marks the report Healthy when the database is reachable"):
    val report = HealthReport.from("1.0.0", databaseOk = true, at)
    assertEquals(report.status, HealthStatus.Healthy)
    assertEquals(report.version, "1.0.0")
    assertEquals(report.database, true)
    assertEquals(report.checkedAt, at)

  test("from marks the report Degraded when the database is unreachable"):
    val report = HealthReport.from("1.0.0", databaseOk = false, at)
    assertEquals(report.status, HealthStatus.Degraded)
    assertEquals(report.database, false)
