package gardening.domain

import cats.Eq

import java.util.UUID
import scala.util.Try

opaque type PesticideId = UUID
object PesticideId:
  def apply(value: UUID): PesticideId           = value
  def parse(value: String): Option[PesticideId] = Try(UUID.fromString(value)).toOption
  extension (id: PesticideId) def value: UUID   = id

opaque type PesticideName = String
object PesticideName:
  def apply(value: String): PesticideName           = value
  extension (name: PesticideName) def value: String = name

opaque type PesticideInfo = String
object PesticideInfo:
  def apply(value: String): PesticideInfo           = value
  extension (info: PesticideInfo) def value: String = info

enum PesticideType:
  case Fungicide, Insecticide, Treatment

final case class PesticideData(name: PesticideName, kind: PesticideType, maybeInfo: Option[PesticideInfo])

enum PesticideStatus derives CanEqual:
  case Active, Archived

object PesticideStatus:
  given Eq[PesticideStatus] = Eq.fromUniversalEquals

final case class Pesticide(id: PesticideId, data: PesticideData, status: PesticideStatus)
