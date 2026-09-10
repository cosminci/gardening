package gardening.application

import language.experimental.captureChecking

import gardening.capabilities.{Clock, Database}
import gardening.domain.HealthReport

/**
 * Produces a health report from the injected capabilities. The database session obtained from `use` is confined to that
 * call and never escapes into the returned report.
 */
object HealthService:
  def check(version: String)(using db: Database, clock: Clock): HealthReport =
    val databaseOk = db.use(session ?=> session.probe())
    HealthReport.from(version, databaseOk, clock.now())
