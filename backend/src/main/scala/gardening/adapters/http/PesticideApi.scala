package gardening.adapters.http

import cats.syntax.either.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.{PesticideCatalog, PesticideUpdateResult}
import io.circe.derivation.{Configuration as CirceConfiguration, ConfiguredCodec, ConfiguredEnumCodec}
import io.circe.{Codec, Decoder, Encoder}
import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.generic.Configuration as TapirConfiguration
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint

object PesticideApi:

  private val recordMissing     = ApiError("pesticide not found")
  private val invalidId         = ApiError("invalid pesticide id")
  private val readFailed        = ApiError("pesticides could not be read")
  private val writeFailed       = ApiError("pesticide could not be saved")
  private val pesticideArchived = ApiError("pesticide is archived")
  private val alreadyArchived   = ApiError("pesticide is already archived")
  private val archiveFailed     = ApiError("pesticide could not be archived")
  private val editErrors        = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.BadRequest, jsonBody[ApiError])(invalidId),
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(recordMissing),
    oneOfVariantExactMatcher(StatusCode.Conflict, jsonBody[ApiError])(pesticideArchived),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )
  private val archiveErrors = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.BadRequest, jsonBody[ApiError])(invalidId),
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(recordMissing),
    oneOfVariantExactMatcher(StatusCode.Conflict, jsonBody[ApiError])(alreadyArchived),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )

  private val catalogEndpoint       = endpoint.errorOut(ApiError.generic)
  private val getPesticidesEndpoint = catalogEndpoint.get.in("pesticides").out(jsonBody[Vector[Pesticide]]).summary("List pesticides")
  private val addPesticideEndpoint  = catalogEndpoint.post.in("pesticides").in(jsonBody[PesticideData])
    .out(statusCode(StatusCode.Created)).out(jsonBody[Pesticide]).summary("Add a pesticide")
  private val editPesticideEndpoint = endpoint.put.in("pesticides" / path[String]("pesticideId")).in(jsonBody[PesticideData])
    .errorOut(editErrors).out(jsonBody[Pesticide]).summary("Edit a pesticide")
  private val archivePesticideEndpoint = endpoint.post.in("pesticides" / path[String]("pesticideId") / "archive")
    .errorOut(archiveErrors).out(jsonBody[Pesticide]).summary("Archive a pesticide")

  private[http] val publicEndpoints: List[AnyEndpoint] =
    List(getPesticidesEndpoint, addPesticideEndpoint, editPesticideEndpoint, archivePesticideEndpoint)

  def serverEndpoints(using catalog: PesticideCatalog): List[ServerEndpoint[Any, Identity]] =
    List(
      getPesticidesEndpoint.handle: _ =>
        catalog.getPesticides match
          case CatalogReadResult.Read(pesticides) => pesticides.asRight
          case CatalogReadResult.ReadFailed(_)    => (StatusCode.InternalServerError, readFailed).asLeft,
      addPesticideEndpoint.handle: data =>
        catalog.addPesticide(data) match
          case CatalogAddResult.Added(pesticide) => pesticide.asRight
          case CatalogAddResult.AddFailed(_)     => (StatusCode.InternalServerError, writeFailed).asLeft,
      editPesticideEndpoint.handle: (encodedId, data) =>
        PesticideId.parse(encodedId).fold(invalidId.asLeft): id =>
          catalog.editPesticide(id, data) match
            case PesticideUpdateResult.Updated(pesticide) => pesticide.asRight
            case PesticideUpdateResult.PesticideMissing   => recordMissing.asLeft
            case PesticideUpdateResult.PesticideArchived  => pesticideArchived.asLeft
            case PesticideUpdateResult.UpdateFailed(_)    => writeFailed.asLeft,
      archivePesticideEndpoint.handle: encodedId =>
        PesticideId.parse(encodedId).fold(invalidId.asLeft): id =>
          catalog.archivePesticide(id) match
            case PesticideUpdateResult.Updated(pesticide) => pesticide.asRight
            case PesticideUpdateResult.PesticideMissing   => recordMissing.asLeft
            case PesticideUpdateResult.PesticideArchived  => alreadyArchived.asLeft
            case PesticideUpdateResult.UpdateFailed(_)    => archiveFailed.asLeft
    )

  private given CirceConfiguration = CirceConfiguration.default
    .withTransformMemberNames {
      case "maybeInfo" => "info"
      case "kind"      => "type"
      case name        => name
    }
    .withTransformConstructorNames(lowerCamel)
  private given TapirConfiguration = TapirConfiguration.default.copy(toEncodedName = {
    case "maybeInfo" => "info"
    case "kind"      => "type"
    case name        => name
  })

  private given Codec[PesticideName] = Codec.from(Decoder.decodeString.map(PesticideName.apply), Encoder.encodeString.contramap(_.value))
  private given Codec[PesticideInfo] = Codec.from(Decoder.decodeString.map(PesticideInfo.apply), Encoder.encodeString.contramap(_.value))
  private given Codec[PesticideId]   = Codec.from(
    // Pesticide identifiers appear only in response bodies.
    // $COVERAGE-OFF$
    Decoder.decodeString.emap(value => PesticideId.parse(value).toRight(s"invalid pesticide id: $value")),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.value.toString)
  )
  private given Codec[PesticideType]          = ConfiguredEnumCodec.derived
  private given Codec[PesticideStatus]        = ConfiguredEnumCodec.derived
  private given Codec.AsObject[PesticideData] = ConfiguredCodec.derived
  private given Codec.AsObject[Pesticide]     = ConfiguredCodec.derived

  // JSON bodies use Circe; Tapir does not invoke these identifier schema mappings at runtime.
  // $COVERAGE-OFF$
  private given Schema[PesticideId]     = Schema.string.map(PesticideId.parse)(_.value.toString).format("uuid")
  private given Schema[PesticideType]   = Schema.derivedEnumeration[PesticideType].apply(encode = Some(value => lowerCamel(value.productPrefix)))
  private given Schema[PesticideStatus] = Schema.derivedEnumeration[PesticideStatus].apply(encode = Some(value => lowerCamel(value.productPrefix)))
  // $COVERAGE-ON$
  private given Schema[PesticideName] = Schema.string
  private given Schema[PesticideInfo] = Schema.string
  private given Schema[PesticideData] = Schema.derived[PesticideData].modify(_.maybeInfo)(_.copy(isOptional = false).nullable)

  private def lowerCamel(name: String) =
    name.substring(0, 1).toLowerCase + name.substring(1)
