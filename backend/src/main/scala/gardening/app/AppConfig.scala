package gardening.app

import gardening.usecases.PlantAttentionMonitor
import io.github.iltotore.iron.pureconfig.given
import pureconfig.*
import pureconfig.error.FailureReason
import pureconfig.module.squants.given
import squants.information.Information

import java.nio.file.Path
import scala.concurrent.duration.{Duration, FiniteDuration}

final case class ServerConfig(host: String, port: Int, staticDir: String) derives ConfigReader

final case class StorageConfig(dbPath: String, lockTimeout: FiniteDuration, photosDir: Path) derives ConfigReader

given ConfigReader[PlantAttentionMonitor.Settings] = ConfigReader.derived

final case class AttentionConfig(feedStalenessThreshold: FiniteDuration, recomputeInterval: FiniteDuration, watering: PlantAttentionMonitor.Settings)
    derives ConfigReader

final case class PhotoConfig(maxUploadSize: Information, maxThumbnailSize: Information) derives ConfigReader

final case class AppConfig(server: ServerConfig, storage: StorageConfig, attention: AttentionConfig, photo: PhotoConfig)

object AppConfig:

  final private case class InvalidConfig(description: String) extends FailureReason

  given ConfigReader[AppConfig] = ConfigReader.derived[AppConfig].emap: config =>
    // Cross-field and positivity rules the refined types can't express; a failure names field and value.
    val watering = config.attention.watering
    val min      = watering.minSampleCount
    val size     = watering.historySize
    for
      _ <- check(min >= 2, s"gardening.attention.watering.min-sample-count ($min) must be >= 2")
      _ <- check(min <= size, s"gardening.attention.watering.history-size ($size) must be >= min-sample-count ($min)")
      _ <- checkPositive("gardening.attention.watering.overdue-grace-period", watering.overdueGracePeriod)
      _ <- checkPositive("gardening.attention.recompute-interval", config.attention.recomputeInterval)
      _ <- checkPositive("gardening.photo.max-upload-size", config.photo.maxUploadSize)
      _ <- checkPositive("gardening.photo.max-thumbnail-size", config.photo.maxThumbnailSize)
    yield config

  def load(): AppConfig = ConfigSource.default.at("gardening").loadOrThrow[AppConfig]

  private def check(condition: Boolean, description: => String): Either[FailureReason, Unit] =
    Either.cond(condition, (), InvalidConfig(description))

  private def checkPositive(field: String, value: FiniteDuration): Either[FailureReason, Unit] =
    check(value > Duration.Zero, s"$field ($value) must be greater than zero")

  private def checkPositive(field: String, value: Information): Either[FailureReason, Unit] =
    check(value.value > 0, s"$field ($value) must be greater than zero")
