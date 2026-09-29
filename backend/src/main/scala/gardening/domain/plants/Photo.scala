package gardening.domain.plants

import gardening.domain.PlantId
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*
import scodec.bits.ByteVector

import java.time.Instant
import java.util.UUID

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
  case Jpeg, Png

enum PhotoVariant:
  case Original, Thumbnail

final case class PhotoContent(bytes: ByteVector, mediaType: PhotoMediaType)

enum PhotoWriteIntentStatus:
  case Pending, Done

enum PhotoWriteIntent(val key: String, val status: PhotoWriteIntentStatus):
  case Add(override val key: String, override val status: PhotoWriteIntentStatus, plantId: PlantId, capturedAt: Instant, photoId: PhotoId)
      extends PhotoWriteIntent(key, status)
  case Remove(override val key: String, override val status: PhotoWriteIntentStatus, photoId: PhotoId) extends PhotoWriteIntent(key, status)
