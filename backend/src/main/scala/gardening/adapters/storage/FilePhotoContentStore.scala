package gardening.adapters.storage

import gardening.domain.journal.*
import scodec.bits.ByteVector

import java.io.IOException
import java.nio.file.{Files, Path}

object FilePhotoContentStore:

  def make(directory: Path): PhotoContentStore = LiveFilePhotoContentStore(directory)

private class LiveFilePhotoContentStore(directory: Path) extends PhotoContentStore:

  override def put(id: PhotoId, content: PhotoContent): PhotoWriteResult =
    try
      Files.createDirectories(directory)
      val _ = Files.write(directory.resolve(filename(id, content.mediaType)), content.bytes.toArray)
      PhotoWriteResult.Written
    catch case error: IOException => PhotoWriteResult.WriteFailed(error)

  override def get(id: PhotoId): PhotoReadResult =
    try
      PhotoMediaType.values.collectFirst:
        case mediaType if Files.exists(directory.resolve(filename(id, mediaType))) => mediaType
      match
        case None            => PhotoReadResult.ContentMissing
        case Some(mediaType) =>
          val bytes = ByteVector(Files.readAllBytes(directory.resolve(filename(id, mediaType))))
          PhotoReadResult.Read(PhotoContent(bytes, mediaType))
    catch case error: IOException => PhotoReadResult.ReadFailed(error)

  override def delete(id: PhotoId): PhotoWriteResult =
    try
      PhotoMediaType.values.foreach: mediaType =>
        val _ = Files.deleteIfExists(directory.resolve(filename(id, mediaType)))
      PhotoWriteResult.Written
    catch case error: IOException => PhotoWriteResult.WriteFailed(error)

  private def filename(id: PhotoId, mediaType: PhotoMediaType) =
    val extension = mediaType match
      case PhotoMediaType.Jpeg => "jpeg"
      case PhotoMediaType.Png  => "png"
      case PhotoMediaType.Webp => "webp"
    s"${id.value}.$extension"
