package gardening.adapters.persistence

import cats.data.ValidatedNel
import cats.syntax.apply.*
import cats.syntax.either.*
import cats.syntax.option.*
import cats.syntax.traverse.*
import cats.syntax.validated.*
import gardening.domain.*
import io.circe.{DecodingFailure, Decoder, HCursor, Json}
import io.circe.parser.parse
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval

private[persistence] object StoredOperationPayload:

  def kind(details: OperationDetails): String =
    details match
      case _: OperationDetails.Care  => "Care"
      case _: OperationDetails.Repot => "Repot"

  def encode(details: OperationDetails): String =
    (details match
      case OperationDetails.Care(actions, pesticides, moisture, maybeNote) =>
        Json.obj(
          "actions"    -> Json.arr(actions.toVector.sortBy(_.toString).map(action => Json.fromString(action.toString))*),
          "pesticides" -> Json.arr(pesticides.toVector.sortBy(_.value.toString).map(id => Json.fromString(id.value.toString))*),
          "moisture"   -> Json.fromString(moisture.toString),
          "note"       -> maybeNote.fold(Json.Null)(note => Json.fromString(note.value))
        )
      case OperationDetails.Repot(substrate, maybeNote) =>
        Json.obj(
          "substrate" -> encodeSubstrate(substrate),
          "note"      -> maybeNote.fold(Json.Null)(note => Json.fromString(note.value))
        )
    ).noSpaces

  def encodeSubstrate(substrate: Substrate): Json =
    Json.arr(
      substrate.parts.map(part =>
        Json.obj(
          "component" -> Json.fromString(part.componentId.value.toString),
          "share"     -> Json.fromInt(part.share)
        )
      )*
    )

  def decodeSubstrate(encoded: String): Either[Throwable, Substrate] =
    // SQLite's json_valid constraint prevents this parse failure for persisted plants.
    // $COVERAGE-OFF$
    parse(encoded)
      .leftMap(reason => invalidSubstrate(reason.message))
      // $COVERAGE-ON$
      .flatMap(_.as[List[StoredSubstratePart]].leftMap(reason => invalidSubstrate(reason.message)))
      .flatMap(parts => decodeSubstrateParts(parts).toEither.leftMap(errors => errors.head))

  extension (payload: String)
    def decode(kind: String): ValidatedNel[Throwable, OperationDetails] =
      val jsonResult = parse(payload) match
        case Right(json) => json.validNel
        // The schema check rejects malformed JSON payloads before this decoder runs.
        // $COVERAGE-OFF$
        case Left(reason) => invalidOperationPayload(reason).invalidNel
        // $COVERAGE-ON$
      jsonResult.andThen(json => decodeVariant(kind, json.hcursor))

  private def decodeVariant(kind: String, cursor: HCursor): ValidatedNel[Throwable, OperationDetails] =
    kind match
      case "Care"  => decodeCare(cursor)
      case "Repot" => decodeRepot(cursor)
      // The schema check rejects operation kinds other than Care and Repot.
      // $COVERAGE-OFF$
      case _ => invalidOperationPayload(DecodingFailure("unknown operation kind", ops = Nil)).invalidNel
      // $COVERAGE-ON$

  private def decodeCare(cursor: HCursor): ValidatedNel[Throwable, OperationDetails] =
    val actions = decodeField[List[String]](cursor, "actions")
      .andThen(_.traverse(decodeAction(_).toValidatedNel))
    val pesticides = decodeField[List[String]](cursor, "pesticides")
      .andThen(_.traverse(decodePesticide(_).toValidatedNel))
    val moisture = decodeField[String](cursor, "moisture").andThen(decodeMoisture(_).toValidatedNel)
    val note     = decodeField[Option[String]](cursor, "note")

    (actions, pesticides, moisture, note).mapN: (actions, pesticides, moisture, note) =>
      OperationDetails.Care(actions.toSet, pesticides.toSet, moisture, maybeNote = note.map(Note.apply))

  private def decodeRepot(cursor: HCursor): ValidatedNel[Throwable, OperationDetails] =
    val substrate = cursor.downField("substrate").focus
      .toValidNel(invalidOperationPayload(DecodingFailure("missing substrate", cursor.history)))
      .andThen(decodeSubstrate)
    val note = decodeField[Option[String]](cursor, "note")

    (substrate, note).mapN: (substrate, note) =>
      OperationDetails.Repot(substrate, maybeNote = note.map(Note.apply))

  private def decodeField[A: Decoder](cursor: HCursor, field: String): ValidatedNel[Throwable, A] =
    cursor.get[A](field).leftMap(invalidOperationPayload).toValidatedNel

  private def decodeAction(encoded: String): Either[Throwable, ActionType] =
    decodeEnum(encoded, ActionType.values.toSeq, "action")

  private def decodeMoisture(encoded: String): Either[Throwable, MoistureLevel] =
    decodeEnum(encoded, MoistureLevel.values.toSeq, "moisture")

  private def decodePesticide(encoded: String): Either[Throwable, PesticideId] =
    PesticideId.parse(encoded).toRight(invalidOperationPayload(DecodingFailure(s"invalid pesticide id: $encoded", ops = Nil)))

  private def decodeSubstrate(json: Json): ValidatedNel[Throwable, Substrate] =
    json.as[List[StoredSubstratePart]]
      .leftMap(reason => invalidSubstrate(reason.message))
      .toValidatedNel
      .andThen(decodeSubstrateParts)

  private def decodeSubstrateParts(parts: List[StoredSubstratePart]): ValidatedNel[Throwable, Substrate] =
    parts
      .traverse(decodeSubstratePart(_).toValidatedNel)
      .andThen(parts => Substrate.of(parts).leftMap(reason => invalidSubstrate(reason.toString)).toValidatedNel)

  private def decodeSubstratePart(part: StoredSubstratePart): Either[Throwable, SubstratePart] =
    for
      componentId <- SubstrateComponentId.parse(part.component).toRight(invalidSubstrate(s"invalid component id: ${part.component}"))
      share       <- part.share
        .refineOption[Interval.Closed[1, 100]]
        .toRight(invalidSubstrate(s"invalid share: ${part.share}"))
    yield SubstratePart(componentId, share)

  private def decodeEnum[A](encoded: String, values: Seq[A], field: String): Either[Throwable, A] =
    values
      .find(_.toString.equals(encoded))
      .toRight(invalidOperationPayload(DecodingFailure(s"invalid $field: $encoded", ops = Nil)))

  private def invalidOperationPayload(reason: Throwable): RuntimeException =
    val message = reason match
      case DecodingFailure(message, _) => message
      // A non-decoding failure can only be the schema-rejected malformed JSON case above.
      // $COVERAGE-OFF$
      case _ => reason.getMessage
      // $COVERAGE-ON$
    RuntimeException(s"invalid stored operation payload: $message", reason)

  private def invalidSubstrate(reason: String): RuntimeException =
    RuntimeException(s"invalid stored substrate: $reason")

  private case class StoredSubstratePart(component: String, share: Int)

  private given Decoder[StoredSubstratePart] =
    Decoder.forProduct2("component", "share")(StoredSubstratePart.apply)
