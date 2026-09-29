package gardening.adapters.http

import cats.syntax.either.*
import gardening.domain.*
import gardening.domain.operations.*
import gardening.usecases.OperationLedger
import io.circe.derivation.{ConfiguredCodec, ConfiguredEnumCodec}
import io.circe.{Codec, Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.{GreaterEqual, Interval}
import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.Codec as TapirCodec
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint

import java.time.Instant
import scala.deriving.Mirror
import scala.util.Try

import Codecs.given

final private case class LoggedOperation(id: String) derives Codec.AsObject

object OperationApi:

  private val plantMissing            = ApiError("plant not found")
  private val plantArchived           = ApiError("plant already archived")
  private val operationMissing        = ApiError("operation not found")
  private val operationTypeMismatch   = ApiError("operation type cannot be changed")
  private val editFailed              = ApiError("operation could not be edited")
  private val cannotDeleteLatestRepot = ApiError("cannot delete the plant's current latest repot")
  private val deleteFailed            = ApiError("operation could not be deleted")
  private val plantReadErrors         = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(plantMissing),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )
  private val logOperationErrors = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(plantMissing),
    oneOfVariantExactMatcher(StatusCode.Conflict, jsonBody[ApiError])(plantArchived),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )
  private val editOperationErrors = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(operationMissing),
    oneOfVariantExactMatcher(StatusCode.Conflict, jsonBody[ApiError])(operationTypeMismatch),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )
  private val deleteOperationErrors = oneOf[ApiError](
    oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(operationMissing),
    oneOfVariantExactMatcher(StatusCode.Conflict, jsonBody[ApiError])(cannotDeleteLatestRepot),
    oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
  )

  final private case class LogOperationRequest(plantId: String, date: Instant, details: OperationDetails)

  private val operationEndpoint     = endpoint.errorOut(ApiError.generic)
  private val getOperationsEndpoint =
    operationEndpoint.get.in("operations").in(query[String]("plantId").validate(Validator.minLength(1)))
      .in(query[OperationOffset]("offset").default(0))
      .in(query[OperationPageSize]("pageSize").default(3))
      .out(jsonBody[OperationPage]).summary("List a bounded page of plant operations")
  private val getOperationDateRangeEndpoint =
    endpoint.get.in("operations" / "date-range").in(query[String]("plantId").validate(Validator.minLength(1))).errorOut(plantReadErrors)
      .out(jsonBody[OperationDateRange]).summary("Read the first and last recorded operation dates")
  private val logOperationEndpoint =
    endpoint.post.in("operations").in(jsonBody[LogOperationRequest]).errorOut(logOperationErrors)
      .out(statusCode(StatusCode.Created)).out(jsonBody[LoggedOperation]).summary("Log a plant operation")
  private val editOperationEndpoint =
    endpoint.put.in("operations" / path[String]("operationId")).in(jsonBody[OperationDetails]).errorOut(editOperationErrors)
      .out(jsonBody[Operation]).summary("Edit a plant operation")
  private val deleteOperationEndpoint =
    endpoint.delete.in("operations" / path[String]("operationId")).errorOut(deleteOperationErrors)
      .out(statusCode(StatusCode.NoContent)).summary("Delete a plant operation")

  private[http] val publicEndpoints: List[AnyEndpoint] =
    List(getOperationsEndpoint, getOperationDateRangeEndpoint, logOperationEndpoint, editOperationEndpoint, deleteOperationEndpoint)

  def serverEndpoints(using operations: OperationLedger): List[ServerEndpoint[Any, Identity]] =
    List(
      getOperationsEndpoint.handle: (plantId, offset, pageSize) =>
        operations.getOperations(PlantId(plantId), OperationWindow(offset, pageSize)) match
          case GetOperationsResult.Read(page)    => page.asRight
          case _: GetOperationsResult.ReadFailed => (StatusCode.InternalServerError, ApiError("journal could not be read")).asLeft,
      getOperationDateRangeEndpoint.handle: plantId =>
        operations.getOperationDateRange(PlantId(plantId)) match
          case GetOperationDateRangeResult.Read(range)   => range.asRight
          case GetOperationDateRangeResult.PlantMissing  => plantMissing.asLeft
          case _: GetOperationDateRangeResult.ReadFailed => ApiError("operation dates could not be read").asLeft,
      logOperationEndpoint.handle: request =>
        operations.logOperation(PlantId(request.plantId), request.date, request.details) match
          case LogOperationResult.Logged(id)       => LoggedOperation(id.value).asRight
          case LogOperationResult.PlantMissing     => plantMissing.asLeft
          case LogOperationResult.PlantArchived    => plantArchived.asLeft
          case _: LogOperationResult.LoggingFailed => ApiError("operation could not be logged").asLeft,
      editOperationEndpoint.handle: (operationId, details) =>
        operations.editOperation(OperationId(operationId), details) match
          case EditOperationResult.Edited(edited)        => edited.asRight
          case EditOperationResult.OperationMissing      => operationMissing.asLeft
          case EditOperationResult.OperationTypeMismatch => operationTypeMismatch.asLeft
          case _: EditOperationResult.EditFailed         => editFailed.asLeft,
      deleteOperationEndpoint.handle: operationId =>
        operations.deleteOperation(OperationId(operationId)) match
          case DeleteOperationResult.Deleted                 => ().asRight
          case DeleteOperationResult.OperationMissing        => operationMissing.asLeft
          case DeleteOperationResult.CannotDeleteLatestRepot => cannotDeleteLatestRepot.asLeft
          case _: DeleteOperationResult.DeleteFailed         => deleteFailed.asLeft
    )

  // value => value is identity (nothing to verify); the forward function only re-derives a Schema
  // `.default` value for docs, which none of these schemas set, so it's unreachable either way.
  // $COVERAGE-OFF$
  private lazy val operationOffsetSchema   = Schema.schemaForInt.validate(Validator.min(0)).map(_.refineOption[GreaterEqual[0]])(value => value)
  private lazy val operationPageSizeSchema =
    Schema.schemaForInt.validate(Validator.min(1).and(Validator.max(10))).map(_.refineOption[Interval.Closed[1, 10]])(value => value)
  // $COVERAGE-ON$

  private given TapirCodec.PlainCodec[OperationOffset] = TapirCodec.int.mapDecode(value =>
    value.refineOption[GreaterEqual[0]] match
      case Some(offset) => DecodeResult.Value(offset)
      case None         => DecodeResult.Error(value.toString, IllegalArgumentException("offset must be at least 0"))
  )(value => value).schema(operationOffsetSchema)

  private given TapirCodec.PlainCodec[OperationPageSize] = TapirCodec.int.mapDecode(value =>
    value.refineOption[Interval.Closed[1, 10]] match
      case Some(size) => DecodeResult.Value(size)
      case None       => DecodeResult.Error(value.toString, IllegalArgumentException("page size must be between 1 and 10"))
  )(value => value).schema(operationPageSizeSchema)

  // No endpoint accepts an OperationId in a request body, so this Decoder branch is never invoked.
  // $COVERAGE-OFF$
  private given Codec[OperationId] = Codec.from(Decoder.decodeString.map(OperationId.apply), Encoder.encodeString.contramap(_.value))
  // $COVERAGE-ON$
  private type OperationEnum = ActionType | MoistureLevel
  private inline given [A <: OperationEnum](using Mirror.SumOf[A]): Codec[A] = ConfiguredEnumCodec.derived
  private given Codec[Instant]                                               =
    Codec.from(Decoder.decodeString.emapTry(value => Try(Instant.parse(value))), Encoder.encodeString.contramap(_.toString))
  private given Codec[Note]        = Codec.from(Decoder.decodeString.map(Note.apply), Encoder.encodeString.contramap(_.value))
  private given Codec[PesticideId] = Codec.from(
    Decoder.decodeString.emap(value => PesticideId.parse(value).toRight(s"invalid pesticide id: $value")),
    Encoder.encodeString.contramap(_.value.toString)
  )
  private given Encoder[Set[ActionType]]            = Encoder.encodeList[ActionType].contramap(_.toList.sortBy(_.toString))
  private given Codec.AsObject[OperationDetails]    = ConfiguredCodec.derived
  private given Codec.AsObject[OperationDateRange]  = ConfiguredCodec.derived
  private given Codec.AsObject[LogOperationRequest] = ConfiguredCodec.derived

  private given Schema[ActionType]    = Codecs.enumSchema[ActionType]
  private given Schema[MoistureLevel] = Codecs.enumSchema[MoistureLevel]

  private type OperationText = OperationId | Note
  private given [A <: OperationText]: Schema[A] = Schema.string
  // JSON bodies use Circe; Tapir does not invoke this identifier schema mapping at runtime.
  // $COVERAGE-OFF$
  private given Schema[PesticideId] = Schema.string.map(PesticideId.parse)(_.value.toString).format("uuid")
  // $COVERAGE-ON$
  private given Schema[OperationDetails.Care] = Schema.derived[OperationDetails.Care]
    .modify(_.actions)(_.copy(isOptional = false))
    .modify(_.pesticides)(_.copy(isOptional = false))
    .modify(_.maybeNote)(_.copy(isOptional = false).nullable)
  private given Schema[OperationDetails.Repot] = Schema.derived[OperationDetails.Repot]
    .modify(_.substrate)(_.copy(isOptional = false))
    .modify(_.maybeNote)(_.copy(isOptional = false).nullable)
  private given Schema[OperationPage]       = Schema.derived[OperationPage].modify(_.operations)(_.copy(isOptional = false))
  private given Schema[LogOperationRequest] = Schema.derived[LogOperationRequest].modify(_.plantId)(_.validate(Validator.minLength(1)))
