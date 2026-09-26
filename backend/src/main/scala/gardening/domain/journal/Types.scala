package gardening.domain.journal

import gardening.domain.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*
import java.time.Instant
import java.util.UUID
import scodec.bits.ByteVector

opaque type PhotoId = UUID
object PhotoId:
  def apply(value: UUID): PhotoId         = value
  extension (id: PhotoId) def value: UUID = id

final case class PlantPhoto(id: PhotoId, plantId: PlantId, capturedAt: Instant)

type PhotoOffset   = Int :| GreaterEqual[0]
type PhotoPageSize = Int :| Interval.Closed[1, 24]
final case class PhotoWindow(offset: PhotoOffset, size: PhotoPageSize)
final case class PhotoPage(photos: Vector[PlantPhoto], hasNextPage: Boolean)

enum PhotoMediaType:
  case Jpeg, Png, Webp

final case class PhotoContent(bytes: ByteVector, mediaType: PhotoMediaType)

enum AddPhotoResult:
  case Added(photo: PlantPhoto)
  case PlantMissing
  case AddFailed(reason: Throwable)

enum RemovePhotoResult:
  case Removed(photo: PlantPhoto)
  case PhotoMissing
  case RemoveFailed(reason: Throwable)

enum GetPhotosResult:
  case Read(page: PhotoPage)
  case ReadFailed(reason: Throwable)

enum PhotoWriteResult:
  case Written
  case WriteFailed(reason: Throwable)

enum PhotoReadResult:
  case Read(content: PhotoContent)
  case ContentMissing
  case ReadFailed(reason: Throwable)

type OperationOffset   = Int :| GreaterEqual[0]
type OperationPageSize = Int :| Interval.Closed[1, 10]
final case class OperationWindow(offset: OperationOffset, size: OperationPageSize)
final case class OperationPage(operations: Vector[Operation], hasNextPage: Boolean)

sealed trait OperationDateRange

object OperationDateRange:
  case object Empty                                        extends OperationDateRange
  final case class Recorded(first: Instant, last: Instant) extends OperationDateRange:
    require(!last.isBefore(first), "last recorded operation cannot precede first")

enum GetOperationDateRangeResult:
  case Read(range: OperationDateRange)
  case PlantMissing
  case ReadFailed(reason: Throwable)

enum GetPlantResult:
  case Read(plant: Plant)
  case RecordMissing
  case ReadFailed(reason: Throwable)

enum GetPlantsResult:
  case Read(plants: Vector[Plant])
  case ReadFailed(reason: Throwable)

enum CreatePlantResult:
  case Created(plant: Plant)
  case UnknownComponent
  case CatalogReadFailed(reason: Throwable)
  case CreateFailed(reason: Throwable)

enum AddPlantResult:
  case Added
  case AddFailed(reason: Throwable)

sealed trait ArchivedCountResult

object ArchivedCountResult:
  final case class Counted(count: Long) extends ArchivedCountResult:
    require(count >= 0L, "archived plant count must be non-negative")
  final case class ReadFailed(reason: Throwable) extends ArchivedCountResult

enum EditPlantResult:
  case Edited(plant: Plant)
  case PlantMissing
  case PlantArchived
  case UnknownComponent
  case CatalogReadFailed(reason: Throwable)
  case EditFailed(reason: Throwable)

enum GetOperationResult:
  case Read(operation: Operation)
  case RecordMissing
  case ReadFailed(reason: Throwable)

enum GetOperationsResult:
  case Read(page: OperationPage)
  case ReadFailed(reason: Throwable)

enum LogOperationResult:
  case Logged(id: OperationId)
  case PlantMissing
  case PlantArchived
  case LoggingFailed(reason: Throwable)

enum EditOperationResult:
  case Edited(operation: Operation)
  case OperationMissing
  case OperationTypeMismatch
  case EditFailed(reason: Throwable)

enum DeleteOperationResult:
  case Deleted
  case OperationMissing
  case CannotDeleteLatestRepot
  case DeleteFailed(reason: Throwable)

enum OperationCompensationResult:
  case Compensated
  case CompensationFailed(reason: Throwable)

enum UpdatePlantResult:
  case Updated
  case UpdateFailed(reason: Throwable)
