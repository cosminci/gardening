package gardening.adapters.sqlite

import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.plants.*
import gardening.ports.PhotoStore
import org.sqlite.{SQLiteErrorCode, SQLiteException}

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID
import scala.util.Try

object SqlitePhotoStore:

  private val timestampFormatter = DateTimeFormatterBuilder().appendInstant(9).toFormatter

  def make(transactor: Transactor): PhotoStore = LiveSqlitePhotoStore(transactor)

  private class LiveSqlitePhotoStore(transactor: Transactor) extends PhotoStore:

    override def addPhoto(photo: PlantPhoto): AddPhotoResult =
      try
        transact(transactor):
          sql"""insert into plant_photo (id, plant_id, captured_at)
                values (${photo.id.value.toString}, ${photo.plantId.value}, ${timestampFormatter.format(photo.capturedAt)})""".update.run()
        AddPhotoResult.Added(photo)
      catch
        case error: SqlException =>
          error.getCause match
            case sqlite: SQLiteException if sqlite.getResultCode.equals(SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY) => AddPhotoResult.PlantMissing
            case _ => AddPhotoResult.AddFailed(error)

    override def removePhoto(photo: PhotoId): RemovePhotoResult =
      try
        transact(transactor):
          val query = sql"delete from plant_photo where id = ${photo.value.toString} returning id, plant_id, captured_at"
          query.query[PlantPhotoRow].run().headOption match
            case None      => RemovePhotoResult.PhotoMissing
            case Some(row) => RemovePhotoResult.Removed(trust(toPhoto(row)))
      catch
        case error: SqlException       => RemovePhotoResult.RemoveFailed(error)
        case error: DatabaseCorruption => RemovePhotoResult.RemoveFailed(error)

    override def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult =
      try
        val readSize = window.size + 1
        val offset   = window.offset
        val rows     = connect(transactor):
          sql"""select id, plant_id, captured_at from plant_photo
                where plant_id = ${plant.value}
                order by captured_at desc, id desc
                limit $readSize offset $offset""".query[PlantPhotoRow].run()
        val photos = trust(rows.traverse(toPhoto))
        GetPhotosResult.Read(PhotoPage(photos.take(window.size), photos.size > window.size))
      catch
        case error: SqlException       => GetPhotosResult.ReadFailed(error)
        case error: DatabaseCorruption => GetPhotosResult.ReadFailed(error)

    private def toPhoto(row: PlantPhotoRow): Either[Throwable, PlantPhoto] =
      for
        ts <- Try(Instant.parse(row.capturedAt)).toEither.left.map(_ => RuntimeException(s"invalid stored photo capturedAt: ${row.capturedAt}"))
        id <- Try(UUID.fromString(row.id)).toEither.left.map(_ => RuntimeException(s"invalid stored photo id: ${row.id}"))
      yield PlantPhoto(PhotoId(id), PlantId(row.plantId), ts)

  private case class PlantPhotoRow(id: String, plantId: String, capturedAt: String) derives DbCodec
