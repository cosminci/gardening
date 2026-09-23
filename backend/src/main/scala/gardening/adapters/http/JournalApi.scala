package gardening.adapters.http

import cats.syntax.either.*
import gardening.domain.*
import gardening.domain.attention.*
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
import java.time.{Duration, Instant}
import scala.deriving.Mirror
import scala.util.Try

final private case class LoggedOperation(id: String) derives Codec.AsObject

final private case class AttentionProjectionResponse(measuredAt: Instant, plants: Vector[PlantAttentionResponse])
final private case class PlantAttentionResponse(
    plant: Plant,
    sampleCount: Int,
    cadenceAvailable: Boolean,
    averageInterval: Option[String],
    elapsed: Option[String],
    urgency: Option[UrgencyResponse],
    state: Option[WateringState]
)
final private case class UrgencyResponse(unbounded: Boolean, numeratorNanos: Option[String], denominatorNanos: Option[String])

object JournalApi:

  private val journalEndpoint   = endpoint.errorOut(JournalError.generic)
  private val getPlantsEndpoint =
    journalEndpoint.get.in("plants").out(jsonBody[Vector[Plant]]).summary("List active plants")

  private val getAttentionEndpoint =
    endpoint.get.in("attention").out(jsonBody[AttentionProjectionResponse]).summary("Read plant attention")

  private val getOperationsEndpoint =
    journalEndpoint.get
      .in("plants" / path[String]("plantId") / "operations")
      .in(query[OperationOffset]("offset").default(0))
      .in(query[OperationPageSize]("pageSize").default(3))
      .out(jsonBody[OperationPage])
      .summary("List a bounded page of plant operations")

  private val logOperationEndpoint =
    journalEndpoint.post.in("plants" / path[String]("plantId") / "operations").in(jsonBody[OperationDetails])
      .out(statusCode(StatusCode.Created)).out(jsonBody[LoggedOperation]).summary("Log a plant operation")

  private val editOperationEndpoint =
    endpoint.put.in("operations" / path[String]("operationId")).in(jsonBody[OperationDetails])
      .errorOut(JournalError.edit)
      .out(jsonBody[Operation]).summary("Edit a plant operation")

  private val getComponentsEndpoint =
    journalEndpoint.get.in("substrate-components").out(jsonBody[Vector[SubstrateComponent]]).summary("List substrate components")
  private val addComponentEndpoint =
    journalEndpoint.post.in("substrate-components").in(jsonBody[SubstrateComponentData])
      .out(statusCode(StatusCode.Created)).out(jsonBody[SubstrateComponent]).summary("Add a substrate component")
  private val editComponentEndpoint =
    endpoint.put.in("substrate-components" / path[String]("componentId")).in(jsonBody[SubstrateComponentData])
      .errorOut(JournalError.catalogEdit).out(jsonBody[SubstrateComponent]).summary("Edit a substrate component")

  private val getPesticidesEndpoint =
    journalEndpoint.get.in("pesticides").out(jsonBody[Vector[Pesticide]]).summary("List pesticides")
  private val addPesticideEndpoint =
    journalEndpoint.post.in("pesticides").in(jsonBody[PesticideData])
      .out(statusCode(StatusCode.Created)).out(jsonBody[Pesticide]).summary("Add a pesticide")
  private val editPesticideEndpoint =
    endpoint.put.in("pesticides" / path[String]("pesticideId")).in(jsonBody[PesticideData])
      .errorOut(JournalError.catalogEdit).out(jsonBody[Pesticide]).summary("Edit a pesticide")

  private[http] val publicEndpoints: List[AnyEndpoint] =
    List(
      getPlantsEndpoint,
      getAttentionEndpoint,
      getOperationsEndpoint,
      logOperationEndpoint,
      editOperationEndpoint,
      getComponentsEndpoint,
      addComponentEndpoint,
      editComponentEndpoint,
      getPesticidesEndpoint,
      addPesticideEndpoint,
      editPesticideEndpoint
    )

  def serverEndpoints(using journal: PlantJournal, attention: PlantAttentionMonitor): List[ServerEndpoint[Any, Identity]] =
    List(
      getPlantsEndpoint.handle: _ =>
        journal.getPlants match
          case GetPlantsResult.Read(plants)  => plants.asRight
          case GetPlantsResult.ReadFailed(_) =>
            (StatusCode.InternalServerError, ApiError("journal could not be read")).asLeft,
      getAttentionEndpoint.handleSuccess(_ => toResponse(attention.current)),
      getOperationsEndpoint.handle: (plantId, offset, pageSize) =>
        journal.getOperations(PlantId(plantId), OperationWindow(offset, pageSize)) match
          case GetOperationsResult.Read(page)    => page.asRight
          case GetOperationsResult.ReadFailed(_) =>
            (StatusCode.InternalServerError, ApiError("journal could not be read")).asLeft,
      logOperationEndpoint.handle: (plantId, details) =>
        journal.logOperation(PlantId(plantId), details) match
          case LogOperationResult.Logged(id) =>
            LoggedOperation(id.value).asRight
          case LogOperationResult.LoggingFailed(_) =>
            (StatusCode.InternalServerError, ApiError("operation could not be logged")).asLeft,
      editOperationEndpoint.handle: (operationId, details) =>
        journal.editOperation(OperationId(operationId), details) match
          case EditOperationResult.Edited(operation) =>
            operation.asRight
          case EditOperationResult.OperationMissing =>
            JournalError.operationMissing.asLeft
          case EditOperationResult.OperationTypeMismatch =>
            JournalError.operationTypeMismatch.asLeft
          case EditOperationResult.EditFailed(_) =>
            JournalError.editFailed.asLeft,
      getComponentsEndpoint.handle: _ =>
        journal.getSubstrateComponents match
          case CatalogReadResult.Read(components) => components.asRight
          case CatalogReadResult.ReadFailed(_)    =>
            (StatusCode.InternalServerError, JournalError.catalogReadFailed).asLeft,
      addComponentEndpoint.handle: data =>
        journal.addSubstrateComponent(data) match
          case CatalogAddResult.Added(component) => component.asRight
          case CatalogAddResult.AddFailed(_)     =>
            (StatusCode.InternalServerError, JournalError.catalogWriteFailed).asLeft,
      editComponentEndpoint.handle: (encodedId, data) =>
        SubstrateComponentId.parse(encodedId) match
          case None     => JournalError.catalogInvalidId.asLeft
          case Some(id) =>
            journal.editSubstrateComponent(id, data) match
              case CatalogEditResult.Edited(component) => component.asRight
              case CatalogEditResult.RecordMissing     => JournalError.catalogRecordMissing.asLeft
              case CatalogEditResult.EditFailed(_)     => JournalError.catalogWriteFailed.asLeft,
      getPesticidesEndpoint.handle: _ =>
        journal.getPesticides match
          case CatalogReadResult.Read(pesticides) => pesticides.asRight
          case CatalogReadResult.ReadFailed(_)    =>
            (StatusCode.InternalServerError, JournalError.catalogReadFailed).asLeft,
      addPesticideEndpoint.handle: data =>
        journal.addPesticide(data) match
          case CatalogAddResult.Added(pesticide) => pesticide.asRight
          case CatalogAddResult.AddFailed(_)     =>
            (StatusCode.InternalServerError, JournalError.catalogWriteFailed).asLeft,
      editPesticideEndpoint.handle: (encodedId, data) =>
        PesticideId.parse(encodedId) match
          case None     => JournalError.catalogInvalidId.asLeft
          case Some(id) =>
            journal.editPesticide(id, data) match
              case CatalogEditResult.Edited(pesticide) => pesticide.asRight
              case CatalogEditResult.RecordMissing     => JournalError.catalogRecordMissing.asLeft
              case CatalogEditResult.EditFailed(_)     => JournalError.catalogWriteFailed.asLeft
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

  // Tapir only reads these inverse mappings while generating the OpenAPI contract.
  // $COVERAGE-OFF$
  private lazy val operationOffsetSchema = Schema.schemaForInt
    .validate(Validator.min(0))
    .map(_.refineOption[GreaterEqual[0]])(value => value)
  private lazy val operationPageSizeSchema = Schema.schemaForInt
    .validate(Validator.min(1).and(Validator.max(10)))
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

  // Tapir requires bidirectional codecs for output bodies even though these values are never decoded by the server.
  // $COVERAGE-OFF$
  private given Codec[PlantId]     = stringCodec(PlantId.apply, _.value)
  private given Codec[OperationId] = stringCodec(OperationId.apply, _.value)
  private given Codec[Species]     = stringCodec(Species.apply, _.value)
  private given Codec[Nickname]    = stringCodec(Nickname.apply, _.value)
  private given Codec[Location]    = stringCodec(Location.apply, _.value)
  private given Codec[Instant]     =
    Codec.from(Decoder.decodeString.emapTry(value => Try(Instant.parse(value))), Encoder.encodeString.contramap(_.toString))
  // $COVERAGE-ON$
  private given Codec[Note]                 = stringCodec(Note.apply, _.value)
  private given Codec[NomenclatureName]     = stringCodec(NomenclatureName.apply, _.value)
  private given Codec[NomenclatureInfo]     = stringCodec(NomenclatureInfo.apply, _.value)
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
  private type JournalEnum = ActionType | MoistureLevel | PesticideType | PlantStatus | WateringState
  private inline given [A <: JournalEnum](using Mirror.SumOf[A]): Codec[A] = ConfiguredEnumCodec.derived
  private given Encoder[Set[ActionType]]                                   = Encoder.encodeList[ActionType].contramap(_.toList.sortBy(_.toString))
  private inline given productCodec[A](using Mirror.ProductOf[A]): Codec.AsObject[A] = ConfiguredCodec.derived
  private given Codec[Substrate]                                                     = Codec.from(
    Decoder.decodeList[SubstratePart].emap(parts => Substrate.of(parts).left.map(_.toString)),
    Encoder.encodeList[SubstratePart].contramap(_.parts)
  )
  private given Codec.AsObject[OperationDetails]            = ConfiguredCodec.derived
  private given Codec.AsObject[PlantDetails]                = ConfiguredCodec.derived
  private given Codec.AsObject[Plant]                       = ConfiguredCodec.derived
  private given Codec.AsObject[UrgencyResponse]             = ConfiguredCodec.derived
  private given Codec.AsObject[PlantAttentionResponse]      = ConfiguredCodec.derived
  private given Codec.AsObject[AttentionProjectionResponse] = ConfiguredCodec.derived

  private def toResponse(projection: AttentionProjection): AttentionProjectionResponse =
    AttentionProjectionResponse(
      projection.measuredAt,
      projection.plants.map(attentionResponse)
    )

  private def attentionResponse(attention: PlantAttention): PlantAttentionResponse =
    attention.cadence match
      case WateringCadence.Unavailable(sampleCount, maybeElapsed) =>
        PlantAttentionResponse(attention.plant, sampleCount, false, None, maybeElapsed.map(_.toString), None, None)
      case WateringCadence.Inferred(sampleCount, averageInterval, elapsed, urgency, state) =>
        PlantAttentionResponse(
          attention.plant,
          sampleCount,
          true,
          Some(averageInterval.toString),
          Some(elapsed.toString),
          Some(urgencyResponse(urgency)),
          Some(state)
        )

  private def urgencyResponse(urgency: Urgency): UrgencyResponse =
    urgency match
      case Urgency.Unbounded                        => UrgencyResponse(unbounded = true, None, None)
      case Urgency.Finite(elapsed, averageInterval) =>
        UrgencyResponse(
          unbounded = false,
          Some(durationNanos(elapsed).toString),
          Some(durationNanos(averageInterval).toString)
        )

  private def durationNanos(duration: Duration): BigInt =
    BigInt(duration.getSeconds) * 1_000_000_000 + duration.getNano

  private type WireText = PlantId | OperationId | Species | Nickname | Location | Note | NomenclatureName | NomenclatureInfo
  private given [A <: WireText]: Schema[A] = Schema.string
  // Tapir requires inverse mappings for opaque schemas; OpenAPI generation only reads their constraints.
  // $COVERAGE-OFF$
  private given Schema[SubstrateComponentId] = Schema.string
    .map(SubstrateComponentId.parse)(_.value.toString)
    .format("uuid")
  private given Schema[PesticideId] = Schema.string
    .map(PesticideId.parse)(_.value.toString)
    .format("uuid")
  private given Schema[Percentage] = Schema.schemaForInt
    .validate(Validator.min(1).and(Validator.max(100)))
    .map(_.refineOption[Interval.Closed[1, 100]])(value => value)
  private given Schema[Substrate] = summon[Schema[List[SubstratePart]]]
    .validate(Validator.minSize(1))
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
  private given Schema[SubstrateComponentData] = Schema.derived[SubstrateComponentData]
    .modify(_.maybeInfo)(_.copy(isOptional = false).nullable)
  private given Schema[PesticideData] = Schema.derived[PesticideData]
    .modify(_.maybeInfo)(_.copy(isOptional = false).nullable)

  private def stringCodec[A](decode: String => A, encode: A => String) =
    Codec.from(Decoder.decodeString.map(decode), Encoder.encodeString.contramap(encode))

  private inline def enumSchema[A <: Product] =
    Schema.derivedEnumeration[A].apply(encode = Some(value => lowerCamel(value.productPrefix)))

  private def encodedFieldName(name: String) =
    name match
      case "maybeNickname" => "nickname"
      case "maybeNote"     => "notes"
      case "maybeInfo"     => "info"
      case "pesticideType" => "type"
      case _               => name

  private def lowerCamel(name: String) =
    name.substring(0, 1).toLowerCase + name.substring(1)
