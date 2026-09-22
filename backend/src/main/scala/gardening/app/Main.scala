package gardening.app

import cats.syntax.either.*
import gardening.adapters.http.{HealthApi, JournalApi, StaticSite}
import gardening.adapters.persistence.SqliteLocation
import gardening.domain.attention.{PlantAttentionService, RefreshAttentionResult}
import org.flywaydb.core.Flyway
import ox.{EitherMode, forkError, sleep, supervisedError}
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
      val _ = Flyway.configure().dataSource(resources.dataSource).load().migrate()
      Programs
        .make(resources)
        .flatMap: programs =>
          val endpoints =
            List(HealthApi.serverEndpoint(version)) ++
              JournalApi.serverEndpoints(using programs.plantJournal, programs.plantAttentionService) ++
              List(StaticSite.endpoint(staticDir))
          start(
            http = () =>
              val _ = NettySyncServer().host(host).port(port).addEndpoints(endpoints).startAndWait()
            ,
            plantAttentionService = programs.plantAttentionService,
            awaitNext = () =>
              sleep(5.minutes)
              ().asRight
          )
        .orThrow

  private[app] def start(
      http: () => Unit,
      plantAttentionService: PlantAttentionService,
      awaitNext: () => Either[Throwable, Unit]
  ) =
    supervisedError(EitherMode[Throwable]()):
      val _ = forkError(pollPlantAttention(plantAttentionService, awaitNext))
      http().asRight

  private def pollPlantAttention(
      plantAttentionService: PlantAttentionService,
      awaitNext: () => Either[Throwable, Unit]
  ) =
    Iterator
      .continually(awaitNext().flatMap(_ => refreshPlantAttention(plantAttentionService)))
      .collectFirst { case failure @ Left(_) => failure }
      .fold[Either[Throwable, Unit]](().asRight)(identity)

  private def refreshPlantAttention(plantAttentionService: PlantAttentionService) =
    plantAttentionService.refreshAll match
      case RefreshAttentionResult.Refreshed(_)          => ().asRight
      case RefreshAttentionResult.RefreshFailed(reason) => reason.asLeft
