package gardening.adapters.http

import cats.syntax.either.*
import cats.syntax.eq.*
import gardening.domain.*
import gardening.domain.plants.*
import gardening.usecases.PlantManager
import io.circe.{Codec, Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.{GreaterEqual, Interval}
import scodec.bits.ByteVector
import sttp.model.{HeaderNames, StatusCode}
import sttp.model.Part
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.Codec as TapirCodec
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import scala.util.Try

import Codecs.given

object PhotoApi:

  private val MaxPhotoBytes: Long = 20L * 1024 * 1024

  private val plantMissing    = ApiError("plant not found")
  private val photoMissing    = ApiError("photo not found")
  private val contentMissing  = ApiError("photo content not found")
  private val unsupportedType = ApiError("unsupported media type: only image/jpeg and image/png are accepted")
  private val photoTooLarge   = ApiError(s"photo exceeds the ${MaxPhotoBytes / (1024 * 1024)} MiB size limit")
  private val addPhotoErrors  = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(plantMissing),
    oneOfVariantExactMatcher(StatusCode.UnsupportedMediaType, jsonBody[ApiError])(unsupportedType),
    oneOfVariantExactMatcher(StatusCode.PayloadTooLarge, jsonBody[ApiError])(photoTooLarge),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )
  private val getPhotosErrors = oneOf[ApiError](
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )
  private val removePhotoErrors = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(photoMissing),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )
  private val photoContentErrors = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(contentMissing),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )

  // The field must be Part[File], not Part[Array[Byte]]: tapir's multipart derivation prefers a
  // circe JSON codec over the raw byte-array part codec once tapir-json-circe is in scope (Array[Byte]
  // has an implicit circe Codec; File does not), corrupting binary uploads. File also avoids
  // buffering the whole upload in memory.
  final private case class PhotoUploadPart(file: Part[File])
  final private case class AddedPhoto(id: String, capturedAt: Instant) derives Codec.AsObject
  final private case class PhotoItem(id: String, capturedAt: Instant) derives Codec.AsObject
  final private case class PhotoPageResponse(photos: Vector[PhotoItem], hasNextPage: Boolean) derives Codec.AsObject

  private val addPhotoEndpoint =
    endpoint.post.in("plants" / path[String]("plantId") / "photos").in(multipartBody[PhotoUploadPart])
      .errorOut(addPhotoErrors).out(statusCode(StatusCode.Created)).out(jsonBody[AddedPhoto])
      .summary("Upload a photo for a plant")

  private val getPhotosEndpoint =
    endpoint.get.in("plants" / path[String]("plantId") / "photos")
      .in(query[PhotoOffset]("offset").default(0))
      .in(query[PhotoPageSize]("pageSize").default(12))
      .errorOut(getPhotosErrors).out(jsonBody[PhotoPageResponse])
      .summary("List a bounded page of plant photos")

  private val removePhotoEndpoint =
    endpoint.delete.in("photos" / path[String]("photoId"))
      .errorOut(removePhotoErrors).out(statusCode(StatusCode.NoContent))
      .summary("Remove a photo")

  private val photoContentEndpoint =
    endpoint.get.in("photos" / path[String]("photoId") / "content")
      .in(query[PhotoVariant]("variant").default(PhotoVariant.Original))
      .errorOut(photoContentErrors).out(byteArrayBody).out(header[String](HeaderNames.ContentType))
      .summary("Retrieve the raw photo content")

  private[http] val publicEndpoints: List[AnyEndpoint] =
    List(addPhotoEndpoint, getPhotosEndpoint, removePhotoEndpoint, photoContentEndpoint)

  def serverEndpoints(using plants: PlantManager): List[ServerEndpoint[Any, Identity]] =
    List(
      addPhotoEndpoint.handle: (plantId, upload) =>
        val part = upload.file
        try
          if part.body.length() > MaxPhotoBytes then photoTooLarge.asLeft
          else
            val bytes = Files.readAllBytes(part.body.toPath)
            sniffPhotoMediaType(bytes) match
              case None            => unsupportedType.asLeft
              case Some(mediaType) =>
                plants.addPhoto(PlantId(plantId), PhotoContent(ByteVector(bytes), mediaType)) match
                  case AddPhotoResult.Added(photo) => AddedPhoto(photo.id.value.toString, photo.capturedAt).asRight
                  case AddPhotoResult.PlantMissing => plantMissing.asLeft
                  case AddPhotoResult.AddFailed(_) => ApiError("photo could not be added").asLeft
        finally part.body.delete(): Unit
      ,
      getPhotosEndpoint.handle: (plantId, offset, pageSize) =>
        plants.getPhotos(PlantId(plantId), PhotoWindow(offset, pageSize)) match
          case GetPhotosResult.Read(page) =>
            PhotoPageResponse(
              page.photos.map(p => PhotoItem(p.id.value.toString, p.capturedAt)),
              page.hasNextPage
            ).asRight
          case GetPhotosResult.ReadFailed(_) => ApiError("photos could not be read").asLeft,
      removePhotoEndpoint.handle: photoId =>
        plants.removePhoto(PhotoId(UUID.fromString(photoId))) match
          case RemovePhotoResult.Removed(_)      => ().asRight
          case RemovePhotoResult.PhotoMissing    => photoMissing.asLeft
          case RemovePhotoResult.RemoveFailed(_) => ApiError("photo could not be removed").asLeft,
      photoContentEndpoint.handle: (photoId, variant) =>
        plants.getPhotoContent(PhotoId(UUID.fromString(photoId)), variant) match
          case PhotoReadResult.Read(content) =>
            val contentType = content.mediaType match
              case PhotoMediaType.Jpeg => "image/jpeg"
              case PhotoMediaType.Png  => "image/png"
            (content.bytes.toArray, contentType).asRight
          case PhotoReadResult.ContentMissing => contentMissing.asLeft
          case PhotoReadResult.ReadFailed(_)  => ApiError("photo content could not be read").asLeft
    )

  // The client's declared Content-Type is never trusted for validation (and tapir's multipart
  // decoder doesn't even surface it for Part[File] parts) - the media type is sniffed from the
  // content's own magic bytes instead.
  private def sniffPhotoMediaType(bytes: Array[Byte]): Option[PhotoMediaType] =
    def prefixMatches(signature: Int*): Boolean =
      bytes.length >= signature.size && bytes.take(signature.size).map(_ & 0xff).toSeq === signature.toSeq
    if prefixMatches(0xff, 0xd8) then Some(PhotoMediaType.Jpeg)
    else if prefixMatches(0x89, 0x50, 0x4e, 0x47) then Some(PhotoMediaType.Png)
    else None

  // Tapir validates query params with the plain codec, not this schema's inverse mapping.
  // $COVERAGE-OFF$
  private lazy val photoOffsetSchema   = Schema.schemaForInt.validate(Validator.min(0)).map(_.refineOption[GreaterEqual[0]])(value => value)
  private lazy val photoPageSizeSchema =
    Schema.schemaForInt.validate(Validator.min(1).and(Validator.max(24))).map(_.refineOption[Interval.Closed[1, 24]])(value => value)
  // $COVERAGE-ON$

  private given TapirCodec.PlainCodec[PhotoOffset] = TapirCodec.int.mapDecode(value =>
    value.refineOption[GreaterEqual[0]] match
      case Some(offset) => DecodeResult.Value(offset)
      case None         => DecodeResult.Error(value.toString, IllegalArgumentException("offset must be at least 0"))
  )(value => value).schema(photoOffsetSchema)

  private given TapirCodec.PlainCodec[PhotoPageSize] = TapirCodec.int.mapDecode(value =>
    value.refineOption[Interval.Closed[1, 24]] match
      case Some(size) => DecodeResult.Value(size)
      case None       => DecodeResult.Error(value.toString, IllegalArgumentException("page size must be between 1 and 24"))
  )(value => value).schema(photoPageSizeSchema)

  private given TapirCodec.PlainCodec[PhotoVariant] = TapirCodec.string.mapDecode {
    case "original"  => DecodeResult.Value(PhotoVariant.Original)
    case "thumbnail" => DecodeResult.Value(PhotoVariant.Thumbnail)
    case other       => DecodeResult.Error(other, IllegalArgumentException("variant must be original or thumbnail"))
  } {
    case PhotoVariant.Original  => "original"
    case PhotoVariant.Thumbnail => "thumbnail"
  }

  // Photo timestamps are server-assigned and output-only in JSON bodies.
  // $COVERAGE-OFF$
  private given Codec[Instant] =
    Codec.from(Decoder.decodeString.emapTry(v => Try(Instant.parse(v))), Encoder.encodeString.contramap(_.toString))
  // $COVERAGE-ON$

  private given Schema[PhotoUploadPart]   = Schema.derived[PhotoUploadPart]
  private given Schema[AddedPhoto]        = Schema.derived[AddedPhoto].modify(_.capturedAt)(_.copy(isOptional = false))
  private given Schema[PhotoItem]         = Schema.derived[PhotoItem].modify(_.capturedAt)(_.copy(isOptional = false))
  private given Schema[PhotoPageResponse] =
    Schema.derived[PhotoPageResponse].modify(_.photos)(_.copy(isOptional = false))
