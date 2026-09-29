package gardening.adapters.sqlite

import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.plants.*
import gardening.ports.PhotoWriteJournal

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID
import scala.util.Try

object SqlitePhotoWriteJournal:

  private val timestampFormatter = DateTimeFormatterBuilder().appendInstant(9).toFormatter

  def make(transactor: Transactor): PhotoWriteJournal = LiveSqlitePhotoWriteJournal(transactor)

  private class LiveSqlitePhotoWriteJournal(transactor: Transactor) extends PhotoWriteJournal:

    override def recordAdd(idempotencyKey: String, plant: PlantId, capturedAt: Instant): PhotoJournalWriteResult =
      try
        transact(transactor):
          sql"""insert into photo_write_intent (key, operation, status, plant_id, captured_at)
                values ($idempotencyKey, 'Add', 'Pending', ${plant.value}, ${timestampFormatter.format(capturedAt)})""".update.run()
        PhotoJournalWriteResult.Recorded
      catch case error: SqlException => PhotoJournalWriteResult.RecordFailed(error)

    override def recordRemove(photo: PhotoId): PhotoJournalWriteResult =
      try
        transact(transactor):
          val key = photo.value.toString
          sql"""insert into photo_write_intent (key, operation, status, photo_id)
                values ($key, 'Remove', 'Pending', $key)""".update.run()
        PhotoJournalWriteResult.Recorded
      catch case error: SqlException => PhotoJournalWriteResult.RecordFailed(error)

    override def attachPhoto(idempotencyKey: String, photo: PhotoId): PhotoJournalWriteResult =
      try
        transact(transactor):
          sql"update photo_write_intent set photo_id = ${photo.value.toString} where key = $idempotencyKey".update.run()
        PhotoJournalWriteResult.Recorded
      catch case error: SqlException => PhotoJournalWriteResult.RecordFailed(error)

    override def markDone(key: String): PhotoJournalWriteResult =
      try
        transact(transactor):
          sql"update photo_write_intent set status = 'Done' where key = $key".update.run()
        PhotoJournalWriteResult.Recorded
      catch case error: SqlException => PhotoJournalWriteResult.RecordFailed(error)

    override def discard(key: String): PhotoJournalWriteResult =
      try
        transact(transactor):
          sql"delete from photo_write_intent where key = $key".update.run()
        PhotoJournalWriteResult.Recorded
      catch case error: SqlException => PhotoJournalWriteResult.RecordFailed(error)

    override def findByKey(key: String): PhotoJournalFindResult =
      try
        val row = connect(transactor):
          sql"select key, operation, status, photo_id, plant_id, captured_at from photo_write_intent where key = $key"
            .query[PhotoWriteIntentRow].run().headOption
        row match
          case None      => PhotoJournalFindResult.NotFound
          case Some(row) => PhotoJournalFindResult.Found(trust(toIntent(row)))
      catch
        case error: SqlException       => PhotoJournalFindResult.FindFailed(error)
        case error: DatabaseCorruption => PhotoJournalFindResult.FindFailed(error)

    override def list(): PhotoJournalListResult =
      try
        val rows = connect(transactor):
          sql"select key, operation, status, photo_id, plant_id, captured_at from photo_write_intent".query[PhotoWriteIntentRow].run()
        PhotoJournalListResult.Listed(trust(rows.traverse(toIntent)))
      catch
        case error: SqlException       => PhotoJournalListResult.ListFailed(error)
        case error: DatabaseCorruption => PhotoJournalListResult.ListFailed(error)

    private def toIntent(row: PhotoWriteIntentRow): Either[Throwable, PhotoWriteIntent] =
      for
        operation <- row.operation match
          case "Add"    => Right(PhotoWriteOperation.Add)
          case "Remove" => Right(PhotoWriteOperation.Remove)
          // A CHECK constraint on this column makes every other stored value unreachable.
          // $COVERAGE-OFF$
          case other => Left(RuntimeException(s"invalid stored photo write intent operation: $other"))
          // $COVERAGE-ON$
        status <- row.status match
          case "Pending" => Right(PhotoWriteIntentStatus.Pending)
          case "Done"    => Right(PhotoWriteIntentStatus.Done)
          // A CHECK constraint on this column makes every other stored value unreachable.
          // $COVERAGE-OFF$
          case other => Left(RuntimeException(s"invalid stored photo write intent status: $other"))
          // $COVERAGE-ON$
        photoId <- row.photoId.traverse(value =>
          Try(UUID.fromString(value)).toEither.left.map(_ => RuntimeException(s"invalid stored photo write intent photo id: $value"))
        )
        capturedAt <- row.capturedAt.traverse(value =>
          Try(Instant.parse(value)).toEither.left.map(_ => RuntimeException(s"invalid stored photo write intent capturedAt: $value"))
        )
      yield PhotoWriteIntent(row.key, operation, status, photoId.map(PhotoId.apply), row.plantId.map(PlantId.apply), capturedAt)

  private case class PhotoWriteIntentRow(
      key: String,
      operation: String,
      status: String,
      photoId: Option[String],
      plantId: Option[String],
      capturedAt: Option[String]
  ) derives DbCodec
