package gardening.app

import cats.syntax.either.*
import gardening.adapters.http.*
import gardening.adapters.persistence.SqliteLocation
import gardening.adapters.prometheus.{PrometheusAttentionFeedMetrics, PrometheusStorageMetrics}
import gardening.adapters.system.SystemClock
import gardening.domain.Logger
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import ox.{EitherMode, supervisedError}
import sttp.shared.Identity
import sttp.tapir.server.metrics.prometheus.PrometheusMetrics
import sttp.tapir.server.netty.sync.{NettySyncServer, NettySyncServerOptions}

import java.nio.file.Paths
import scala.concurrent.duration.*
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

    val registry = new PrometheusRegistry
    JvmMetrics.builder().register(registry)
    val prometheusMetrics = PrometheusMetrics.default[Identity](namespace = "gardening", registry = registry)
    val serverOptions     =
      NettySyncServerOptions.customiseInterceptors.metricsInterceptor(
        prometheusMetrics.metricsInterceptor(Seq(AttentionApi.attentionFeedEndpoint))
      ).options
    val feedHeartbeats = ConnectionHeartbeats.make(staleness = 5.seconds)(using SystemClock)
    PrometheusAttentionFeedMetrics.register(registry, feedHeartbeats)
    PrometheusStorageMetrics.register(registry, Paths.get(dbPath), photosDir)

    val outcome = Using.resource(AppResources.acquire(SqliteLocation.File(dbPath))): resources =>
      supervisedError(EitherMode[Throwable]()):
        val _ = Flyway.configure().dataSource(resources.dataSource).load().migrate()
        Programs.make(resources, photosDir, registry).flatMap: programs =>
          val endpoints = aggregateEndpoints(programs, version, staticDir, prometheusMetrics, feedHeartbeats)
          log.info(s"gardening backend ready host=$host port=$port version=$version")
          NettySyncServer(serverOptions).host(host).port(port).addEndpoints(endpoints).startAndWait().asRight
    outcome.left.foreach(log.error("startup", _))
    if outcome.isLeft then sys.exit(1)

  private def aggregateEndpoints(
      programs: Programs,
      version: String,
      staticDir: String,
      prometheusMetrics: PrometheusMetrics[Identity],
      feedHeartbeats: ConnectionHeartbeats
  ) =
    List(HealthApi.serverEndpoint(version), prometheusMetrics.metricsEndpoint) ++
      PlantApi.serverEndpoints(using programs.plants, programs.plantAttentionMonitor) ++
      AttentionApi.serverEndpoints(using programs.plantAttentionMonitor, feedHeartbeats) ++
      OperationApi.serverEndpoints(using programs.operations) ++
      SubstrateApi.serverEndpoints(using programs.substrateCatalog) ++
      PhotoApi.serverEndpoints(using programs.plants) ++
      PesticideApi.serverEndpoints(using programs.pesticideCatalog) :+
      StaticSite.endpoint(staticDir)
