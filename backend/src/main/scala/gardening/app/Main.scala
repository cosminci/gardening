package gardening.app

import gardening.adapters.http.{HealthApi, JournalApi, StaticSite}
import gardening.adapters.persistence.{Sqlite, SqliteLocation, SqlitePlantJournalStore}
import gardening.adapters.system.{SystemClock, UuidIdGenerator}
import gardening.domain.PlantJournal
import org.flywaydb.core.Flyway
import sttp.tapir.server.netty.sync.NettySyncServer

object Main:

  def main(args: Array[String]): Unit =
    val version   = sys.env.getOrElse("GARDENING_APP_VERSION", "0.0.0-dev")
    val staticDir = sys.env.getOrElse("GARDENING_STATIC_DIR", "static")
    val port      = sys.env.get("GARDENING_PORT").flatMap(_.toIntOption).getOrElse(8080)
    val host      = sys.env.getOrElse("GARDENING_HOST", "0.0.0.0")
    val dbPath    = sys.env.getOrElse("GARDENING_DB_PATH", "gardening.db")

    val connection = Sqlite.connect(SqliteLocation.File(dbPath))
    try
      val _         = Flyway.configure().dataSource(connection.dataSource).load().migrate()
      val store     = SqlitePlantJournalStore.make(connection.transactor)
      val journal   = PlantJournal.make(using store, UuidIdGenerator, SystemClock)
      val endpoints =
        List(HealthApi.serverEndpoint(version)) ++ JournalApi.serverEndpoints(using journal) ++ List(StaticSite.endpoint(staticDir))
      val _ = NettySyncServer().host(host).port(port).addEndpoints(endpoints).startAndWait()
    finally connection.close()
