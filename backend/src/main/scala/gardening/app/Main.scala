package gardening.app

import cats.syntax.either.*
import gardening.adapters.http.{AttentionApi, HealthApi, OperationApi, PesticideApi, PhotoApi, PlantApi, StaticSite, SubstrateApi}
import gardening.adapters.persistence.SqliteLocation
import gardening.domain.Logger
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import ox.{EitherMode, supervisedError}
import sttp.tapir.server.netty.sync.NettySyncServer

import java.nio.file.Paths
import scala.util.Using

object Main:

  def main(args: Array[String]): Unit =
    val version   = sys.env.getOrElse("GARDENING_APP_VERSION", "0.0.0-dev")
    val staticDir = sys.env.getOrElse("GARDENING_STATIC_DIR", "static")
    val port      = sys.env.get("GARDENING_PORT").flatMap(_.toIntOption).getOrElse(8080)
    val host      = sys.env.getOrElse("GARDENING_HOST", "0.0.0.0")
    val dbPath    = sys.env.getOrElse("GARDENING_DB_PATH", "gardening.db")
    val photosDir = Paths.get(sys.env.getOrElse("GARDENING_PHOTOS_DIR", "photos"))

    given log: Logger = new Logger:
      private val underlying           = LoggerFactory.getLogger("gardening")
      def info(message: String): Unit  = underlying.info(message)
      def error(message: String): Unit = underlying.error(message)

    val outcome = Using.resource(AppResources.acquire(SqliteLocation.File(dbPath))): resources =>
      supervisedError(EitherMode[Throwable]()):
        val _ = Flyway.configure().dataSource(resources.dataSource).load().migrate()
        Programs.make(resources, photosDir).flatMap: programs =>
          val endpoints = aggregateEndpoints(programs, version, staticDir)
          log.info(s"gardening backend ready host=$host port=$port version=$version")
          NettySyncServer().host(host).port(port).addEndpoints(endpoints).startAndWait().asRight
    outcome.left.foreach(log.error("startup", _))
    if outcome.isLeft then sys.exit(1)

  private def aggregateEndpoints(programs: Programs, version: String, staticDir: String) =
    List(HealthApi.serverEndpoint(version)) ++
      PlantApi.serverEndpoints(using programs.plantJournal, programs.plantAttentionMonitor) ++
      AttentionApi.serverEndpoints(using programs.plantAttentionMonitor) ++
      OperationApi.serverEndpoints(using programs.plantJournal) ++
      SubstrateApi.serverEndpoints(using programs.substrateCatalog) ++
      PhotoApi.serverEndpoints(using programs.plantJournal) ++
      PesticideApi.serverEndpoints(using programs.pesticideCatalog) :+
      StaticSite.endpoint(staticDir)
