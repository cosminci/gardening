package gardening.ports

import gardening.domain.PlantId
import gardening.domain.plants.*

import java.time.Instant

trait PhotoWriteJournal:
  def recordAdd(idempotencyKey: String, plant: PlantId, capturedAt: Instant): PhotoJournalWriteResult
  def recordRemove(photo: PhotoId): PhotoJournalWriteResult
  def attachPhoto(idempotencyKey: String, photo: PhotoId): PhotoJournalWriteResult
  def markDone(key: String): PhotoJournalWriteResult
  def discard(key: String): PhotoJournalWriteResult
  def findByKey(key: String): PhotoJournalFindResult
  def list(): PhotoJournalListResult
