package gardening.domain

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

import java.time.Instant

opaque type PlantId = String
object PlantId:
  def apply(value: String): PlantId = value

opaque type OperationId = String
object OperationId:
  def apply(value: String): OperationId = value

opaque type Species = String
object Species:
  def apply(value: String): Species = value

opaque type Nickname = String
object Nickname:
  def apply(value: String): Nickname = value

opaque type Location = String
object Location:
  def apply(value: String): Location = value

opaque type Note = String
object Note:
  def apply(value: String): Note = value

type Percentage = Int :| Interval.Closed[1, 100]

enum PlantStatus:
  case Active, Archived

enum SubstrateComponent(val label: String):
  case KekkilaUniversal  extends SubstrateComponent("Kekkila universal peat")
  case KekkilaEricaceous extends SubstrateComponent("Kekkila ericaceous peat")
  case Perlite           extends SubstrateComponent("Perlite")
  case PineBark          extends SubstrateComponent("Pine bark")
  case Sand3to5          extends SubstrateComponent("Sand 3-5 mm")
  case Sand4to8          extends SubstrateComponent("Sand 4-8 mm")
  case Leca              extends SubstrateComponent("LECA")

enum ActionType(val label: String):
  case Watered    extends ActionType("Watered")
  case Fertilized extends ActionType("Fertilized")
  case Repotted   extends ActionType("Repotted")
  case Pesticide  extends ActionType("Insecticide / H2O2")
  case Pruned     extends ActionType("Pruned")
  case NoAction   extends ActionType("None")

enum MoistureLevel(val label: String):
  case Wet           extends MoistureLevel("Wet")
  case ModeratePlus  extends MoistureLevel("Moderate +")
  case ModerateMinus extends MoistureLevel("Moderate -")
  case Dry           extends MoistureLevel("Dry")
  case NoReading     extends MoistureLevel("N/A")

final case class SubstratePart(component: SubstrateComponent, share: Percentage)

enum SubstrateError:
  case Empty
  case DuplicateComponent
  case ExceedsTotal

opaque type Substrate = List[SubstratePart]
object Substrate:
  def of(parts: List[SubstratePart]): Either[SubstrateError, Substrate] =
    if parts.isEmpty then Left(SubstrateError.Empty)
    else if parts.map(_.component).distinct.size < parts.size then Left(SubstrateError.DuplicateComponent)
    else if parts.map(part => part.share: Int).sum > 100 then Left(SubstrateError.ExceedsTotal)
    else Right(parts)

final case class Plant(
    id: PlantId,
    species: Species,
    maybeNickname: Option[Nickname],
    location: Location,
    substrate: Substrate,
    status: PlantStatus
)

final case class OperationDetails(
    date: Instant,
    actions: Set[ActionType],
    moisture: MoistureLevel,
    maybeSubstrate: Option[Substrate],
    maybeNote: Option[Note]
)

final case class Operation(id: OperationId, plantId: PlantId, details: OperationDetails)

enum LogOperationResult:
  case Logged(id: OperationId)
  case LoggingFailed(reason: Throwable)

enum EditOperationResult:
  case Edited(operation: Operation)
  case OperationMissing
  case EditFailed(reason: Throwable)

enum RemoveOperationResult:
  case Removed(operation: Operation)
  case AlreadyRemoved
  case RemoveFailed(reason: Throwable)
