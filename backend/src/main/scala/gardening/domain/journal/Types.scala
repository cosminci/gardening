package gardening.domain.journal

import gardening.domain.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*
import java.time.Instant

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

enum CatalogReadResult[+A]:
  case Read(entries: Vector[A])
  case ReadFailed(reason: Throwable)

enum CatalogAddResult[+A]:
  case Added(entry: A)
  case AddFailed(reason: Throwable)

enum CatalogEditResult[+A]:
  case Edited(entry: A)
  case RecordMissing
  case EditFailed(reason: Throwable)

enum GetPlantResult:
  case Read(plant: Plant)
  case RecordMissing
  case ReadFailed(reason: Throwable)

enum GetPlantsResult:
  case Read(plants: Vector[Plant])
  case ReadFailed(reason: Throwable)

sealed trait ArchivedCountResult

object ArchivedCountResult:
  final case class Counted(count: Long) extends ArchivedCountResult:
    require(count >= 0L, "archived plant count must be non-negative")
  final case class ReadFailed(reason: Throwable) extends ArchivedCountResult

enum ArchivePlantResult:
  case Archived
  case PlantMissing
  case AlreadyArchived
  case ArchiveFailed(reason: Throwable)

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

enum OperationCompensationResult:
  case Compensated
  case CompensationFailed(reason: Throwable)

enum UpdatePlantResult:
  case Updated
  case UpdateFailed(reason: Throwable)
