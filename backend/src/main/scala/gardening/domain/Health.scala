package gardening.domain

import language.experimental.captureChecking

import java.time.Instant

/** Overall health of the service. */
enum HealthStatus:
  case Healthy, Degraded

/**
 * A point-in-time health report. Pure value: capture checking (enabled for this file) confirms the domain holds no
 * capabilities.
 */
final case class HealthReport(status: HealthStatus, version: String, database: Boolean, checkedAt: Instant)

object HealthReport:
  def from(version: String, databaseOk: Boolean, now: Instant): HealthReport =
    val status = if databaseOk then HealthStatus.Healthy else HealthStatus.Degraded
    HealthReport(status, version, databaseOk, now)
