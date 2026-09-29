package gardening.domain.plants

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

enum ThumbnailDerivationResult:
  case Derived(thumbnail: PhotoContent)
  case DerivationFailed(reason: Throwable)

final case class PhotoBackfillSkip(photo: PhotoId, reason: Throwable)

enum PhotoBackfillResult:
  case Completed(processed: Vector[PhotoId], skipped: Vector[PhotoBackfillSkip])
  case BackfillFailed(reason: Throwable)
