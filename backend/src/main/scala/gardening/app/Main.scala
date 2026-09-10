package gardening.app

import gardening.adapters.http.{HealthApi, StaticSite}
import gardening.adapters.persistence.{MagnumDatabaseProbe, SqliteDataSource}
import gardening.adapters.system.SystemClock
import gardening.capabilities.{Clock, Database}
import sttp.tapir.server.netty.sync.NettySyncServer

/**
 * Composition root. Wires live capabilities and serves the API plus the built frontend on a single port. Not
 * unit-tested: exercised end to end by the packaged runtime image.
 */
object Main:
  def main(args: Array[String]): Unit =
    val version   = sys.env.getOrElse("GARDENING_APP_VERSION", "0.0.0-dev")
    val dbPath    = sys.env.getOrElse("GARDENING_DB_PATH", "gardening.db")
    val staticDir = sys.env.getOrElse("GARDENING_STATIC_DIR", "static")
    val port      = sys.env.get("GARDENING_PORT").flatMap(_.toIntOption).getOrElse(8080)
    val host      = sys.env.getOrElse("GARDENING_HOST", "0.0.0.0")

    given Database = Database.make(MagnumDatabaseProbe(SqliteDataSource(s"jdbc:sqlite:$dbPath")))
    given Clock    = SystemClock

    val endpoints = HealthApi.serverEndpoint(version) :: StaticSite.endpoints(staticDir)
    val _         = NettySyncServer().host(host).port(port).addEndpoints(endpoints).startAndWait()
