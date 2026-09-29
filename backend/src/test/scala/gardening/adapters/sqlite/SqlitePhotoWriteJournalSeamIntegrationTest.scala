package gardening.adapters.sqlite

import gardening.domain.*
import gardening.domain.plants.*
import gardening.ports.PhotoWriteJournal
import gardening.adapters.sqlite.SqliteHelpers.{execute, makeReadOnly}
import com.augustnagro.magnum.Transactor
import munit.FunSuite
import org.flywaydb.core.Flyway

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqlitePhotoWriteJournalSeamIntegrationTest extends FunSuite:

  private val date    = Instant.parse("2026-01-01T00:00:00Z")
  private val plantId = PlantId("p1")
  private val photoId = PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000001"))
  private val key     = "idem-1"

  test("should record an in-flight add, mark it done, then discard it"):
    Using.resource(journalResource): resource =>
      val journal = resource.journal

      assertEquals(journal.recordAdd(key, plantId, date, photoId), PhotoJournalWriteResult.Recorded)
      val expectedAfterRecord = PhotoJournalFindResult.Found(PhotoWriteIntent.Add(key, PhotoWriteIntentStatus.Pending, plantId, date, photoId))
      assertEquals(journal.findByKey(key), expectedAfterRecord)

      assertEquals(journal.markDone(key), PhotoJournalWriteResult.Recorded)
      val expectedAfterDone = PhotoJournalFindResult.Found(PhotoWriteIntent.Add(key, PhotoWriteIntentStatus.Done, plantId, date, photoId))
      assertEquals(journal.findByKey(key), expectedAfterDone)

      assertEquals(journal.discard(key), PhotoJournalWriteResult.Recorded)
      assertEquals(journal.findByKey(key), PhotoJournalFindResult.NotFound)

  test("should record an in-flight remove keyed by the photo id itself"):
    Using.resource(journalResource): resource =>
      val journal        = resource.journal
      val removeKey      = photoId.value.toString
      val expectedIntent = PhotoWriteIntent.Remove(removeKey, PhotoWriteIntentStatus.Pending, photoId)

      assertEquals(journal.recordRemove(photoId), PhotoJournalWriteResult.Recorded)
      assertEquals(journal.findByKey(removeKey), PhotoJournalFindResult.Found(expectedIntent))

  test("should return NotFound for an unknown key"):
    Using.resource(journalResource): resource =>
      assertEquals(resource.journal.findByKey("no-such-key"), PhotoJournalFindResult.NotFound)

  test("should list every recorded intent"):
    Using.resource(journalResource): resource =>
      val journal     = resource.journal
      val secondPlant = PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000002"))

      assertEquals(journal.recordAdd(key, plantId, date, photoId), PhotoJournalWriteResult.Recorded)
      assertEquals(journal.recordRemove(secondPlant), PhotoJournalWriteResult.Recorded)

      journal.list() match
        case PhotoJournalListResult.Listed(intents) => assertEquals(intents.map(_.key).toSet, Set(key, secondPlant.value.toString))
        case other                                  => fail(s"expected Listed, got $other")

  test("should return an empty list when nothing is recorded"):
    Using.resource(journalResource): resource =>
      assertEquals(resource.journal.list(), PhotoJournalListResult.Listed(Vector.empty))

  test("should report a write failure when the database is read-only"):
    Using.resource(journalResource): resource =>
      val readOnlyJournal = SqlitePhotoWriteJournal.make(Transactor(resource.dataSource, connectionConfig = makeReadOnly))

      readOnlyJournal.recordAdd(key, plantId, date, photoId) match
        case PhotoJournalWriteResult.RecordFailed(_) => ()
        case other                                   => fail(s"expected RecordFailed, got $other")
      readOnlyJournal.recordRemove(photoId) match
        case PhotoJournalWriteResult.RecordFailed(_) => ()
        case other                                   => fail(s"expected RecordFailed, got $other")
      readOnlyJournal.markDone(key) match
        case PhotoJournalWriteResult.RecordFailed(_) => ()
        case other                                   => fail(s"expected RecordFailed, got $other")
      readOnlyJournal.discard(key) match
        case PhotoJournalWriteResult.RecordFailed(_) => ()
        case other                                   => fail(s"expected RecordFailed, got $other")

  test("should report a read failure when the schema is unavailable"):
    Using.resource(Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))): connection =>
      val journal = SqlitePhotoWriteJournal.make(connection.transactor)
      journal.findByKey(key) match
        case PhotoJournalFindResult.FindFailed(_) => ()
        case other                                => fail(s"expected FindFailed, got $other")
      journal.list() match
        case PhotoJournalListResult.ListFailed(_) => ()
        case other                                => fail(s"expected ListFailed, got $other")

  test("should report a corrupt stored photo id as a read failure"):
    Using.resource(journalResource): resource =>
      val invalidPhotoId = "z" * 36
      val command        = "insert into photo_write_intent (key, operation, plant_id, captured_at, photo_id) values (?, ?, ?, ?, ?)"
      execute(resource.dataSource, command, key, "Add", plantId.value, date.toString, invalidPhotoId)

      resource.journal.findByKey(key) match
        case PhotoJournalFindResult.FindFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, s"invalid stored photo write intent photo id: $invalidPhotoId")
        case other => fail(s"expected FindFailed(DatabaseCorruption), got $other")

  test("should report a corrupt stored photo id as a list failure"):
    Using.resource(journalResource): resource =>
      val invalidPhotoId = "z" * 36
      val command        = "insert into photo_write_intent (key, operation, plant_id, captured_at, photo_id) values (?, ?, ?, ?, ?)"
      execute(resource.dataSource, command, key, "Add", plantId.value, date.toString, invalidPhotoId)

      resource.journal.list() match
        case PhotoJournalListResult.ListFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, s"invalid stored photo write intent photo id: $invalidPhotoId")
        case other => fail(s"expected ListFailed(DatabaseCorruption), got $other")

  test("should report an add photo write intent missing its plant_id as a read failure"):
    Using.resource(journalResource): resource =>
      val command = "insert into photo_write_intent (key, operation, captured_at, photo_id) values (?, ?, ?, ?)"
      execute(resource.dataSource, command, key, "Add", date.toString, photoId.value.toString)

      resource.journal.findByKey(key) match
        case PhotoJournalFindResult.FindFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, s"add photo write intent $key is missing plant_id")
        case other => fail(s"expected FindFailed(DatabaseCorruption), got $other")

  test("should report a corrupt stored capturedAt as a read failure"):
    Using.resource(journalResource): resource =>
      assertEquals(resource.journal.recordAdd(key, plantId, date, photoId), PhotoJournalWriteResult.Recorded)
      execute(resource.dataSource, "update photo_write_intent set captured_at = ? where key = ?", "not-an-instant", key)

      resource.journal.findByKey(key) match
        case PhotoJournalFindResult.FindFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, "invalid stored photo write intent capturedAt: not-an-instant")
        case other => fail(s"expected FindFailed(DatabaseCorruption), got $other")

  final private case class JournalResource(connection: SqliteConnection, dataSource: DataSource, journal: PhotoWriteJournal) extends AutoCloseable:
    override def close(): Unit = connection.close()

  private def journalResource =
    val connection = Sqlite.make.connect(SqliteLocation.InMemory(UUID.randomUUID().toString))
    val _          = Flyway.configure().dataSource(connection.dataSource).load().migrate()
    JournalResource(connection, connection.dataSource, SqlitePhotoWriteJournal.make(connection.transactor))
