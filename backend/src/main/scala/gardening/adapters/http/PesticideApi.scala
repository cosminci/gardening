package gardening.adapters.http

import cats.syntax.either.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.PesticideCatalog
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

  private val catalogEndpoint       = endpoint.errorOut(JournalError.generic)
  private val getPesticidesEndpoint =
    catalogEndpoint.get.in("pesticides").out(jsonBody[Vector[Pesticide]]).summary("List pesticides")
  private val addPesticideEndpoint =
    catalogEndpoint.post.in("pesticides").in(jsonBody[PesticideData])
      .out(statusCode(StatusCode.Created)).out(jsonBody[Pesticide]).summary("Add a pesticide")
  private val editPesticideEndpoint =
    endpoint.put.in("pesticides" / path[String]("pesticideId")).in(jsonBody[PesticideData])
      .errorOut(JournalError.catalogEdit).out(jsonBody[Pesticide]).summary("Edit a pesticide")

  private[http] val publicEndpoints: List[AnyEndpoint] =
    List(getPesticidesEndpoint, addPesticideEndpoint, editPesticideEndpoint)

  def serverEndpoints(using catalog: PesticideCatalog): List[ServerEndpoint[Any, Identity]] =
    List(
      getPesticidesEndpoint.handle: _ =>
        catalog.getPesticides match
          case CatalogReadResult.Read(pesticides) => pesticides.asRight
          case CatalogReadResult.ReadFailed(_)    => (StatusCode.InternalServerError, JournalError.catalogReadFailed).asLeft,
      addPesticideEndpoint.handle: data =>
        catalog.addPesticide(data) match
          case CatalogAddResult.Added(pesticide) => pesticide.asRight
          case CatalogAddResult.AddFailed(_)     => (StatusCode.InternalServerError, JournalError.catalogWriteFailed).asLeft,
      editPesticideEndpoint.handle: (encodedId, data) =>
        PesticideId.parse(encodedId).fold(JournalError.catalogInvalidId.asLeft): id =>
          catalog.editPesticide(id, data) match
            case CatalogEditResult.Edited(pesticide) => pesticide.asRight
            case CatalogEditResult.RecordMissing     => JournalError.catalogRecordMissing.asLeft
            case CatalogEditResult.EditFailed(_)     => JournalError.catalogWriteFailed.asLeft
    )

  private given CirceConfiguration = CirceConfiguration.default
    .withTransformMemberNames {
      case "maybeInfo"     => "info"
      case "pesticideType" => "type"
      case name            => name
    }
    .withTransformConstructorNames(lowerCamel)
  private given TapirConfiguration = TapirConfiguration.default.copy(toEncodedName = {
    case "maybeInfo"     => "info"
    case "pesticideType" => "type"
    case name            => name
  })

  private given Codec[NomenclatureName] = Codec.from(
    Decoder.decodeString.map(NomenclatureName.apply),
    Encoder.encodeString.contramap(_.value)
  )
  private given Codec[NomenclatureInfo] = Codec.from(
    Decoder.decodeString.map(NomenclatureInfo.apply),
    Encoder.encodeString.contramap(_.value)
  )
  // Tapir requires bidirectional codecs for output-only pesticide identifiers.
  private given Codec[PesticideId] = Codec.from(
    // $COVERAGE-OFF$
    Decoder.decodeString.emap(value => PesticideId.parse(value).toRight(s"invalid pesticide id: $value")),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.value.toString)
  )
  private given Codec[PesticideType]          = ConfiguredEnumCodec.derived
  private given Codec.AsObject[PesticideData] = ConfiguredCodec.derived
  private given Codec.AsObject[Pesticide]     = ConfiguredCodec.derived

  // OpenAPI generation reads the schema; the server never decodes pesticide identifiers in bodies.
  // $COVERAGE-OFF$
  private given Schema[PesticideId] = Schema.string
    .map(PesticideId.parse)(_.value.toString)
    .format("uuid")
  private given Schema[PesticideType] = Schema.derivedEnumeration[PesticideType]
    .apply(encode = Some(value => lowerCamel(value.productPrefix)))
  // $COVERAGE-ON$
  private given Schema[NomenclatureName] = Schema.string
  private given Schema[NomenclatureInfo] = Schema.string
  private given Schema[PesticideData]    = Schema.derived[PesticideData]
    .modify(_.maybeInfo)(_.copy(isOptional = false).nullable)

  private def lowerCamel(name: String) =
    name.substring(0, 1).toLowerCase + name.substring(1)
