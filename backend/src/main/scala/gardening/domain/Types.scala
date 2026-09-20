package gardening.domain

import cats.Eq
import cats.data.NonEmptyList
import cats.syntax.either.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

import java.time.Instant

opaque type PlantId = String
object PlantId:
  def apply(value: String): PlantId         = value
  extension (id: PlantId) def value: String = id

opaque type OperationId = String
object OperationId:
  def apply(value: String): OperationId         = value
  extension (id: OperationId) def value: String = id

opaque type Species = String
object Species:
  def apply(value: String): Species              = value
  extension (species: Species) def value: String = species

opaque type Nickname = String
object Nickname:
  def apply(value: String): Nickname               = value
  extension (nickname: Nickname) def value: String = nickname

opaque type Location = String
object Location:
  def apply(value: String): Location               = value
  extension (location: Location) def value: String = location

opaque type Note = String
object Note:
  def apply(value: String): Note           = value
  extension (note: Note) def value: String = note

type Percentage = Int :| Interval.Closed[1, 100]

enum PlantStatus derives CanEqual:
  case Active, Archived

object PlantStatus:
  given Eq[PlantStatus] = Eq.fromUniversalEquals

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
    if parts.isEmpty then SubstrateError.Empty.asLeft
    else if parts.map(_.component).distinct.size < parts.size then SubstrateError.DuplicateComponent.asLeft
    else if parts.map(part => part.share: Int).sum > 100 then SubstrateError.ExceedsTotal.asLeft
    else parts.asRight

  extension (substrate: Substrate) def parts: List[SubstratePart] = substrate

final case class Plant(id: PlantId, details: PlantDetails)

final case class PlantDetails(
    species: Species,
    maybeNickname: Option[Nickname],
    location: Location,
    substrate: Substrate,
    status: PlantStatus
)

sealed trait OperationDetails:
  def maybeNote: Option[Note]

object OperationDetails:
  final case class Care(
      actions: Set[ActionType],
      moisture: MoistureLevel,
      override val maybeNote: Option[Note]
  ) extends OperationDetails
  final case class Repot(
      substrate: Substrate,
      override val maybeNote: Option[Note]
  ) extends OperationDetails

final case class Operation(id: OperationId, plantId: PlantId, date: Instant, details: OperationDetails)

enum JournalRecord:
  case Plant(id: PlantId)
  case Operation(id: OperationId)

final case class JournalCorruption(record: JournalRecord, reason: Throwable)

enum GetPlantResult:
  case Read(plant: Plant)
  case RecordMissing
  case Corrupted(details: NonEmptyList[JournalCorruption])
  case ReadFailed(reason: Throwable)

enum GetPlantsResult:
  case Read(plants: Vector[Plant])
  case Corrupted(details: NonEmptyList[JournalCorruption])
  case ReadFailed(reason: Throwable)

enum GetOperationResult:
  case Read(operation: Operation)
  case RecordMissing
  case Corrupted(details: NonEmptyList[JournalCorruption])
  case ReadFailed(reason: Throwable)

enum GetOperationsResult:
  case Read(operations: Vector[Operation])
  case Corrupted(details: NonEmptyList[JournalCorruption])
  case ReadFailed(reason: Throwable)

enum LogOperationResult:
  case Logged(id: OperationId)
  case LoggingFailed(reason: Throwable)

enum EditOperationResult:
  case Edited(operation: Operation)
  case OperationMissing
  case OperationTypeMismatch
  case Corrupted(details: NonEmptyList[JournalCorruption])
  case EditFailed(reason: Throwable)

enum OperationCompensationResult:
  case Compensated
  case CompensationFailed(reason: Throwable)

enum UpdatePlantResult:
  case Updated
  case UpdateFailed(reason: Throwable)
