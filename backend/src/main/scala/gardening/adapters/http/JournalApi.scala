package gardening.adapters.http

import cats.syntax.either.*
import gardening.domain.*
import io.circe.derivation.{Configuration as CirceConfiguration, ConfiguredCodec, ConfiguredEnumCodec}
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
import java.time.Instant
import scala.deriving.Mirror
import scala.util.Try

final private case class LoggedOperation(id: String) derives Codec.AsObject

object JournalApi:

  private val journalEndpoint   = endpoint.errorOut(JournalError.generic)
  private val getPlantsEndpoint =
    journalEndpoint.get.in("plants").out(jsonBody[Vector[Plant]]).summary("List active plants")

  private val getOperationsEndpoint =
    journalEndpoint.get.in("plants" / path[String]("plantId") / "operations").out(jsonBody[Vector[Operation]]).summary("List a plant's operations")

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

  def serverEndpoints(using journal: PlantJournal): List[ServerEndpoint[Any, Identity]] =
    List(
      getPlantsEndpoint.handle: _ =>
        journal.getPlants match
          case GetPlantsResult.Read(plants)                                 => plants.asRight
          case GetPlantsResult.Corrupted(_) | GetPlantsResult.ReadFailed(_) =>
            (StatusCode.InternalServerError, ApiError("journal could not be read")).asLeft,
      getOperationsEndpoint.handle: plantId =>
        journal.getOperations(PlantId(plantId)) match
          case GetOperationsResult.Read(operations)                                 => operations.asRight
          case GetOperationsResult.Corrupted(_) | GetOperationsResult.ReadFailed(_) =>
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
          case EditOperationResult.Corrupted(_) | EditOperationResult.EditFailed(_) =>
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
  private given Codec[PesticideType]        = stringCodec(PesticideType.apply, _.value)
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
  private given Codec.AsObject[OperationDetails] = ConfiguredCodec.derived

  private type WireText = PlantId | OperationId | Species | Nickname | Location | Note | NomenclatureName | NomenclatureInfo | PesticideType
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
