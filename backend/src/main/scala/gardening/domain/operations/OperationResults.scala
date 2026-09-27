package gardening.domain.operations

enum GetOperationDateRangeResult:
  case Read(range: OperationDateRange)
  case PlantMissing
  case ReadFailed(reason: Throwable)

enum GetOperationResult:
  case Read(operation: Operation)
  case RecordMissing
  case ReadFailed(reason: Throwable)

enum GetOperationsResult:
  case Read(page: OperationPage)
  case ReadFailed(reason: Throwable)

enum GetLatestRepotResult:
  case Read(operation: Option[Operation])
  case ReadFailed(reason: Throwable)

enum LogOperationResult:
  case Logged(id: OperationId)
  case PlantMissing
  case PlantArchived
  case LoggingFailed(reason: Throwable)

enum AddOperationResult:
  case Logged(id: OperationId)
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
