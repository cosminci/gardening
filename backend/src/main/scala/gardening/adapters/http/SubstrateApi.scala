package gardening.adapters.http

import cats.syntax.either.*
import gardening.domain.*
import gardening.domain.substrate.{AddSubstrateComponentResult, DeleteSubstrateMixResult, GetSubstrateComponentsResult, GetSubstrateMixesResult, SubstrateComponentUpdateResult}
import gardening.usecases.{AddSubstrateMixResult, SubstrateCatalog}
import io.circe.derivation.{Configuration, ConfiguredCodec, ConfiguredEnumCodec}
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
  private val componentArchived    = ApiError("substrate component is archived")
  private val alreadyArchived      = ApiError("substrate component is already archived")
  private val archiveFailed        = ApiError("substrate component could not be archived")
  private val componentEditErrors  = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.BadRequest, jsonBody[ApiError])(invalidComponentId),
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(componentMissing),
    oneOfVariantExactMatcher(StatusCode.Conflict, jsonBody[ApiError])(componentArchived),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )
  private val componentArchiveErrors = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.BadRequest, jsonBody[ApiError])(invalidComponentId),
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(componentMissing),
    oneOfVariantExactMatcher(StatusCode.Conflict, jsonBody[ApiError])(alreadyArchived),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )

  private val mixesReadFailed    = ApiError("substrate mixes could not be read")
  private val mixWriteFailed     = ApiError("substrate mix could not be saved")
  private val duplicateSubstrate = ApiError("a substrate mix with these components already exists")
  private val invalidMixId       = ApiError("invalid substrate mix id")
  private val mixDeleteFailed    = ApiError("substrate mix could not be deleted")
  private val addMixErrors       = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.Conflict, jsonBody[ApiError])(duplicateSubstrate),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )
  private val deleteMixErrors = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.BadRequest, jsonBody[ApiError])(invalidMixId),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )

  private val catalogEndpoint = endpoint.errorOut(ApiError.generic)
  private val getCompEndpoint = catalogEndpoint.get.in("substrates" / "components")
    .out(jsonBody[Vector[SubstrateComponent]]).summary("List substrate components")
  private val addCompEndpoint = catalogEndpoint.post.in("substrates" / "components").in(jsonBody[SubstrateComponentData])
    .out(statusCode(StatusCode.Created)).out(jsonBody[SubstrateComponent]).summary("Add a substrate component")
  private val editCompEndpoint = endpoint.put.in("substrates" / "components" / path[String]("componentId")).in(jsonBody[SubstrateComponentData])
    .errorOut(componentEditErrors).out(jsonBody[SubstrateComponent]).summary("Edit a substrate component")
  private val archiveCompEndpoint = endpoint.post.in("substrates" / "components" / path[String]("componentId") / "archive")
    .errorOut(componentArchiveErrors).out(jsonBody[SubstrateComponent]).summary("Archive a substrate component")

  private val getMixesEndpoint = catalogEndpoint.get.in("substrates" / "mixes").out(jsonBody[Vector[SubstrateMix]]).summary("List substrate mixes")
  private val addMixEndpoint   = endpoint.post.in("substrates" / "mixes").in(jsonBody[SubstrateMixData])
    .errorOut(addMixErrors).out(statusCode(StatusCode.Created)).out(jsonBody[SubstrateMix]).summary("Save a substrate mix")
  private val deleteMixEndpoint = endpoint.delete.in("substrates" / "mixes" / path[String]("mixId"))
    .errorOut(deleteMixErrors).out(statusCode(StatusCode.NoContent)).summary("Delete a substrate mix")

  private[http] val publicEndpoints: List[AnyEndpoint] =
    List(getCompEndpoint, addCompEndpoint, editCompEndpoint, archiveCompEndpoint, getMixesEndpoint, addMixEndpoint, deleteMixEndpoint)

  def serverEndpoints(using catalog: SubstrateCatalog): List[ServerEndpoint[Any, Identity]] =
    List(
      getCompEndpoint.handle: _ =>
        catalog.getSubstrateComponents match
          case GetSubstrateComponentsResult.Read(components) => components.asRight
          case _: GetSubstrateComponentsResult.ReadFailed    => (StatusCode.InternalServerError, componentsReadFailed).asLeft,
      addCompEndpoint.handle: data =>
        catalog.addSubstrateComponent(data) match
          case AddSubstrateComponentResult.Added(component) => component.asRight
          case _: AddSubstrateComponentResult.AddFailed     => (StatusCode.InternalServerError, componentWriteFailed).asLeft,
      editCompEndpoint.handle: (encodedId, data) =>
        SubstrateComponentId.parse(encodedId).fold(invalidComponentId.asLeft): id =>
          catalog.editSubstrateComponent(id, data) match
            case SubstrateComponentUpdateResult.Updated(component) => component.asRight
            case SubstrateComponentUpdateResult.ComponentMissing   => componentMissing.asLeft
            case SubstrateComponentUpdateResult.ComponentArchived  => componentArchived.asLeft
            case _: SubstrateComponentUpdateResult.UpdateFailed    => componentWriteFailed.asLeft,
      archiveCompEndpoint.handle: encodedId =>
        SubstrateComponentId.parse(encodedId).fold(invalidComponentId.asLeft): id =>
          catalog.archiveSubstrateComponent(id) match
            case SubstrateComponentUpdateResult.Updated(component) => component.asRight
            case SubstrateComponentUpdateResult.ComponentMissing   => componentMissing.asLeft
            case SubstrateComponentUpdateResult.ComponentArchived  => alreadyArchived.asLeft
            case _: SubstrateComponentUpdateResult.UpdateFailed    => archiveFailed.asLeft,
      getMixesEndpoint.handle: _ =>
        catalog.getSubstrateMixes match
          case GetSubstrateMixesResult.Read(mixes)   => mixes.asRight
          case _: GetSubstrateMixesResult.ReadFailed => (StatusCode.InternalServerError, mixesReadFailed).asLeft,
      addMixEndpoint.handle: data =>
        catalog.addSubstrateMix(data.name, data.maybeNotes, data.substrate) match
          case AddSubstrateMixResult.Added(mix)         => mix.asRight
          case AddSubstrateMixResult.DuplicateSubstrate => duplicateSubstrate.asLeft
          case _: AddSubstrateMixResult.AddFailed       => mixWriteFailed.asLeft,
      deleteMixEndpoint.handle: encodedId =>
        Try(UUID.fromString(encodedId)).toOption.fold(invalidMixId.asLeft): id =>
          catalog.deleteSubstrateMix(id) match
            case DeleteSubstrateMixResult.Deleted         => ().asRight
            case _: DeleteSubstrateMixResult.DeleteFailed => mixDeleteFailed.asLeft
    )

  private case class SubstrateMixData(name: SubstrateMixName, maybeNotes: Option[SubstrateMixNotes], substrate: Substrate)

  private given Configuration = Configuration.default
    .withTransformMemberNames {
      case "maybeInfo"  => "info"
      case "maybeNotes" => "notes"
      case name         => name
    }
    .withTransformConstructorNames(lowerCamel)
  private given TapirConfiguration = TapirConfiguration.default.copy(toEncodedName = {
    case "maybeInfo"  => "info"
    case "maybeNotes" => "notes"
    case name         => name
  })

  private given Codec[SubstrateComponentName] =
    Codec.from(Decoder.decodeString.map(SubstrateComponentName.apply), Encoder.encodeString.contramap(_.value))
  private given Codec[SubstrateComponentInfo] =
    Codec.from(Decoder.decodeString.map(SubstrateComponentInfo.apply), Encoder.encodeString.contramap(_.value))
  private given Codec[SubstrateComponentId] = Codec.from(
    // No endpoint accepts a SubstrateComponentId in a request body, so this Decoder branch is never invoked.
    // $COVERAGE-OFF$
    Decoder.decodeString.emap(value => SubstrateComponentId.parse(value).toRight(s"invalid substrate component id: $value")),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.value.toString)
  )
  private given Codec[SubstrateComponentStatus]        = ConfiguredEnumCodec.derived
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
  // JSON bodies use Circe; Tapir does not invoke these enum schema mappings at runtime.
  // $COVERAGE-OFF$
  private given Schema[SubstrateComponentStatus] =
    Schema.derivedEnumeration[SubstrateComponentStatus].apply(encode = Some(value => lowerCamel(value.productPrefix)))
  // $COVERAGE-ON$
  private given Schema[SubstrateComponentData] = Schema.derived[SubstrateComponentData]
    .modify(_.maybeInfo)(_.copy(isOptional = false).nullable)

  // value => value is identity (nothing to verify); the forward function only re-derives a Schema
  // `.default` value for docs, which none of these schemas set, so it's unreachable either way.
  // $COVERAGE-OFF$
  private given Schema[Percentage] = Schema.schemaForInt
    .validate(Validator.min(1).and(Validator.max(100)))
    .map(_.refineOption[Interval.Closed[1, 100]])(value => value)
  // $COVERAGE-ON$
  private given Schema[SubstrateMixName]  = Schema.string
  private given Schema[SubstrateMixNotes] = Schema.string
  private given Schema[SubstratePart]     = Schema.derived[SubstratePart]
  private given Schema[Substrate]         = summon[Schema[List[SubstratePart]]]
    .validate(Validator.minSize(1))
    .map(
      // Only re-derives a Schema `.default` value for docs; none of these schemas set one, so this is unreachable.
      // $COVERAGE-OFF$
      parts =>
        Substrate.of(parts).toOption
        // $COVERAGE-ON$
    )(_.parts) // tapir replays the attached Validator against this on every decode, after Circe parses the value
  private given Schema[SubstrateMixData] = Schema.derived[SubstrateMixData]
    .modify(_.maybeNotes)(_.copy(isOptional = false).nullable)
    .modify(_.substrate)(_.copy(isOptional = false))
  private given Schema[SubstrateMix] = Schema.derived[SubstrateMix]
    .modify(_.maybeNotes)(_.copy(isOptional = false).nullable)
    .modify(_.substrate)(_.copy(isOptional = false))

  private def lowerCamel(name: String) =
    name.substring(0, 1).toLowerCase + name.substring(1)
