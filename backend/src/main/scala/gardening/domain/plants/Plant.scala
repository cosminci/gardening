package gardening.domain.plants

import cats.Eq
import gardening.domain.*

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

enum PlantStatus derives CanEqual:
  case Active, Archived

object PlantStatus:
  given Eq[PlantStatus] = Eq.fromUniversalEquals

final case class Plant(id: PlantId, details: PlantDetails)

final case class PlantDetails(
    species: Species,
    maybeNickname: Option[Nickname],
    location: Location,
    substrate: Substrate,
    status: PlantStatus
)
