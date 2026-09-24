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

  private val catalogEndpoint       = endpoint.errorOut(JournalError.generic)
  private val getComponentsEndpoint =
    catalogEndpoint.get.in("substrate" / "components").out(jsonBody[Vector[SubstrateComponent]]).summary("List substrate components")
  private val addComponentEndpoint =
    catalogEndpoint.post.in("substrate" / "components").in(jsonBody[SubstrateComponentData])
      .out(statusCode(StatusCode.Created)).out(jsonBody[SubstrateComponent]).summary("Add a substrate component")
  private val editComponentEndpoint =
    endpoint.put.in("substrate" / "components" / path[String]("componentId")).in(jsonBody[SubstrateComponentData])
      .errorOut(JournalError.catalogEdit).out(jsonBody[SubstrateComponent]).summary("Edit a substrate component")

  private[http] val publicEndpoints: List[AnyEndpoint] =
    List(getComponentsEndpoint, addComponentEndpoint, editComponentEndpoint)

  def serverEndpoints(using catalog: SubstrateComponentCatalog): List[ServerEndpoint[Any, Identity]] =
    List(
      getComponentsEndpoint.handle: _ =>
        catalog.getSubstrateComponents match
          case CatalogReadResult.Read(components) => components.asRight
          case CatalogReadResult.ReadFailed(_)    => (StatusCode.InternalServerError, JournalError.catalogReadFailed).asLeft,
      addComponentEndpoint.handle: data =>
        catalog.addSubstrateComponent(data) match
          case CatalogAddResult.Added(component) => component.asRight
          case CatalogAddResult.AddFailed(_)     => (StatusCode.InternalServerError, JournalError.catalogWriteFailed).asLeft,
      editComponentEndpoint.handle: (encodedId, data) =>
        SubstrateComponentId.parse(encodedId).fold(JournalError.catalogInvalidId.asLeft): id =>
          catalog.editSubstrateComponent(id, data) match
            case CatalogEditResult.Edited(component) => component.asRight
            case CatalogEditResult.RecordMissing     => JournalError.catalogRecordMissing.asLeft
            case CatalogEditResult.EditFailed(_)     => JournalError.catalogWriteFailed.asLeft
    )

  private given Configuration = Configuration.default.withTransformMemberNames:
    case "maybeInfo" => "info"
    case name        => name
  private given TapirConfiguration = TapirConfiguration.default.copy(toEncodedName = {
    case "maybeInfo" => "info"
    case name        => name
  })

  private given Codec[NomenclatureName] = Codec.from(
    Decoder.decodeString.map(NomenclatureName.apply),
    Encoder.encodeString.contramap(_.value)
  )
  private given Codec[NomenclatureInfo] = Codec.from(
    Decoder.decodeString.map(NomenclatureInfo.apply),
    Encoder.encodeString.contramap(_.value)
  )
  // Tapir requires a bidirectional codec for the output-only component identifier.
  // $COVERAGE-OFF$
  private given Codec[SubstrateComponentId] = Codec.from(
    Decoder.decodeString.emap(value => SubstrateComponentId.parse(value).toRight(s"invalid substrate component id: $value")),
    Encoder.encodeString.contramap(_.value.toString)
  )
  // $COVERAGE-ON$
  private given Codec.AsObject[SubstrateComponentData] = ConfiguredCodec.derived
  private given Codec.AsObject[SubstrateComponent]     = ConfiguredCodec.derived

  // OpenAPI generation reads the schema; the server never decodes component identifiers in bodies.
  // $COVERAGE-OFF$
  private given Schema[SubstrateComponentId] = Schema.string
    .map(SubstrateComponentId.parse)(_.value.toString)
    .format("uuid")
  // $COVERAGE-ON$
  private given Schema[NomenclatureName]       = Schema.string
  private given Schema[NomenclatureInfo]       = Schema.string
  private given Schema[SubstrateComponentData] = Schema.derived[SubstrateComponentData]
    .modify(_.maybeInfo)(_.copy(isOptional = false).nullable)
