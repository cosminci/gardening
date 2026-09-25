package gardening.adapters.http

import cats.syntax.either.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.substrate.{AddSubstrateMixResult, SubstrateCatalog}
import io.circe.derivation.{Configuration, ConfiguredCodec}
import io.circe.{Codec, Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval
import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.generic.Configuration as TapirConfiguration
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint

import java.util.UUID
import scala.util.Try

object SubstrateApi:

  private val componentMissing     = ApiError("substrate component not found")
  private val invalidComponentId   = ApiError("invalid substrate component id")
  private val componentsReadFailed = ApiError("substrate components could not be read")
  private val componentWriteFailed = ApiError("substrate component could not be saved")
  private val componentEditErrors  =
    oneOf[ApiError](
      oneOfVariantExactMatcher(StatusCode.BadRequest, jsonBody[ApiError])(invalidComponentId),
      oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(componentMissing),
      oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
    )

  private val mixesReadFailed    = ApiError("substrate mixes could not be read")
  private val mixWriteFailed     = ApiError("substrate mix could not be saved")
  private val duplicateSubstrate = ApiError("a substrate mix with these components already exists")
  private val invalidMixId       = ApiError("invalid substrate mix id")
  private val mixDeleteFailed    = ApiError("substrate mix could not be deleted")
  private val addMixErrors       =
    oneOf[ApiError](
      oneOfVariantExactMatcher(StatusCode.Conflict, jsonBody[ApiError])(duplicateSubstrate),
      oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
    )
  private val deleteMixErrors =
    oneOf[ApiError](
      oneOfVariantExactMatcher(StatusCode.BadRequest, jsonBody[ApiError])(invalidMixId),
      oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
    )

  private val catalogEndpoint       = endpoint.errorOut(ApiError.generic)
  private val getComponentsEndpoint =
    catalogEndpoint.get.in("substrates" / "components").out(jsonBody[Vector[SubstrateComponent]]).summary("List substrate components")
  private val addComponentEndpoint =
    catalogEndpoint.post.in("substrates" / "components").in(jsonBody[SubstrateComponentData])
      .out(statusCode(StatusCode.Created)).out(jsonBody[SubstrateComponent]).summary("Add a substrate component")
  private val editComponentEndpoint =
    endpoint.put.in("substrates" / "components" / path[String]("componentId")).in(jsonBody[SubstrateComponentData])
      .errorOut(componentEditErrors).out(jsonBody[SubstrateComponent]).summary("Edit a substrate component")

  private val getMixesEndpoint =
    catalogEndpoint.get.in("substrates" / "mixes").out(jsonBody[Vector[SubstrateMix]]).summary("List substrate mixes")
  private val addMixEndpoint =
    endpoint.post.in("substrates" / "mixes").in(jsonBody[SubstrateMixData])
      .errorOut(addMixErrors).out(statusCode(StatusCode.Created)).out(jsonBody[SubstrateMix]).summary("Save a substrate mix")
  private val deleteMixEndpoint =
    endpoint.delete.in("substrates" / "mixes" / path[String]("mixId"))
      .errorOut(deleteMixErrors).out(statusCode(StatusCode.NoContent)).summary("Delete a substrate mix")

  private[http] val publicEndpoints: List[AnyEndpoint] =
    List(getComponentsEndpoint, addComponentEndpoint, editComponentEndpoint, getMixesEndpoint, addMixEndpoint, deleteMixEndpoint)

  def serverEndpoints(using catalog: SubstrateCatalog): List[ServerEndpoint[Any, Identity]] =
    List(
      getComponentsEndpoint.handle: _ =>
        catalog.getSubstrateComponents match
          case CatalogReadResult.Read(components) => components.asRight
          case CatalogReadResult.ReadFailed(_)    => (StatusCode.InternalServerError, componentsReadFailed).asLeft,
      addComponentEndpoint.handle: data =>
        catalog.addSubstrateComponent(data) match
          case CatalogAddResult.Added(component) => component.asRight
          case CatalogAddResult.AddFailed(_)     => (StatusCode.InternalServerError, componentWriteFailed).asLeft,
      editComponentEndpoint.handle: (encodedId, data) =>
        SubstrateComponentId.parse(encodedId).fold(invalidComponentId.asLeft): id =>
          catalog.editSubstrateComponent(id, data) match
            case CatalogEditResult.Edited(component) => component.asRight
            case CatalogEditResult.RecordMissing     => componentMissing.asLeft
            case CatalogEditResult.EditFailed(_)     => componentWriteFailed.asLeft,
      getMixesEndpoint.handle: _ =>
        catalog.getSubstrateMixes match
          case CatalogReadResult.Read(mixes)   => mixes.asRight
          case CatalogReadResult.ReadFailed(_) => (StatusCode.InternalServerError, mixesReadFailed).asLeft,
      addMixEndpoint.handle: data =>
        catalog.addSubstrateMix(data.name, data.notes, data.substrate) match
          case AddSubstrateMixResult.Added(mix)         => mix.asRight
          case AddSubstrateMixResult.DuplicateSubstrate => duplicateSubstrate.asLeft
          case AddSubstrateMixResult.AddFailed(_)       => mixWriteFailed.asLeft,
      deleteMixEndpoint.handle: encodedId =>
        Try(UUID.fromString(encodedId)).toOption.fold(invalidMixId.asLeft): id =>
          catalog.deleteSubstrateMix(id) match
            case CatalogDeleteResult.Deleted         => ().asRight
            case CatalogDeleteResult.DeleteFailed(_) => mixDeleteFailed.asLeft
    )

  private case class SubstrateMixData(name: SubstrateMixName, notes: Option[SubstrateMixNotes], substrate: Substrate)

  private given Configuration = Configuration.default.withTransformMemberNames:
    case "maybeInfo" => "info"
    case name        => name
  private given TapirConfiguration = TapirConfiguration.default.copy(toEncodedName = {
    case "maybeInfo" => "info"
    case name        => name
  })

  private given Codec[SubstrateComponentName] =
    Codec.from(Decoder.decodeString.map(SubstrateComponentName.apply), Encoder.encodeString.contramap(_.value))
  private given Codec[SubstrateComponentInfo] =
    Codec.from(Decoder.decodeString.map(SubstrateComponentInfo.apply), Encoder.encodeString.contramap(_.value))
  private given Codec[SubstrateComponentId] = Codec.from(
    // Component identifiers appear only in response bodies.
    // $COVERAGE-OFF$
    Decoder.decodeString.emap(value => SubstrateComponentId.parse(value).toRight(s"invalid substrate component id: $value")),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.value.toString)
  )
  private given Codec.AsObject[SubstrateComponentData] = ConfiguredCodec.derived
  private given Codec.AsObject[SubstrateComponent]     = ConfiguredCodec.derived

  private given Codec[Percentage] = Codec.from(
    Decoder.decodeInt.emap(value => value.refineOption[Interval.Closed[1, 100]].toRight(s"invalid share: $value")),
    Encoder.encodeInt.contramap(value => value: Int)
  )
  private given Codec[SubstrateMixName]       = Codec.from(Decoder.decodeString.map(SubstrateMixName.apply), Encoder.encodeString.contramap(_.value))
  private given Codec[SubstrateMixNotes]      = Codec.from(Decoder.decodeString.map(SubstrateMixNotes.apply), Encoder.encodeString.contramap(_.value))
  private given Codec.AsObject[SubstratePart] = ConfiguredCodec.derived
  private given Codec[Substrate]              = Codec.from(
    Decoder.decodeList[SubstratePart].emap(parts => Substrate.of(parts).left.map(_.toString)),
    Encoder.encodeList[SubstratePart].contramap(_.parts)
  )
  private given Codec.AsObject[SubstrateMixData] = ConfiguredCodec.derived
  private given Codec.AsObject[SubstrateMix]     = ConfiguredCodec.derived

  private given Schema[SubstrateComponentId] = Schema.string
    // JSON bodies use Circe; Tapir does not invoke these identifier schema mappings at runtime.
    // $COVERAGE-OFF$
    .map(SubstrateComponentId.parse)(_.value.toString).format("uuid")
  // $COVERAGE-ON$
  private given Schema[SubstrateComponentName] = Schema.string
  private given Schema[SubstrateComponentInfo] = Schema.string
  private given Schema[SubstrateComponentData] = Schema.derived[SubstrateComponentData]
    .modify(_.maybeInfo)(_.copy(isOptional = false).nullable)

  private given Schema[Percentage] = Schema.schemaForInt
    .validate(Validator.min(1).and(Validator.max(100)))
    // JSON bodies use Circe rather than this percentage schema's inverse mapping.
    // $COVERAGE-OFF$
    .map(_.refineOption[Interval.Closed[1, 100]])(value => value)
  // $COVERAGE-ON$
  private given Schema[SubstrateMixName]  = Schema.string
  private given Schema[SubstrateMixNotes] = Schema.string
  private given Schema[SubstratePart]     = Schema.derived[SubstratePart]
  private given Schema[Substrate]         = summon[Schema[List[SubstratePart]]]
    .validate(Validator.minSize(1))
    // JSON bodies use Circe rather than this substrate schema's inverse mapping.
    // $COVERAGE-OFF$
    .map(parts => Substrate.of(parts).toOption)(_.parts)
  // $COVERAGE-ON$
  private given Schema[SubstrateMixData] = Schema.derived[SubstrateMixData]
    .modify(_.notes)(_.copy(isOptional = false).nullable)
  private given Schema[SubstrateMix] = Schema.derived[SubstrateMix]
    .modify(_.notes)(_.copy(isOptional = false).nullable)
