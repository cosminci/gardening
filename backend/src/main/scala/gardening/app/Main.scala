package gardening.app

import cats.syntax.either.*
import gardening.adapters.http.{HealthApi, JournalApi, StaticSite, SubstrateComponentApi}
import gardening.adapters.persistence.SqliteLocation
import gardening.domain.attention.PlantAttentionMonitor
import org.flywaydb.core.Flyway
import ox.{EitherMode, forkError, sleep, supervisedError}
import ox.either.*
import sttp.tapir.server.netty.sync.NettySyncServer

import scala.concurrent.duration.*
import scala.util.chaining.*
import scala.util.Using

object Main:

  def main(args: Array[String]): Unit =
    val version   = sys.env.getOrElse("GARDENING_APP_VERSION", "0.0.0-dev")
    val staticDir = sys.env.getOrElse("GARDENING_STATIC_DIR", "static")
    val port      = sys.env.get("GARDENING_PORT").flatMap(_.toIntOption).getOrElse(8080)
    val host      = sys.env.getOrElse("GARDENING_HOST", "0.0.0.0")
    val dbPath    = sys.env.getOrElse("GARDENING_DB_PATH", "gardening.db")

    Using.resource(AppResources.acquire(SqliteLocation.File(dbPath))): resources =>
      val _ = Flyway.configure().dataSource(resources.dataSource).load().migrate()
      Programs.make(resources).flatMap: programs =>
        val endpoints =
          List(HealthApi.serverEndpoint(version)) ++
            JournalApi.serverEndpoints(using programs.plantJournal, programs.plantAttentionMonitor) ++
            SubstrateComponentApi.serverEndpoints(using programs.substrateComponentCatalog) :+
            StaticSite.endpoint(staticDir)
        run(programs.plantAttentionMonitor):
          val _ = NettySyncServer().host(host).port(port).addEndpoints(endpoints).startAndWait()
      .orThrow

  private def run(attention: PlantAttentionMonitor)(http: => Unit) =
    supervisedError(EitherMode[Throwable]()):
      val _ = forkError(pollPlantAttention(attention))
      http.pipe(_ => ().asRight)

  private def pollPlantAttention(attention: PlantAttentionMonitor) =
    Iterator.continually {
      sleep(5.minutes)
      val _ = attention.refreshAll
    }.foreach(identity).pipe(_ => ().asRight)
