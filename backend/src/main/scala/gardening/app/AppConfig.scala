package gardening.app

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import io.github.iltotore.iron.pureconfig.given
import _root_.pureconfig.*
import _root_.pureconfig.error.FailureReason
import _root_.pureconfig.module.squants.given
import squants.information.Information

import java.nio.file.Path
import scala.concurrent.duration.{Duration, FiniteDuration}

final case class WateringConfig(
    minSampleCount: Int :| GreaterEqual[2],
    maxSampleCount: Int :| GreaterEqual[2],
    overdueGracePeriod: FiniteDuration
) derives ConfigReader

final case class PhotoConfig(maxUploadSize: Information, maxThumbnailSize: Information) derives ConfigReader

final case class AppConfig(
    host: String,
    port: Int,
    dbPath: String,
    photosDir: Path,
    staticDir: String,
    storageLockTimeout: FiniteDuration,
    attentionFeedStalenessThreshold: FiniteDuration,
    attentionRecomputeInterval: FiniteDuration,
    watering: WateringConfig,
    photo: PhotoConfig
)

object AppConfig:

  final private case class InvalidConfig(description: String) extends FailureReason

  // Every semantic bound the type system can't express (cross-field ordering, positivity of
  // durations and sizes) is checked here, once, so an out-of-bound override fails startup naming
  // the field and its rejected value rather than surfacing later as wrong behaviour.
  given ConfigReader[AppConfig] = ConfigReader.derived[AppConfig].emap: config =>
    val watering = config.watering
    for
      _ <- check(
        watering.minSampleCount <= watering.maxSampleCount,
        s"gardening.watering.max-sample-count (${watering.maxSampleCount}) must be greater than or equal to min-sample-count (${watering.minSampleCount})"
      )
      _ <- check(watering.overdueGracePeriod > Duration.Zero, positive("gardening.watering.overdue-grace-period", watering.overdueGracePeriod))
      _ <- check(
        config.attentionRecomputeInterval > Duration.Zero,
        positive("gardening.attention-recompute-interval", config.attentionRecomputeInterval)
      )
      _ <- check(config.photo.maxUploadSize.value > 0, positive("gardening.photo.max-upload-size", config.photo.maxUploadSize))
      _ <- check(config.photo.maxThumbnailSize.value > 0, positive("gardening.photo.max-thumbnail-size", config.photo.maxThumbnailSize))
    yield config

  def load(): AppConfig = ConfigSource.default.at("gardening").loadOrThrow[AppConfig]

  private def check(condition: Boolean, description: => String): Either[FailureReason, Unit] =
    Either.cond(condition, (), InvalidConfig(description))

  private def positive(field: String, value: Any): String = s"$field ($value) must be greater than zero"
