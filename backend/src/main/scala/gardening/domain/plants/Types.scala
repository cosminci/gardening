package gardening.domain.plants

import cats.Eq
import gardening.domain.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*
import java.time.Instant
import java.util.UUID
import scodec.bits.ByteVector

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

opaque type PhotoId = UUID
object PhotoId:
  def apply(value: UUID): PhotoId         = value
  extension (id: PhotoId) def value: UUID = id

final case class PlantPhoto(id: PhotoId, plantId: PlantId, capturedAt: Instant)

type PhotoOffset   = Int :| GreaterEqual[0]
type PhotoPageSize = Int :| Interval.Closed[1, 24]
final case class PhotoWindow(offset: PhotoOffset, size: PhotoPageSize)
final case class PhotoPage(photos: Vector[PlantPhoto], hasNextPage: Boolean)

enum PhotoMediaType:
  case Jpeg, Png, Webp

final case class PhotoContent(bytes: ByteVector, mediaType: PhotoMediaType)

enum AddPhotoResult:
  case Added(photo: PlantPhoto)
  case PlantMissing
  case AddFailed(reason: Throwable)

enum RemovePhotoResult:
  case Removed(photo: PlantPhoto)
  case PhotoMissing
  case RemoveFailed(reason: Throwable)

enum GetPhotosResult:
  case Read(page: PhotoPage)
  case ReadFailed(reason: Throwable)

enum PhotoWriteResult:
  case Written
  case WriteFailed(reason: Throwable)

enum PhotoReadResult:
  case Read(content: PhotoContent)
  case ContentMissing
  case ReadFailed(reason: Throwable)

enum GetPlantResult:
  case Read(plant: Plant)
  case RecordMissing
  case ReadFailed(reason: Throwable)

enum GetPlantsResult:
  case Read(plants: Vector[Plant])
  case ReadFailed(reason: Throwable)

enum CreatePlantResult:
  case Created(plant: Plant)
  case UnknownComponent
  case CatalogReadFailed(reason: Throwable)
  case CreateFailed(reason: Throwable)

enum AddPlantResult:
  case Added
  case AddFailed(reason: Throwable)

sealed trait ArchivedCountResult

object ArchivedCountResult:
  final case class Counted(count: Long) extends ArchivedCountResult:
    require(count >= 0L, "archived plant count must be non-negative")
  final case class ReadFailed(reason: Throwable) extends ArchivedCountResult

enum EditPlantResult:
  case Edited(plant: Plant)
  case PlantMissing
  case PlantArchived
  case UnknownComponent
  case CatalogReadFailed(reason: Throwable)
  case EditFailed(reason: Throwable)

enum UpdatePlantResult:
  case Updated
  case UpdateFailed(reason: Throwable)
