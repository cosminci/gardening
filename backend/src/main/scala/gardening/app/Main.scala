package gardening.app

import gardening.adapters.http.{HealthApi, JournalApi, StaticSite}
import gardening.adapters.persistence.SqliteLocation
import org.flywaydb.core.Flyway
import ox.{EitherMode, forkError, supervisedError}
import ox.either.*
import sttp.tapir.server.netty.sync.NettySyncServer

import scala.concurrent.duration.*
import scala.util.Using

object Main:

  def main(args: Array[String]): Unit =
    val version   = sys.env.getOrElse("GARDENING_APP_VERSION", "0.0.0-dev")
    val staticDir = sys.env.getOrElse("GARDENING_STATIC_DIR", "static")
    val port      = sys.env.get("GARDENING_PORT").flatMap(_.toIntOption).getOrElse(8080)
    val host      = sys.env.getOrElse("GARDENING_HOST", "0.0.0.0")
    val dbPath    = sys.env.getOrElse("GARDENING_DB_PATH", "gardening.db")

    Using.resource(AppResources.acquire(SqliteLocation.File(dbPath))): resources =>
      val _         = Flyway.configure().dataSource(resources.dataSource).load().migrate()
      val programs  = Programs.make(resources, attentionInterval = 5.minutes)
      val endpoints =
        List(HealthApi.serverEndpoint(version)) ++
          JournalApi.serverEndpoints(using programs.plantJournal, programs.plantAttentionProjection) ++
          List(StaticSite.endpoint(staticDir))
      start(
        http = () =>
          val _ = NettySyncServer().host(host).port(port).addEndpoints(endpoints).startAndWait()
        ,
        plantAttentionRefresher = programs.plantAttentionRefresher
      ).orThrow

  private[app] def start(
      http: () => Unit,
      plantAttentionRefresher: PeriodicAttentionRefresher
  ): Either[Throwable, Unit] =
    plantAttentionRefresher.refreshAttention().flatMap: _ =>
      supervisedError(EitherMode[Throwable]()):
        val _ = forkError(plantAttentionRefresher.refreshPeriodically())
        Right(http())
