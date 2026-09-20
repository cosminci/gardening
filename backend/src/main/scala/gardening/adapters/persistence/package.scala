package gardening.adapters.persistence

import cats.data.ValidatedNel
import cats.syntax.apply.*
import cats.syntax.either.*
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
      case OperationDetails.Care(actions, moisture, maybeNote) =>
        Json.obj(
          "actions"  -> Json.arr(actions.toVector.sortBy(_.toString).map(action => Json.fromString(action.toString))*),
          "moisture" -> Json.fromString(moisture.toString),
          "note"     -> maybeNote.fold(Json.Null)(note => Json.fromString(note.value))
        )
      case OperationDetails.Repot(substrate, maybeNote) =>
        Json.obj(
          "substrate" -> Json.arr(substrate.parts.map(encodeSubstratePart)*),
          "note"      -> maybeNote.fold(Json.Null)(note => Json.fromString(note.value))
        )
    ).noSpaces

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
    val moisture = decodeField[String](cursor, "moisture").andThen(decodeMoisture(_).toValidatedNel)
    val note     = decodeField[Option[String]](cursor, "note")

    (actions, moisture, note).mapN: (actions, moisture, note) =>
      OperationDetails.Care(actions.toSet, moisture, maybeNote = note.map(Note.apply))

  private def decodeRepot(cursor: HCursor): ValidatedNel[Throwable, OperationDetails] =
    val substrate = decodeField[List[StoredSubstratePart]](cursor, "substrate")
      .andThen:
        _.traverse: stored =>
          decodeSubstratePart(stored).toValidatedNel
      .andThen(parts => Substrate.of(parts).leftMap(reason => invalidStoredSubstrate(reason.toString)).toValidatedNel)
    val note = decodeField[Option[String]](cursor, "note")

    (substrate, note).mapN: (substrate, note) =>
      OperationDetails.Repot(substrate, maybeNote = note.map(Note.apply))

  private def decodeField[A: Decoder](cursor: HCursor, field: String): ValidatedNel[Throwable, A] =
    cursor.get[A](field).leftMap(invalidOperationPayload).toValidatedNel

  private def decodeAction(encoded: String): Either[Throwable, ActionType] =
    decodeEnum(encoded, ActionType.values.toSeq, "action")

  private def decodeMoisture(encoded: String): Either[Throwable, MoistureLevel] =
    decodeEnum(encoded, MoistureLevel.values.toSeq, "moisture")

  private def decodeEnum[A](encoded: String, values: Seq[A], field: String): Either[Throwable, A] =
    values
      .find(_.toString.equals(encoded))
      .toRight(invalidOperationPayload(DecodingFailure(s"invalid $field: $encoded", ops = Nil)))

  private def decodeSubstratePart(stored: StoredSubstratePart): Either[Throwable, SubstratePart] =
    for
      component <- SubstrateComponent.values
        .find(_.toString.equals(stored.component))
        .toRight(invalidStoredSubstrate(s"unknown component: ${stored.component}"))
      share <- stored.share
        .refineOption[Interval.Closed[1, 100]]
        .toRight(invalidStoredSubstrate(s"invalid share: ${stored.share}"))
    yield SubstratePart(component, share)

  private def encodeSubstratePart(part: SubstratePart): Json =
    Json.obj(
      "component" -> Json.fromString(part.component.toString),
      "share"     -> Json.fromInt(part.share)
    )

  private def invalidOperationPayload(reason: Throwable): RuntimeException =
    val message = reason match
      case DecodingFailure(message, _) => message
      // A non-decoding failure can only be the schema-rejected malformed JSON case above.
      // $COVERAGE-OFF$
      case _ => reason.getMessage
      // $COVERAGE-ON$
    RuntimeException(s"invalid stored operation payload: $message", reason)

  private def invalidStoredSubstrate(reason: String): RuntimeException =
    RuntimeException(s"invalid stored substrate: $reason")

  final private case class StoredSubstratePart(component: String, share: Int)

  private given Decoder[StoredSubstratePart] =
    Decoder.forProduct2("component", "share")(StoredSubstratePart.apply)
