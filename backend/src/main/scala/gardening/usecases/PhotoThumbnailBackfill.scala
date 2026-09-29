package gardening.usecases

import gardening.domain.*
import gardening.domain.plants.*
import gardening.ports.{PlantStore, PhotoStore, PhotoContentStore}
import gardening.capabilities.Logger
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual

import language.experimental.captureChecking

import scala.annotation.tailrec
import scala.util.chaining.scalaUtilChainingOps

// One-time, operator-run procedure (see app.BackfillPhotoThumbnails) - never wired into request
// handling or startup. Derives a thumbnail for every existing photo that doesn't have one yet,
// using the same derivation as upload; a photo already carrying a thumbnail is left untouched, so
// running this more than once is a no-op the second time.
trait PhotoThumbnailBackfill:
  def run(): PhotoBackfillResult

object PhotoThumbnailBackfill:

  private val pageSize: PhotoPageSize = 24

  def make(using
      plantStore: PlantStore^,
      photoStore: PhotoStore^,
      contentStore: PhotoContentStore^,
      thumbnail: PhotoThumbnail^
  )(using log: Logger^): PhotoThumbnailBackfill^{plantStore, photoStore, contentStore, thumbnail, log} =
    new LivePhotoThumbnailBackfill

  private class LivePhotoThumbnailBackfill(using
      plantStore: PlantStore^,
      photoStore: PhotoStore^,
      contentStore: PhotoContentStore^,
      thumbnail: PhotoThumbnail^
  )(using log: Logger^) extends PhotoThumbnailBackfill:

    override def run(): PhotoBackfillResult =
      val photoIds =
        for
          plantIds <- readPlantIds
          photoIds <- plantIds.foldLeft[Either[Throwable, Vector[PhotoId]]](Right(Vector.empty)):
            case (Left(reason), _)     => Left(reason)
            case (Right(acc), plantId) => readPhotoIds(plantId).map(acc ++ _)
        yield photoIds

      photoIds match
        case Left(reason) => PhotoBackfillResult.BackfillFailed(reason).tap(_ => log.error("photo thumbnail backfill", reason))
        case Right(ids)   =>
          val outcomes  = ids.map(backfillPhoto)
          val processed = outcomes.collect { case Right(Some(id)) => id }
          val skipped   = outcomes.collect { case Left(skip) => skip }
          skipped.foreach(skip => log.error(s"photo thumbnail backfill skipped ${skip.photo}", skip.reason))
          log.info(s"photo thumbnail backfill complete: processed=${processed.size} skipped=${skipped.size}")
          PhotoBackfillResult.Completed(processed, skipped)

    private def readPlantIds: Either[Throwable, Vector[PlantId]] =
      PlantStatus.values.toVector.foldLeft[Either[Throwable, Vector[PlantId]]](Right(Vector.empty)):
        case (Left(reason), _)    => Left(reason)
        case (Right(acc), status) =>
          plantStore.getPlants(status) match
            case GetPlantsResult.ReadFailed(reason) => Left(reason)
            case GetPlantsResult.Read(plants)       => Right(acc ++ plants.map(_.id))

    private def readPhotoIds(plant: PlantId): Either[Throwable, Vector[PhotoId]] =
      @tailrec
      def loop(offset: Int, acc: Vector[PhotoId]): Either[Throwable, Vector[PhotoId]] =
        offset.refineOption[GreaterEqual[0]] match
          // offset only ever grows from 0 by a page size; never negative.
          // $COVERAGE-OFF$
          case None => Right(acc)
          // $COVERAGE-ON$
          case Some(validOffset) =>
            photoStore.getPhotos(plant, PhotoWindow(validOffset, pageSize)) match
              case GetPhotosResult.ReadFailed(reason) => Left(reason)
              case GetPhotosResult.Read(page)         =>
                val ids = acc ++ page.photos.map(_.id)
                if page.hasNextPage then loop(offset + pageSize, ids) else Right(ids)
      loop(0, Vector.empty)

    private def backfillPhoto(photo: PhotoId): Either[PhotoBackfillSkip, Option[PhotoId]] =
      contentStore.get(photo, PhotoVariant.Thumbnail) match
        case PhotoReadResult.Read(_)            => Right(None)
        case PhotoReadResult.ReadFailed(reason) => Left(PhotoBackfillSkip(photo, reason))
        case PhotoReadResult.ContentMissing     =>
          contentStore.get(photo, PhotoVariant.Original) match
            case PhotoReadResult.ContentMissing     => Left(PhotoBackfillSkip(photo, RuntimeException(s"photo $photo has no original content")))
            case PhotoReadResult.ReadFailed(reason) => Left(PhotoBackfillSkip(photo, reason))
            case PhotoReadResult.Read(original)     =>
              thumbnail.derive(original) match
                case ThumbnailDerivationResult.DerivationFailed(reason) => Left(PhotoBackfillSkip(photo, reason))
                case ThumbnailDerivationResult.Derived(derived)         =>
                  contentStore.put(photo, original, derived) match
                    case PhotoWriteResult.WriteFailed(reason) => Left(PhotoBackfillSkip(photo, reason))
                    case PhotoWriteResult.Written             => Right(Some(photo))
