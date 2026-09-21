package gardening.adapters.http

import io.circe.Codec
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*

final private[http] case class ApiError(message: String) derives Codec.AsObject

private[http] object JournalError:
  val generic = statusCode.and(jsonBody[ApiError])

  val operationMissing      = ApiError("operation not found")
  val operationTypeMismatch = ApiError("operation type cannot be changed")
  val editFailed            = ApiError("operation could not be edited")
  val catalogRecordMissing  = ApiError("nomenclature not found")
  val catalogInvalidId      = ApiError("invalid nomenclature id")
  val catalogReadFailed     = ApiError("nomenclatures could not be read")
  val catalogWriteFailed    = ApiError("nomenclature could not be saved")

  val edit =
    oneOf[ApiError](
      oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(operationMissing),
      oneOfVariantExactMatcher(StatusCode.Conflict, jsonBody[ApiError])(operationTypeMismatch),
      oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
    )

  val catalogEdit =
    oneOf[ApiError](
      oneOfVariantExactMatcher(StatusCode.BadRequest, jsonBody[ApiError])(catalogInvalidId),
      oneOfVariantExactMatcher(StatusCode.NotFound, jsonBody[ApiError])(catalogRecordMissing),
      oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[ApiError]))
    )
