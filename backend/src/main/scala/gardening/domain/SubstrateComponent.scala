package gardening.domain

import cats.Eq

import java.util.UUID
import scala.util.Try

opaque type SubstrateComponentId = UUID
object SubstrateComponentId:
  def apply(value: UUID): SubstrateComponentId           = value
  def parse(value: String): Option[SubstrateComponentId] = Try(UUID.fromString(value)).toOption
  extension (id: SubstrateComponentId) def value: UUID   = id

opaque type SubstrateComponentName = String
object SubstrateComponentName:
  def apply(value: String): SubstrateComponentName           = value
  extension (name: SubstrateComponentName) def value: String = name

opaque type SubstrateComponentInfo = String
object SubstrateComponentInfo:
  def apply(value: String): SubstrateComponentInfo           = value
  extension (info: SubstrateComponentInfo) def value: String = info

enum SubstrateComponentStatus derives CanEqual:
  case Active, Archived

object SubstrateComponentStatus:
  given Eq[SubstrateComponentStatus] = Eq.fromUniversalEquals

final case class SubstrateComponentData(name: SubstrateComponentName, maybeInfo: Option[SubstrateComponentInfo])
final case class SubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData, status: SubstrateComponentStatus)
