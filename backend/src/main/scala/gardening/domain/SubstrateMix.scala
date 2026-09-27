package gardening.domain

import java.util.UUID

opaque type SubstrateMixName = String
object SubstrateMixName:
  def apply(value: String): SubstrateMixName           = value
  extension (name: SubstrateMixName) def value: String = name

opaque type SubstrateMixNotes = String
object SubstrateMixNotes:
  def apply(value: String): SubstrateMixNotes            = value
  extension (notes: SubstrateMixNotes) def value: String = notes

final case class SubstrateMix(id: UUID, name: SubstrateMixName, maybeNotes: Option[SubstrateMixNotes], substrate: Substrate)
