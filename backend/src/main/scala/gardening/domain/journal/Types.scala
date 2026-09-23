package gardening.domain.journal

import gardening.domain.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

type OperationOffset   = Int :| GreaterEqual[0]
type OperationPageSize = Int :| Interval.Closed[1, 10]
final case class OperationWindow(offset: OperationOffset, size: OperationPageSize)
final case class OperationPage(operations: Vector[Operation], hasNextPage: Boolean)

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

enum GetOperationResult:
  case Read(operation: Operation)
  case RecordMissing
  case ReadFailed(reason: Throwable)

enum GetOperationsResult:
  case Read(page: OperationPage)
  case ReadFailed(reason: Throwable)

enum LogOperationResult:
  case Logged(id: OperationId)
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
