package gardening.adapters.sqlite

import gardening.domain.*
import gardening.domain.plants.*
import gardening.ports.PhotoWriteJournal
import com.augustnagro.magnum.Transactor
import munit.FunSuite
import org.flywaydb.core.Flyway

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using

class SqlitePhotoWriteJournalSeamIntegrationTest extends FunSuite:

  private val date    = Instant.parse("2026-01-01T00:00:00Z")
  private val plantId = PlantId("p1")
  private val photoId = PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000001"))
  private val key     = "idem-1"

  test("should record an in-flight add, attach its photo id, mark it done, then discard it"):
    Using.resource(journalResource): resource =>
      val journal = resource.journal

      assertEquals(journal.recordAdd(key, plantId, date), PhotoJournalWriteResult.Recorded)
      val afterRecord = journal.findByKey(key)
      assertEquals(
        afterRecord,
        PhotoJournalFindResult.Found(PhotoWriteIntent(key, PhotoWriteOperation.Add, PhotoWriteIntentStatus.Pending, None, Some(plantId), Some(date)))
      )

      assertEquals(journal.attachPhoto(key, photoId), PhotoJournalWriteResult.Recorded)
      val afterAttach = journal.findByKey(key)
      assertEquals(
        afterAttach,
        PhotoJournalFindResult.Found(PhotoWriteIntent(
          key,
          PhotoWriteOperation.Add,
          PhotoWriteIntentStatus.Pending,
          Some(photoId),
          Some(plantId),
          Some(date)
        ))
      )

      assertEquals(journal.markDone(key), PhotoJournalWriteResult.Recorded)
      val afterDone = journal.findByKey(key)
      assertEquals(
        afterDone,
        PhotoJournalFindResult.Found(PhotoWriteIntent(
          key,
          PhotoWriteOperation.Add,
          PhotoWriteIntentStatus.Done,
          Some(photoId),
          Some(plantId),
          Some(date)
        ))
      )

      assertEquals(journal.discard(key), PhotoJournalWriteResult.Recorded)
      assertEquals(journal.findByKey(key), PhotoJournalFindResult.NotFound)

  test("should record an in-flight remove keyed by the photo id itself"):
    Using.resource(journalResource): resource =>
      val journal        = resource.journal
      val removeKey      = photoId.value.toString
      val expectedIntent = PhotoWriteIntent(removeKey, PhotoWriteOperation.Remove, PhotoWriteIntentStatus.Pending, Some(photoId), None, None)

      assertEquals(journal.recordRemove(photoId), PhotoJournalWriteResult.Recorded)

      assertEquals(journal.findByKey(removeKey), PhotoJournalFindResult.Found(expectedIntent))

  test("should return NotFound for an unknown key"):
    Using.resource(journalResource): resource =>
      assertEquals(resource.journal.findByKey("no-such-key"), PhotoJournalFindResult.NotFound)

  test("should list every recorded intent"):
    Using.resource(journalResource): resource =>
      val journal     = resource.journal
      val secondPlant = PhotoId(UUID.fromString("00000000-0000-4000-8002-000000000002"))

      assertEquals(journal.recordAdd(key, plantId, date), PhotoJournalWriteResult.Recorded)
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

      readOnlyJournal.recordAdd(key, plantId, date) match
        case PhotoJournalWriteResult.RecordFailed(_) => ()
        case other                                   => fail(s"expected RecordFailed, got $other")
      readOnlyJournal.recordRemove(photoId) match
        case PhotoJournalWriteResult.RecordFailed(_) => ()
        case other                                   => fail(s"expected RecordFailed, got $other")
      readOnlyJournal.attachPhoto(key, photoId) match
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
      execute(resource.dataSource, "insert into photo_write_intent (key, operation, photo_id) values (?, ?, ?)", key, "Add", invalidPhotoId)

      resource.journal.findByKey(key) match
        case PhotoJournalFindResult.FindFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, s"invalid stored photo write intent photo id: $invalidPhotoId")
        case other => fail(s"expected FindFailed(DatabaseCorruption), got $other")

  test("should report a corrupt stored photo id as a list failure"):
    Using.resource(journalResource): resource =>
      val invalidPhotoId = "z" * 36
      execute(resource.dataSource, "insert into photo_write_intent (key, operation, photo_id) values (?, ?, ?)", key, "Add", invalidPhotoId)

      resource.journal.list() match
        case PhotoJournalListResult.ListFailed(DatabaseCorruption(reason)) =>
          assertEquals(reason.getMessage, s"invalid stored photo write intent photo id: $invalidPhotoId")
        case other => fail(s"expected ListFailed(DatabaseCorruption), got $other")

  test("should report a corrupt stored capturedAt as a read failure"):
    Using.resource(journalResource): resource =>
      assertEquals(resource.journal.recordAdd(key, plantId, date), PhotoJournalWriteResult.Recorded)
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

  private def makeReadOnly(connection: Connection) =
    val statement = connection.createStatement()
    val _         = statement.execute("PRAGMA query_only = ON")
    statement.close()

  private def execute(dataSource: DataSource, sql: String, parameters: String*) =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement(sql)
      try
        parameters.zipWithIndex.foreach((parameter, index) => statement.setString(index + 1, parameter))
        val _ = statement.executeUpdate()
      finally statement.close()
    finally connection.close()
