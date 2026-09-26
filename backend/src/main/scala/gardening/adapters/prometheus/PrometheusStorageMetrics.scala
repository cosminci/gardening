package gardening.adapters.prometheus

import io.prometheus.metrics.core.metrics.GaugeWithCallback
import io.prometheus.metrics.model.registry.PrometheusRegistry
import ox.discard

import java.nio.file.{Files, Path}
import scala.util.{Try, Using}

object PrometheusStorageMetrics:

  def register(registry: PrometheusRegistry, dbPath: Path, photosDir: Path): Unit =
    GaugeWithCallback
      .builder()
      .name("gardening_storage_db_bytes")
      .help("SQLite journal database file size on disk")
      .callback(cb => cb.call(Try(Files.size(dbPath).toDouble).getOrElse(0.0)))
      .register(registry)
      .discard
    GaugeWithCallback
      .builder()
      .name("gardening_storage_photos_bytes")
      .help("Photo content storage size on disk")
      .callback(cb => cb.call(directorySize(photosDir)))
      .register(registry)
      .discard

  private def directorySize(dir: Path): Double =
    if !Files.exists(dir) then 0.0
    else Using.resource(Files.walk(dir))(_.filter(Files.isRegularFile(_)).mapToLong(Files.size).sum().toDouble)
