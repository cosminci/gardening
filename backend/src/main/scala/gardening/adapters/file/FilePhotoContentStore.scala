package gardening.adapters.file

import gardening.domain.plants.*
import gardening.ports.PhotoContentStore
import scodec.bits.ByteVector

import java.io.IOException
import java.nio.file.{Files, Path}

object FilePhotoContentStore:

  def make(directory: Path): PhotoContentStore = LiveFilePhotoContentStore(directory)

private class LiveFilePhotoContentStore(directory: Path) extends PhotoContentStore:

  override def put(id: PhotoId, original: PhotoContent, thumbnail: PhotoContent): PhotoWriteResult =
    try
      Files.createDirectories(directory)
      val _ = Files.write(directory.resolve(originalFilename(id, original.mediaType)), original.bytes.toArray)
      val _ = Files.write(directory.resolve(thumbnailFilename(id)), thumbnail.bytes.toArray)
      PhotoWriteResult.Written
    catch case error: IOException => PhotoWriteResult.WriteFailed(error)

  override def get(id: PhotoId, variant: PhotoVariant): PhotoReadResult =
    try
      variant match
        case PhotoVariant.Thumbnail =>
          val path = directory.resolve(thumbnailFilename(id))
          if !Files.exists(path) then PhotoReadResult.ContentMissing
          else PhotoReadResult.Read(PhotoContent(ByteVector(Files.readAllBytes(path)), PhotoMediaType.Jpeg))
        case PhotoVariant.Original =>
          PhotoMediaType.values.collectFirst:
            case mediaType if Files.exists(directory.resolve(originalFilename(id, mediaType))) => mediaType
          match
            case None            => PhotoReadResult.ContentMissing
            case Some(mediaType) =>
              val bytes = ByteVector(Files.readAllBytes(directory.resolve(originalFilename(id, mediaType))))
              PhotoReadResult.Read(PhotoContent(bytes, mediaType))
    catch case error: IOException => PhotoReadResult.ReadFailed(error)

  override def delete(id: PhotoId): PhotoWriteResult =
    try
      PhotoMediaType.values.foreach: mediaType =>
        val _ = Files.deleteIfExists(directory.resolve(originalFilename(id, mediaType)))
      val _ = Files.deleteIfExists(directory.resolve(thumbnailFilename(id)))
      PhotoWriteResult.Written
    catch case error: IOException => PhotoWriteResult.WriteFailed(error)

  private def originalFilename(id: PhotoId, mediaType: PhotoMediaType) =
    val extension = mediaType match
      case PhotoMediaType.Jpeg => "jpeg"
      case PhotoMediaType.Png  => "png"
    s"${id.value}-original.$extension"

  private def thumbnailFilename(id: PhotoId) = s"${id.value}-thumbnail.jpeg"
