package gardening.app

import gardening.adapters.file.FilePhotoContentStore
import gardening.adapters.sqlite.{SqlitePhotoStore, SqlitePlantStore, SqliteLocation}
import gardening.domain.plants.{PhotoBackfillResult, PhotoThumbnail}
import gardening.capabilities.Logger
import gardening.usecases.PhotoThumbnailBackfill
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

import java.nio.file.Paths
import scala.util.Using

object BackfillPhotoThumbnails:

  def main(args: Array[String]): Unit =
    val dbPath    = sys.env.getOrElse("GARDENING_DB_PATH", "gardening.db")
    val photosDir = Paths.get(sys.env.getOrElse("GARDENING_PHOTOS_DIR", "photos"))

    given log: Logger = new Logger:
      private val underlying           = LoggerFactory.getLogger("gardening-backfill")
      def info(message: String): Unit  = underlying.info(message)
      def error(message: String): Unit = underlying.error(message)

    val result = Using.resource(AppResources.acquire(SqliteLocation.File(dbPath))): resources =>
      val _            = Flyway.configure().dataSource(resources.dataSource).load().migrate()
      val plantStore   = SqlitePlantStore.make(resources.transactor)
      val photoStore   = SqlitePhotoStore.make(resources.transactor)
      val contentStore = FilePhotoContentStore.make(photosDir)
      PhotoThumbnailBackfill.make(using plantStore, photoStore, contentStore, PhotoThumbnail.make).run()

    result match
      case PhotoBackfillResult.BackfillFailed(_) => sys.exit(1)
      case PhotoBackfillResult.Completed(_, _)   => ()
