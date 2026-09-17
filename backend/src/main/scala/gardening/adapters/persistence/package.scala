package gardening.adapters.persistence

import cats.data.ValidatedNel
import cats.syntax.apply.*
import cats.syntax.either.*
import cats.syntax.traverse.*
import cats.syntax.validated.*
import com.augustnagro.magnum.DbCodec
import gardening.domain.*
import io.circe.{DecodingFailure, Decoder, HCursor, Json}
import io.circe.parser.parse
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval

import java.time.Instant
import scala.util.Try

private[persistence] opaque type StoredOperationDetails = String

private[persistence] object StoredOperationDetails:

  def encode(details: OperationDetails): StoredOperationDetails =
    val json = details match
      case OperationDetails.Care(date, actions, moisture, maybeNote) =>
        Json.obj(
          "kind"     -> Json.fromString("Care"),
          "date"     -> Json.fromString(date.toString),
          "actions"  -> Json.arr(actions.toVector.sortBy(_.toString).map(action => Json.fromString(action.toString))*),
          "moisture" -> Json.fromString(moisture.toString),
          "note"     -> maybeNote.fold(Json.Null)(note => Json.fromString(note.value))
        )
      case OperationDetails.Repot(date, substrate, maybeNote) =>
        Json.obj(
          "kind"      -> Json.fromString("Repot"),
          "date"      -> Json.fromString(date.toString),
          "substrate" -> Json.arr(substrate.parts.map(encodeSubstratePart)*),
          "note"      -> maybeNote.fold(Json.Null)(note => Json.fromString(note.value))
        )

    json.noSpaces

  extension (details: StoredOperationDetails)
    def decode: ValidatedNel[Throwable, OperationDetails] =
      parse(details)
        .leftMap(invalidOperationDetails)
        .toValidatedNel
        .andThen: json =>
          decodeField[String](json.hcursor, "kind").andThen(decodeVariant(_, json.hcursor))

  given DbCodec[StoredOperationDetails] =
    DbCodec[String].biMap(value => value, value => value)

  private def decodeVariant(kind: String, cursor: HCursor): ValidatedNel[Throwable, OperationDetails] =
    kind match
      case "Care"  => decodeCare(cursor)
      case "Repot" => decodeRepot(cursor)
      case _       => invalidOperationDetails(DecodingFailure("unknown operation kind", ops = Nil)).invalidNel

  private def decodeCare(cursor: HCursor): ValidatedNel[Throwable, OperationDetails] =
    val date    = decodeField[String](cursor, "date").andThen(decodeDate(_).toValidatedNel)
    val actions = decodeField[List[String]](cursor, "actions")
      .andThen(_.traverse(decodeAction(_).toValidatedNel))
    val moisture = decodeField[String](cursor, "moisture").andThen(decodeMoisture(_).toValidatedNel)
    val note     = decodeField[Option[String]](cursor, "note")

    (date, actions, moisture, note).mapN: (date, actions, moisture, note) =>
      OperationDetails.Care(date, actions.toSet, moisture, maybeNote = note.map(Note.apply))

  private def decodeRepot(cursor: HCursor): ValidatedNel[Throwable, OperationDetails] =
    val date      = decodeField[String](cursor, "date").andThen(decodeDate(_).toValidatedNel)
    val substrate = decodeField[List[StoredSubstratePart]](cursor, "substrate")
      .andThen:
        _.traverse: stored =>
          decodeSubstratePart(stored).leftMap(reason => RuntimeException(s"invalid stored substrate: $reason")).toValidatedNel
      .andThen(parts => Substrate.of(parts).leftMap(reason => RuntimeException(s"invalid stored substrate: $reason")).toValidatedNel)
    val note = decodeField[Option[String]](cursor, "note")

    (date, substrate, note).mapN: (date, substrate, note) =>
      OperationDetails.Repot(date, substrate, maybeNote = note.map(Note.apply))

  private def decodeField[A: Decoder](cursor: HCursor, field: String): ValidatedNel[Throwable, A] =
    cursor.get[A](field).leftMap(invalidOperationDetails).toValidatedNel

  private def decodeDate(encoded: String): Either[Throwable, Instant] =
    Try(Instant.parse(encoded)).toEither.left.map(_ => invalidOperationDetails(DecodingFailure("invalid operation date", ops = Nil)))

  private def decodeAction(encoded: String): Either[Throwable, ActionType] =
    decodeEnum(encoded, ActionType.values.toSeq, "action")

  private def decodeMoisture(encoded: String): Either[Throwable, MoistureLevel] =
    decodeEnum(encoded, MoistureLevel.values.toSeq, "moisture")

  private def decodeEnum[A](encoded: String, values: Seq[A], field: String): Either[Throwable, A] =
    values
      .find(_.toString.equals(encoded))
      .toRight(invalidOperationDetails(DecodingFailure(s"invalid $field: $encoded", ops = Nil)))

  private def decodeSubstratePart(stored: StoredSubstratePart): Either[SubstrateError, SubstratePart] =
    for
      component <- SubstrateComponent.values.find(_.toString.equals(stored.component)).toRight(SubstrateError.Malformed)
      share     <- stored.share.refineOption[Interval.Closed[1, 100]].toRight(SubstrateError.Malformed)
    yield SubstratePart(component, share)

  private def encodeSubstratePart(part: SubstratePart): Json =
    Json.obj(
      "component" -> Json.fromString(part.component.toString),
      "share"     -> Json.fromInt(part.share)
    )

  private def invalidOperationDetails(reason: Throwable): RuntimeException =
    RuntimeException("invalid stored operation details", reason)

  final private case class StoredSubstratePart(component: String, share: Int)

  private given Decoder[StoredSubstratePart] =
    Decoder.forProduct2("component", "share")(StoredSubstratePart.apply)
