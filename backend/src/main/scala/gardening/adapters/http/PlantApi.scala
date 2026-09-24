package gardening.adapters.http

import cats.syntax.either.*
import gardening.domain.*
import gardening.domain.attention.PlantAttentionMonitor
import gardening.domain.journal.*
import io.circe.derivation.{ConfiguredCodec, ConfiguredEnumCodec}
import io.circe.{Codec, Decoder, Encoder, Json}
import sttp.model.{MediaType, StatusCode}
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.Codec as TapirCodec
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import scala.util.chaining.*

import Codecs.given

final private case class ArchivedPlantCount(count: Long) derives Codec.AsObject
final private case class PlantPatchOperation(op: String, path: String, value: Json) derives Codec.AsObject

object PlantApi:

  private val plantMissing              = ApiError("plant not found")
  private val plantArchived             = ApiError("plant already archived")
  private val unsupportedPlantPatch     = "unsupported plant patch"
  private val unsupportedPatchMediaType = ApiError("unsupported patch media type")
  private val plantPatchErrors          =
    oneOf[ApiError | String](
      oneOfVariantExactMatcher(StatusCode.BadRequest, stringBody)(unsupportedPlantPatch),
      oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(plantMissing),
      oneOfVariantExactMatcher(StatusCode.Conflict, jsonBody[ApiError])(plantArchived),
      oneOfVariantExactMatcher(StatusCode.UnsupportedMediaType, jsonBody[ApiError])(unsupportedPatchMediaType),
      oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
    )

  final private case class JsonPatchFormat() extends CodecFormat:
    override val mediaType: MediaType = MediaType.unsafeApply("application", "json-patch+json")

  private val plantPatchBody =
    stringBodyUtf8AnyFormat(
      summon[TapirCodec.JsonCodec[Vector[PlantPatchOperation]]]
        .schema(_.copy(isOptional = false))
        .format(JsonPatchFormat())
    )

  private val plantEndpoint     = endpoint.errorOut(ApiError.generic)
  private val getPlantsEndpoint =
    plantEndpoint.get.in("plants").in(query[PlantStatus]("status").default(PlantStatus.Active))
      .out(jsonBody[Vector[Plant]]).summary("List plants by status")
  private val getArchivedCountEndpoint =
    plantEndpoint.get.in("plants" / "archived" / "count").out(jsonBody[ArchivedPlantCount]).summary("Count archived plants")
  private val patchPlantEndpoint =
    endpoint.patch.in("plants" / path[String]("plantId")).in(extractFromRequest(_.contentTypeParsed)).in(plantPatchBody)
      .errorOut(plantPatchErrors)
      .out(statusCode(StatusCode.NoContent)).summary("Patch a plant")

  private[http] val publicEndpoints: List[AnyEndpoint] =
    List(getPlantsEndpoint, getArchivedCountEndpoint, patchPlantEndpoint)

  def serverEndpoints(using journal: PlantJournal, attention: PlantAttentionMonitor): List[ServerEndpoint[Any, Identity]] =
    List(
      getPlantsEndpoint.handle: status =>
        journal.getPlants(status) match
          case GetPlantsResult.Read(plants)  => plants.asRight
          case GetPlantsResult.ReadFailed(_) => (StatusCode.InternalServerError, ApiError("plants could not be read")).asLeft,
      getArchivedCountEndpoint.handle: _ =>
        journal.getArchivedCount match
          case ArchivedCountResult.Counted(count) => ArchivedPlantCount(count).asRight
          case ArchivedCountResult.ReadFailed(_)  => (StatusCode.InternalServerError, ApiError("archived count could not be read")).asLeft,
      patchPlantEndpoint.handle: (plantId, contentType, patch) =>
        val isJsonPatch = contentType.exists(mediaType => mediaType.mainType.equals("application") && mediaType.subType.equals("json-patch+json"))
        if !isJsonPatch then unsupportedPatchMediaType.asLeft
        else
          patch match
            case Vector(PlantPatchOperation("replace", "/details/status", value)) if value.equals(Json.fromString("archived")) =>
              journal.archivePlant(PlantId(plantId)) match
                case ArchivePlantResult.Archived         => attention.refreshAll.pipe(_ => ().asRight)
                case ArchivePlantResult.PlantMissing     => plantMissing.asLeft
                case ArchivePlantResult.AlreadyArchived  => plantArchived.asLeft
                case ArchivePlantResult.ArchiveFailed(_) => ApiError("plant could not be archived").asLeft
            case _ => unsupportedPlantPatch.asLeft
    )

  private given Schema[Json] = Schema.any

  private given TapirCodec.PlainCodec[PlantStatus] = TapirCodec.string
    .mapDecode(value =>
      PlantStatus.values.find(status => Codecs.lowerCamel(status.toString).equals(value)) match
        case Some(status) => DecodeResult.Value(status)
        case None         => DecodeResult.Error(value, IllegalArgumentException(s"invalid plant status: $value"))
    )(status => Codecs.lowerCamel(status.toString))
    .schema(Codecs.enumSchema[PlantStatus])

  private given Schema[PlantStatus] = Codecs.enumSchema[PlantStatus]

  private given Codec[PlantStatus] = ConfiguredEnumCodec.derived

  private given Codec[Species] = Codec.from(
    // Plant species are output-only in JSON bodies.
    // $COVERAGE-OFF$
    Decoder.decodeString.map(Species.apply),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.value)
  )
  private given Codec[Nickname] = Codec.from(
    // Plant nicknames are output-only in JSON bodies.
    // $COVERAGE-OFF$
    Decoder.decodeString.map(Nickname.apply),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.value)
  )
  private given Codec[Location] = Codec.from(
    // Plant locations are output-only in JSON bodies.
    // $COVERAGE-OFF$
    Decoder.decodeString.map(Location.apply),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.value)
  )
  private given Codec.AsObject[PlantDetails] = ConfiguredCodec.derived
  private given Codec.AsObject[Plant]        = ConfiguredCodec.derived

  private type PlantText = Species | Nickname | Location
  private given [A <: PlantText]: Schema[A] = Schema.string
  private given Schema[PlantDetails]        = Schema
    .derived[PlantDetails]
    .modify(_.maybeNickname)(_.copy(isOptional = false).nullable)
    .modify(_.substrate)(_.copy(isOptional = false))
