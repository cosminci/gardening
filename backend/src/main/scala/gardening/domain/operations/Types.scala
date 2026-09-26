package gardening.domain.operations

import gardening.domain.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*
import java.time.Instant

opaque type OperationId = String
object OperationId:
  def apply(value: String): OperationId         = value
  extension (id: OperationId) def value: String = id

opaque type Note = String
object Note:
  def apply(value: String): Note           = value
  extension (note: Note) def value: String = note

enum ActionType(val label: String):
  case Watered    extends ActionType("Watered")
  case Fertilized extends ActionType("Fertilized")
  case Pesticide  extends ActionType("Insecticide / H2O2")
  case Pruned     extends ActionType("Pruned")
  case NoAction   extends ActionType("None")

enum MoistureLevel(val label: String):
  case Wet           extends MoistureLevel("Wet")
  case ModeratePlus  extends MoistureLevel("Moderate +")
  case ModerateMinus extends MoistureLevel("Moderate -")
  case Dry           extends MoistureLevel("Dry")
  case NoReading     extends MoistureLevel("N/A")

sealed trait OperationDetails:
  def maybeNote: Option[Note]

object OperationDetails:
  final case class Care(
      actions: Set[ActionType],
      pesticides: Set[PesticideId],
      moisture: MoistureLevel,
      override val maybeNote: Option[Note]
  ) extends OperationDetails
  final case class Repot(
      substrate: Substrate,
      override val maybeNote: Option[Note]
  ) extends OperationDetails

final case class Operation(id: OperationId, plantId: PlantId, date: Instant, details: OperationDetails)

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
