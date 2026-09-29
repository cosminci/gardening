package gardening.usecases

import gardening.domain.*
import gardening.domain.plants.*
import gardening.ports.{PhotoStore, PhotoContentStore, PhotoWriteJournal}
import gardening.capabilities.Logger

import language.experimental.captureChecking

// Reconciles the durable photo-write journal against real store/content state. Runs once, at
// startup only, before the server accepts requests - a live process's own failures are already
// handled immediately by PhotoManager's in-process compensation.
trait PhotoWriteRecovery:
  def reconcile(): Unit

object PhotoWriteRecovery:

  def make(using
      store: PhotoStore^,
      contentStore: PhotoContentStore^,
      journal: PhotoWriteJournal^
  )(using log: Logger^): PhotoWriteRecovery^{store, contentStore, journal, log} =
    new LivePhotoWriteRecovery

  private class LivePhotoWriteRecovery(using
      store: PhotoStore^,
      contentStore: PhotoContentStore^,
      journal: PhotoWriteJournal^
  )(using log: Logger^) extends PhotoWriteRecovery:

    override def reconcile(): Unit =
      journal.list() match
        case PhotoJournalListResult.ListFailed(reason) => log.error("reconcile photo write journal", reason)
        case PhotoJournalListResult.Listed(intents)    => intents.foreach(reconcileIntent)

    private def reconcileIntent(intent: PhotoWriteIntent): Unit = intent.operation match
      case PhotoWriteOperation.Add    => reconcileAdd(intent)
      case PhotoWriteOperation.Remove => reconcileRemove(intent)

    // A Done intent already had its content and metadata both written, either by a prior live
    // completion or a prior reconciliation pass; surviving until this new startup means any
    // client retry has had a full process lifetime to arrive and be deduplicated - safe to purge.
    private def reconcileAdd(intent: PhotoWriteIntent): Unit = intent.status match
      case PhotoWriteIntentStatus.Done    => discard(intent.key)
      case PhotoWriteIntentStatus.Pending =>
        intent.photoId match
          case None          => discard(intent.key)
          case Some(photoId) =>
            (contentStore.get(photoId, PhotoVariant.Original), contentStore.get(photoId, PhotoVariant.Thumbnail)) match
              case (PhotoReadResult.Read(_), PhotoReadResult.Read(_)) => finishAdd(intent, photoId)
              case _                                                  => discardPartialContent(intent.key, photoId)

    private def finishAdd(intent: PhotoWriteIntent, photoId: PhotoId): Unit =
      (intent.plantId, intent.capturedAt) match
        case (Some(plantId), Some(capturedAt)) =>
          store.addPhoto(PlantPhoto(photoId, plantId, capturedAt)) match
            case AddPhotoResult.Added(_) =>
              journal.markDone(intent.key) match
                case PhotoJournalWriteResult.Recorded             => ()
                case PhotoJournalWriteResult.RecordFailed(reason) => log.error("mark photo write intent done", reason)
            case AddPhotoResult.PlantMissing | AddPhotoResult.AddFailed(_) => discardPartialContent(intent.key, photoId)
        case _ =>
          log.error(s"photo write intent ${intent.key} is missing its plant or capturedAt")
          discardPartialContent(intent.key, photoId)

    // Metadata delete runs before content delete in the live path, so PhotoMissing here means the
    // delete already fully landed on a previous run; either way, calling it again is exactly what
    // finishes an interrupted removal, and is a no-op if it already completed.
    private def reconcileRemove(intent: PhotoWriteIntent): Unit =
      intent.photoId match
        case None          => discard(intent.key)
        case Some(photoId) =>
          store.removePhoto(photoId) match
            case RemovePhotoResult.Removed(_) | RemovePhotoResult.PhotoMissing =>
              contentStore.delete(photoId) match
                case PhotoWriteResult.Written             => discard(intent.key)
                case PhotoWriteResult.WriteFailed(reason) => log.error("recover photo removal content cleanup", reason)
            case RemovePhotoResult.RemoveFailed(reason) => log.error("recover photo removal", reason)

    private def discardPartialContent(key: String, photoId: PhotoId): Unit =
      contentStore.delete(photoId) match
        case PhotoWriteResult.Written             => discard(key)
        case PhotoWriteResult.WriteFailed(reason) => log.error("recover photo write intent content cleanup", reason)

    private def discard(key: String): Unit =
      journal.discard(key) match
        case PhotoJournalWriteResult.Recorded             => ()
        case PhotoJournalWriteResult.RecordFailed(reason) => log.error("discard photo write intent", reason)
