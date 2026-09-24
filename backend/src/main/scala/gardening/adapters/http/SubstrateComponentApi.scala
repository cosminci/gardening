package gardening.adapters.http

import cats.syntax.either.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.substrate.SubstrateComponentCatalog
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.{Codec, Decoder, Encoder}
import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.generic.Configuration as TapirConfiguration
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint

object SubstrateComponentApi:

  private val recordMissing = ApiError("nomenclature not found")
  private val invalidId     = ApiError("invalid nomenclature id")
  private val readFailed    = ApiError("nomenclatures could not be read")
  private val writeFailed   = ApiError("nomenclature could not be saved")
  private val editErrors    =
    oneOf[ApiError](
      oneOfVariantExactMatcher(StatusCode.BadRequest, jsonBody[ApiError])(invalidId),
      oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(recordMissing),
      oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
    )

  private val catalogEndpoint       = endpoint.errorOut(ApiError.generic)
  private val getComponentsEndpoint =
    catalogEndpoint.get.in("substrate" / "components").out(jsonBody[Vector[SubstrateComponent]]).summary("List substrate components")
  private val addComponentEndpoint =
    catalogEndpoint.post.in("substrate" / "components").in(jsonBody[SubstrateComponentData])
      .out(statusCode(StatusCode.Created)).out(jsonBody[SubstrateComponent]).summary("Add a substrate component")
  private val editComponentEndpoint =
    endpoint.put.in("substrate" / "components" / path[String]("componentId")).in(jsonBody[SubstrateComponentData])
      .errorOut(editErrors).out(jsonBody[SubstrateComponent]).summary("Edit a substrate component")

  private[http] val publicEndpoints: List[AnyEndpoint] =
    List(getComponentsEndpoint, addComponentEndpoint, editComponentEndpoint)

  def serverEndpoints(using catalog: SubstrateComponentCatalog): List[ServerEndpoint[Any, Identity]] =
    List(
      getComponentsEndpoint.handle: _ =>
        catalog.getSubstrateComponents match
          case CatalogReadResult.Read(components) => components.asRight
          case CatalogReadResult.ReadFailed(_)    => (StatusCode.InternalServerError, readFailed).asLeft,
      addComponentEndpoint.handle: data =>
        catalog.addSubstrateComponent(data) match
          case CatalogAddResult.Added(component) => component.asRight
          case CatalogAddResult.AddFailed(_)     => (StatusCode.InternalServerError, writeFailed).asLeft,
      editComponentEndpoint.handle: (encodedId, data) =>
        SubstrateComponentId.parse(encodedId).fold(invalidId.asLeft): id =>
          catalog.editSubstrateComponent(id, data) match
            case CatalogEditResult.Edited(component) => component.asRight
            case CatalogEditResult.RecordMissing     => recordMissing.asLeft
            case CatalogEditResult.EditFailed(_)     => writeFailed.asLeft
    )

  private given Configuration = Configuration.default.withTransformMemberNames:
    case "maybeInfo" => "info"
    case name        => name
  private given TapirConfiguration = TapirConfiguration.default.copy(toEncodedName = {
    case "maybeInfo" => "info"
    case name        => name
  })

  private given Codec[NomenclatureName]     = Codec.from(Decoder.decodeString.map(NomenclatureName.apply), Encoder.encodeString.contramap(_.value))
  private given Codec[NomenclatureInfo]     = Codec.from(Decoder.decodeString.map(NomenclatureInfo.apply), Encoder.encodeString.contramap(_.value))
  private given Codec[SubstrateComponentId] = Codec.from(
    // Component identifiers appear only in response bodies.
    // $COVERAGE-OFF$
    Decoder.decodeString.emap(value => SubstrateComponentId.parse(value).toRight(s"invalid substrate component id: $value")),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.value.toString)
  )
  private given Codec.AsObject[SubstrateComponentData] = ConfiguredCodec.derived
  private given Codec.AsObject[SubstrateComponent]     = ConfiguredCodec.derived

  private given Schema[SubstrateComponentId] = Schema.string
    // JSON bodies use Circe; Tapir does not invoke these identifier schema mappings at runtime.
    // $COVERAGE-OFF$
    .map(SubstrateComponentId.parse)(_.value.toString).format("uuid")
  // $COVERAGE-ON$
  private given Schema[NomenclatureName]       = Schema.string
  private given Schema[NomenclatureInfo]       = Schema.string
  private given Schema[SubstrateComponentData] = Schema.derived[SubstrateComponentData]
    .modify(_.maybeInfo)(_.copy(isOptional = false).nullable)
