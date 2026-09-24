package gardening.adapters.http

import cats.syntax.either.*
import gardening.domain.*
import gardening.domain.attention.PlantAttentionMonitor
import gardening.domain.journal.*
import io.circe.derivation.{Configuration as CirceConfiguration, ConfiguredCodec, ConfiguredEnumCodec}
import io.circe.{Codec, Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.{GreaterEqual, Interval}
import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.Codec as TapirCodec
import sttp.tapir.generic.Configuration as TapirConfiguration
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import java.time.Instant
import scala.deriving.Mirror
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps

final private case class LoggedOperation(id: String) derives Codec.AsObject
final private case class ArchivedPlantCount(count: Long) derives Codec.AsObject

object JournalApi:

  final private case class LogOperationRequest(date: Instant, details: OperationDetails)

  private val journalEndpoint   = endpoint.errorOut(JournalError.generic)
  private val getPlantsEndpoint =
    journalEndpoint.get.in(
      "plants"
    ).in(query[PlantStatus]("status").default(PlantStatus.Active)).out(jsonBody[Vector[Plant]]).summary("List plants by status")
  private val getArchivedCountEndpoint =
    journalEndpoint.get.in("plants" / "archived" / "count").out(jsonBody[ArchivedPlantCount]).summary("Count archived plants")
  private val archivePlantEndpoint =
    endpoint.post.in("plants" / path[String]("plantId") / "archive").errorOut(JournalError.plantRequest)
      .out(statusCode(StatusCode.NoContent)).summary("Permanently archive an active plant")
  private val getOperationsEndpoint =
    journalEndpoint.get
      .in("plants" / path[String]("plantId") / "operations")
      .in(query[OperationOffset]("offset").default(0))
      .in(query[OperationPageSize]("pageSize").default(3))
      .out(jsonBody[OperationPage])
      .summary("List a bounded page of plant operations")

  private val getOperationDateRangeEndpoint =
    endpoint.get.in("plants" / path[String]("plantId") / "operation-date-range")
      .errorOut(JournalError.plantRead)
      .out(jsonBody[OperationDateRange])
      .summary("Read the first and last recorded operation dates")

  private val logOperationEndpoint =
    endpoint.post.in("plants" / path[String]("plantId") / "operations").in(jsonBody[LogOperationRequest])
      .errorOut(JournalError.plantRequest)
      .out(statusCode(StatusCode.Created)).out(jsonBody[LoggedOperation]).summary("Log a plant operation")

  private val editOperationEndpoint =
    endpoint.put.in("operations" / path[String]("operationId")).in(jsonBody[OperationDetails])
      .errorOut(JournalError.edit)
      .out(jsonBody[Operation]).summary("Edit a plant operation")

  private[http] val plantEndpoints: List[AnyEndpoint] =
    List(getPlantsEndpoint, getArchivedCountEndpoint, archivePlantEndpoint)
  private[http] val operationEndpoints: List[AnyEndpoint] =
    List(getOperationsEndpoint, getOperationDateRangeEndpoint, logOperationEndpoint, editOperationEndpoint)

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
      archivePlantEndpoint.handle: plantId =>
        val id = PlantId(plantId)
        journal.archivePlant(id) match
          case ArchivePlantResult.Archived         => attention.removeArchivedPlant(id).pipe(_.asRight)
          case ArchivePlantResult.PlantMissing     => JournalError.plantMissing.asLeft
          case ArchivePlantResult.AlreadyArchived  => JournalError.plantArchived.asLeft
          case ArchivePlantResult.ArchiveFailed(_) => ApiError("plant could not be archived").asLeft,
      getOperationsEndpoint.handle: (plantId, offset, pageSize) =>
        journal.getOperations(PlantId(plantId), OperationWindow(offset, pageSize)) match
          case GetOperationsResult.Read(page)    => page.asRight
          case GetOperationsResult.ReadFailed(_) => (StatusCode.InternalServerError, ApiError("journal could not be read")).asLeft,
      getOperationDateRangeEndpoint.handle: plantId =>
        journal.getOperationDateRange(PlantId(plantId)) match
          case GetOperationDateRangeResult.Read(range)   => range.asRight
          case GetOperationDateRangeResult.PlantMissing  => JournalError.plantMissing.asLeft
          case GetOperationDateRangeResult.ReadFailed(_) => ApiError("operation dates could not be read").asLeft,
      logOperationEndpoint.handle: (plantId, request) =>
        journal.logOperation(PlantId(plantId), request.date, request.details) match
          case LogOperationResult.Logged(id)       => LoggedOperation(id.value).asRight
          case LogOperationResult.PlantMissing     => JournalError.plantMissing.asLeft
          case LogOperationResult.PlantArchived    => JournalError.plantArchived.asLeft
          case LogOperationResult.LoggingFailed(_) => ApiError("operation could not be logged").asLeft,
      editOperationEndpoint.handle: (operationId, details) =>
        journal.editOperation(OperationId(operationId), details) match
          case EditOperationResult.Edited(operation)     => operation.asRight
          case EditOperationResult.OperationMissing      => JournalError.operationMissing.asLeft
          case EditOperationResult.OperationTypeMismatch => JournalError.operationTypeMismatch.asLeft
          case EditOperationResult.EditFailed(_)         => JournalError.editFailed.asLeft
    )

  private given circeConfiguration: CirceConfiguration =
    CirceConfiguration.default
      .withTransformMemberNames(encodedFieldName)
      .withTransformConstructorNames(lowerCamel)
      .withDiscriminator("kind")
  private given tapirConfiguration: TapirConfiguration =
    TapirConfiguration.default.copy(
      toEncodedName = encodedFieldName,
      discriminator = Some("kind"),
      toDiscriminatorValue = name => lowerCamel(name.fullName.split('.').last.stripSuffix("$"))
    )

  private lazy val operationOffsetSchema = Schema.schemaForInt
    .validate(Validator.min(0))
    // Tapir validates query offsets with the plain codec, not this schema's inverse mapping.
    // $COVERAGE-OFF$
    .map(_.refineOption[GreaterEqual[0]])(value => value)
  // $COVERAGE-ON$
  private lazy val operationPageSizeSchema = Schema.schemaForInt
    .validate(Validator.min(1).and(Validator.max(10)))
    // Tapir validates query page sizes with the plain codec, not this schema's inverse mapping.
    // $COVERAGE-OFF$
    .map(_.refineOption[Interval.Closed[1, 10]])(value => value)
  // $COVERAGE-ON$

  private given TapirCodec.PlainCodec[OperationOffset] = TapirCodec.int
    .mapDecode(value =>
      value.refineOption[GreaterEqual[0]] match
        case Some(offset) => DecodeResult.Value(offset)
        case None         => DecodeResult.Error(value.toString, IllegalArgumentException("offset must be at least 0"))
    )(value => value)
    .schema(operationOffsetSchema)

  private given TapirCodec.PlainCodec[OperationPageSize] = TapirCodec.int
    .mapDecode(value =>
      value.refineOption[Interval.Closed[1, 10]] match
        case Some(size) => DecodeResult.Value(size)
        case None       => DecodeResult.Error(value.toString, IllegalArgumentException("page size must be between 1 and 10"))
    )(value => value)
    .schema(operationPageSizeSchema)

  private given TapirCodec.PlainCodec[PlantStatus] = TapirCodec.string
    .mapDecode(value =>
      PlantStatus.values.find(status => lowerCamel(status.toString).equals(value)) match
        case Some(status) => DecodeResult.Value(status)
        case None         => DecodeResult.Error(value, IllegalArgumentException(s"invalid plant status: $value"))
    )(status => lowerCamel(status.toString))
    .schema(enumSchema[PlantStatus])

  private given Codec[PlantId] = Codec.from(
    // Plant identifiers are output-only in JSON bodies.
    // $COVERAGE-OFF$
    Decoder.decodeString.map(PlantId.apply),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.value)
  )
  private given Codec[OperationId] = Codec.from(
    // Operation identifiers are output-only in JSON bodies.
    // $COVERAGE-OFF$
    Decoder.decodeString.map(OperationId.apply),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.value)
  )
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
  private given Codec[Instant] =
    Codec.from(Decoder.decodeString.emapTry(value => Try(Instant.parse(value))), Encoder.encodeString.contramap(_.toString))
  private given Codec[Note]                 = stringCodec(Note.apply, _.value)
  private given Codec[SubstrateComponentId] = Codec.from(
    Decoder.decodeString.emap(value => SubstrateComponentId.parse(value).toRight(s"invalid substrate component id: $value")),
    Encoder.encodeString.contramap(_.value.toString)
  )
  private given Codec[PesticideId] = Codec.from(
    Decoder.decodeString.emap(value => PesticideId.parse(value).toRight(s"invalid pesticide id: $value")),
    Encoder.encodeString.contramap(_.value.toString)
  )
  private given Codec[Percentage] = Codec.from(
    Decoder.decodeInt.emap(value => value.refineOption[Interval.Closed[1, 100]].toRight(s"invalid share: $value")),
    Encoder.encodeInt.contramap(value => value)
  )
  private type JournalEnum = ActionType | MoistureLevel | PlantStatus
  private inline given [A <: JournalEnum](using Mirror.SumOf[A]): Codec[A] = ConfiguredEnumCodec.derived
  private given Encoder[Set[ActionType]]                                   = Encoder.encodeList[ActionType].contramap(_.toList.sortBy(_.toString))
  private inline given productCodec[A](using Mirror.ProductOf[A]): Codec.AsObject[A] = ConfiguredCodec.derived
  private given Codec[Substrate]                                                     = Codec.from(
    Decoder.decodeList[SubstratePart].emap(parts => Substrate.of(parts).left.map(_.toString)),
    Encoder.encodeList[SubstratePart].contramap(_.parts)
  )
  private given Codec.AsObject[OperationDetails]    = ConfiguredCodec.derived
  private given Codec.AsObject[OperationDateRange]  = ConfiguredCodec.derived
  private given Codec.AsObject[LogOperationRequest] = ConfiguredCodec.derived
  private given Codec.AsObject[PlantDetails]        = ConfiguredCodec.derived
  private given Codec.AsObject[Plant]               = ConfiguredCodec.derived

  private type WireText = PlantId | OperationId | Species | Nickname | Location | Note
  private given [A <: WireText]: Schema[A] = Schema.string
  // JSON bodies use Circe; Tapir does not invoke these identifier schema mappings at runtime.
  // $COVERAGE-OFF$
  private given Schema[SubstrateComponentId] = Schema.string.map(SubstrateComponentId.parse)(_.value.toString).format("uuid")
  private given Schema[PesticideId]          = Schema.string.map(PesticideId.parse)(_.value.toString).format("uuid")
  // $COVERAGE-ON$
  private given Schema[Percentage] = Schema.schemaForInt
    .validate(Validator.min(1).and(Validator.max(100)))
    // JSON bodies use Circe rather than this percentage schema's inverse mapping.
    // $COVERAGE-OFF$
    .map(_.refineOption[Interval.Closed[1, 100]])(value => value)
  // $COVERAGE-ON$
  private given Schema[Substrate] = summon[Schema[List[SubstratePart]]]
    .validate(Validator.minSize(1))
    // JSON bodies use Circe rather than this substrate schema's inverse mapping.
    // $COVERAGE-OFF$
    .map(parts => Substrate.of(parts).toOption)(_.parts)
  // $COVERAGE-ON$
  private inline given [A <: JournalEnum & Product](using Mirror.SumOf[A]): Schema[A] = enumSchema[A]
  private given Schema[PlantDetails]                                                  = Schema
    .derived[PlantDetails]
    .modify(_.maybeNickname)(_.copy(isOptional = false).nullable)
    .modify(_.substrate)(_.copy(isOptional = false))
  private given Schema[OperationDetails.Care] = Schema
    .derived[OperationDetails.Care]
    .modify(_.actions)(_.copy(isOptional = false))
    .modify(_.pesticides)(_.copy(isOptional = false))
    .modify(_.maybeNote)(_.copy(isOptional = false).nullable)
  private given Schema[OperationDetails.Repot] = Schema
    .derived[OperationDetails.Repot]
    .modify(_.substrate)(_.copy(isOptional = false))
    .modify(_.maybeNote)(_.copy(isOptional = false).nullable)
  private given Schema[OperationPage] = Schema
    .derived[OperationPage]
    .modify(_.operations)(_.copy(isOptional = false))
  private given Schema[LogOperationRequest] = Schema.derived

  private def stringCodec[A](decode: String => A, encode: A => String) =
    Codec.from(Decoder.decodeString.map(decode), Encoder.encodeString.contramap(encode))

  private inline def enumSchema[A <: Product] =
    Schema.derivedEnumeration[A].apply(encode = Some(value => lowerCamel(value.productPrefix)))

  private def encodedFieldName(name: String) =
    name match
      case "maybeNickname" => "nickname"
      case "maybeNote"     => "notes"
      case _               => name

  private def lowerCamel(name: String) =
    name.substring(0, 1).toLowerCase + name.substring(1)
