package gardening.app

import gardening.domain.attention.{PlantAttentionRefresh, RefreshAttentionResult}
import ox.sleep

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

final class PeriodicAttentionRefresher private[app] (
    attention: PlantAttentionRefresh,
    awaitNext: () => Either[Throwable, Unit]
):

  def refreshAttention(): Either[Throwable, Unit] =
    attention.refreshAll match
      case RefreshAttentionResult.Refreshed(_)          => Right(())
      case RefreshAttentionResult.RefreshFailed(reason) => Left(reason)

  def refreshPeriodically(): Either[Throwable, Unit] =
    @tailrec
    def loop(): Either[Throwable, Unit] =
      awaitNext().flatMap(_ => refreshAttention()) match
        case failure @ Left(_) => failure
        case Right(_)          => loop()
    loop()

object PeriodicAttentionRefresher:

  def every(attention: PlantAttentionRefresh, interval: FiniteDuration): PeriodicAttentionRefresher =
    PeriodicAttentionRefresher(
      attention,
      () =>
        sleep(interval)
        Right(())
    )
