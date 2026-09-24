package gardening.adapters.http

import io.circe.Codec
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*

final private[http] case class ApiError(message: String) derives Codec.AsObject

private[http] object ApiError:
  val generic: EndpointOutput[(StatusCode, ApiError)] = statusCode.and(jsonBody[ApiError])
