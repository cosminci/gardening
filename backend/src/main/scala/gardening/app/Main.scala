package gardening.app

import cats.syntax.either.*
import gardening.adapters.http.{AttentionApi, HealthApi, OperationApi, PesticideApi, PlantApi, StaticSite, SubstrateComponentApi}
import gardening.adapters.persistence.SqliteLocation
import org.flywaydb.core.Flyway
import ox.{EitherMode, supervisedError}
import ox.either.orThrow
import sttp.tapir.server.netty.sync.NettySyncServer

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
      run(resources, version, staticDir, host, port)
    .orThrow

  private def run(resources: AppResources, version: String, staticDir: String, host: String, port: Int) =
    supervisedError(EitherMode[Throwable]()):
      Programs.make(resources).flatMap: programs =>
        val endpoints =
          List(HealthApi.serverEndpoint(version)) ++
            PlantApi.serverEndpoints(using programs.plantJournal, programs.plantAttentionMonitor) ++
            AttentionApi.serverEndpoints(using programs.plantAttentionMonitor) ++
            OperationApi.serverEndpoints(using programs.plantJournal) ++
            SubstrateComponentApi.serverEndpoints(using programs.substrateComponentCatalog) ++
            PesticideApi.serverEndpoints(using programs.pesticideCatalog) :+
            StaticSite.endpoint(staticDir)
        val _ = NettySyncServer().host(host).port(port).addEndpoints(endpoints).startAndWait()
        ().asRight
