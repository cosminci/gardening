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

final case class ServerConfig(host: String, port: Int, staticDir: String) derives ConfigReader

final case class StorageConfig(dbPath: String, lockTimeout: FiniteDuration, photosDir: Path) derives ConfigReader

final case class WateringConfig(
    minSampleCount: Int :| GreaterEqual[2],
    maxSampleCount: Int :| GreaterEqual[2],
    overdueGracePeriod: FiniteDuration
) derives ConfigReader

final case class AttentionConfig(feedStalenessThreshold: FiniteDuration, recomputeInterval: FiniteDuration, watering: WateringConfig)
    derives ConfigReader

final case class PhotoConfig(maxUploadSize: Information, maxThumbnailSize: Information) derives ConfigReader

final case class AppConfig(server: ServerConfig, storage: StorageConfig, attention: AttentionConfig, photo: PhotoConfig)

object AppConfig:

  final private case class InvalidConfig(description: String) extends FailureReason

  given ConfigReader[AppConfig] = ConfigReader.derived[AppConfig].emap: config =>
    // Cross-field and positivity rules the refined types can't express; a failure names field and value.
    val watering = config.attention.watering
    val photo    = config.photo
    for
      _ <- check(
        watering.minSampleCount <= watering.maxSampleCount,
        s"gardening.attention.watering.max-sample-count (${watering.maxSampleCount}) must be >= min-sample-count (${watering.minSampleCount})"
      )
      _ <- checkPositive("gardening.attention.watering.overdue-grace-period", watering.overdueGracePeriod)
      _ <- checkPositive("gardening.attention.recompute-interval", config.attention.recomputeInterval)
      _ <- checkPositive("gardening.photo.max-upload-size", photo.maxUploadSize)
      _ <- checkPositive("gardening.photo.max-thumbnail-size", photo.maxThumbnailSize)
    yield config

  def load(): AppConfig = ConfigSource.default.at("gardening").loadOrThrow[AppConfig]

  private def check(condition: Boolean, description: => String): Either[FailureReason, Unit] =
    Either.cond(condition, (), InvalidConfig(description))

  private def checkPositive(field: String, value: FiniteDuration): Either[FailureReason, Unit] =
    check(value > Duration.Zero, s"$field ($value) must be greater than zero")

  private def checkPositive(field: String, value: Information): Either[FailureReason, Unit] =
    check(value.value > 0, s"$field ($value) must be greater than zero")
