package gardening.adapters.persistence

import cats.data.ValidatedNel
import cats.syntax.either.*
import cats.syntax.traverse.*
import gardening.domain.*
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval

private[persistence] object StoredSubstrate:

  def encodeString(substrate: Substrate): String = encode(substrate).noSpaces

  def encode(substrate: Substrate): Json =
    Json.arr(
      substrate.parts.map(part =>
        Json.obj(
          "component" -> Json.fromString(part.componentId.value.toString),
          "share"     -> Json.fromInt(part.share)
        )
      )*
    )

  def decodeString(encoded: String): Either[Throwable, Substrate] =
    // SQLite's json_valid constraint prevents this parse failure for persisted plants.
    // $COVERAGE-OFF$
    parse(encoded)
      .leftMap(reason => invalid(reason.message))
      // $COVERAGE-ON$
      .flatMap(_.as[List[StoredPart]].leftMap(reason => invalid(reason.message)))
      .flatMap(parts => decodeParts(parts).toEither.leftMap(errors => errors.head))

  def decodeJson(json: Json): ValidatedNel[Throwable, Substrate] =
    json.as[List[StoredPart]]
      .leftMap(reason => invalid(reason.message))
      .toValidatedNel
      .andThen(decodeParts)

  private def decodeParts(parts: List[StoredPart]): ValidatedNel[Throwable, Substrate] =
    parts
      .traverse(decodePart(_).toValidatedNel)
      .andThen(parts => Substrate.of(parts).leftMap(reason => invalid(reason.toString)).toValidatedNel)

  private def decodePart(part: StoredPart): Either[Throwable, SubstratePart] =
    for
      componentId <- SubstrateComponentId.parse(part.component).toRight(invalid(s"invalid component id: ${part.component}"))
      share       <- part.share
        .refineOption[Interval.Closed[1, 100]]
        .toRight(invalid(s"invalid share: ${part.share}"))
    yield SubstratePart(componentId, share)

  private def invalid(reason: String): RuntimeException =
    RuntimeException(s"invalid stored substrate: $reason")

  private case class StoredPart(component: String, share: Int)

  private given Decoder[StoredPart] =
    Decoder.forProduct2("component", "share")(StoredPart.apply)
