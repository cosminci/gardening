package gardening.domain

import cats.Eq
import cats.syntax.either.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

import java.util.UUID
import scala.util.Try

opaque type PlantId = String
object PlantId:
  def apply(value: String): PlantId         = value
  extension (id: PlantId) def value: String = id

opaque type SubstrateComponentId = UUID
object SubstrateComponentId:
  def apply(value: UUID): SubstrateComponentId           = value
  def parse(value: String): Option[SubstrateComponentId] = Try(UUID.fromString(value)).toOption
  extension (id: SubstrateComponentId) def value: UUID   = id

opaque type PesticideId = UUID
object PesticideId:
  def apply(value: UUID): PesticideId           = value
  def parse(value: String): Option[PesticideId] = Try(UUID.fromString(value)).toOption
  extension (id: PesticideId) def value: UUID   = id

opaque type SubstrateComponentName = String
object SubstrateComponentName:
  def apply(value: String): SubstrateComponentName           = value
  extension (name: SubstrateComponentName) def value: String = name

opaque type SubstrateComponentInfo = String
object SubstrateComponentInfo:
  def apply(value: String): SubstrateComponentInfo           = value
  extension (info: SubstrateComponentInfo) def value: String = info

opaque type PesticideName = String
object PesticideName:
  def apply(value: String): PesticideName           = value
  extension (name: PesticideName) def value: String = name

opaque type PesticideInfo = String
object PesticideInfo:
  def apply(value: String): PesticideInfo           = value
  extension (info: PesticideInfo) def value: String = info

opaque type SubstrateMixName = String
object SubstrateMixName:
  def apply(value: String): SubstrateMixName           = value
  extension (name: SubstrateMixName) def value: String = name

opaque type SubstrateMixNotes = String
object SubstrateMixNotes:
  def apply(value: String): SubstrateMixNotes            = value
  extension (notes: SubstrateMixNotes) def value: String = notes

enum PesticideType:
  case Fungicide, Insecticide, Treatment

enum SubstrateComponentStatus derives CanEqual:
  case Active, Archived

object SubstrateComponentStatus:
  given Eq[SubstrateComponentStatus] = Eq.fromUniversalEquals

final case class SubstrateComponentData(name: SubstrateComponentName, maybeInfo: Option[SubstrateComponentInfo])
final case class SubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData, status: SubstrateComponentStatus)
final case class PesticideData(name: PesticideName, kind: PesticideType, maybeInfo: Option[PesticideInfo])

enum PesticideStatus derives CanEqual:
  case Active, Archived

object PesticideStatus:
  given Eq[PesticideStatus] = Eq.fromUniversalEquals

final case class Pesticide(id: PesticideId, data: PesticideData, status: PesticideStatus)

type Percentage = Int :| Interval.Closed[1, 100]

final case class SubstratePart(componentId: SubstrateComponentId, share: Percentage)

enum SubstrateError:
  case Empty
  case DuplicateComponent
  case ExceedsTotal

opaque type Substrate = List[SubstratePart]
object Substrate:
  def of(parts: List[SubstratePart]): Either[SubstrateError, Substrate] =
    if parts.isEmpty then SubstrateError.Empty.asLeft
    else if parts.map(_.componentId).distinct.size < parts.size then SubstrateError.DuplicateComponent.asLeft
    else if parts.map(part => part.share: Int).sum > 100 then SubstrateError.ExceedsTotal.asLeft
    else parts.asRight

  extension (substrate: Substrate) def parts: List[SubstratePart] = substrate

final case class SubstrateMix(id: UUID, name: SubstrateMixName, notes: Option[SubstrateMixNotes], substrate: Substrate)
