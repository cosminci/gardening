package gardening.domain

import cats.syntax.either.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

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
