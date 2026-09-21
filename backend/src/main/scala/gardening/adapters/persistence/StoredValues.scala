package gardening.adapters.persistence

import cats.syntax.either.*
import cats.syntax.traverse.*
import gardening.domain.*
import io.circe.{DecodingFailure, Decoder, HCursor, Json}
import io.circe.parser.parse
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval

def storedOperationKind(details: OperationDetails) =
  details match
    case _: OperationDetails.Care  => "Care"
    case _: OperationDetails.Repot => "Repot"

def encodeStoredOperation(details: OperationDetails) =
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

def encodeSubstrate(substrate: Substrate) =
  Json.arr(
    substrate.parts.map(part =>
      Json.obj(
        "component" -> Json.fromString(part.componentId.value.toString),
        "share"     -> Json.fromInt(part.share)
      )
    )*
  )

def decodeSubstrate(encoded: String) =
  // SQLite's json_valid constraint prevents this parse failure for persisted plants.
  // $COVERAGE-OFF$
  parse(encoded)
    .leftMap(reason => invalidSubstrate(reason.message))
    // $COVERAGE-ON$
    .flatMap(_.as[List[StoredSubstratePart]].leftMap(reason => invalidSubstrate(reason.message)))
    .flatMap(decodeSubstrateParts)

extension (payload: String)
  def decodeStoredOperation(kind: String) =
    for
      json    <- parse(payload).leftMap(invalidOperationPayload)
      details <- decodeDetails(kind, json.hcursor)
    yield details

private def decodeDetails(kind: String, cursor: HCursor) =
  kind match
    case "Care"  => decodeCare(cursor)
    case "Repot" => decodeRepot(cursor)
    // The schema check rejects operation kinds other than Care and Repot.
    // $COVERAGE-OFF$
    case _ => invalidOperationPayload(DecodingFailure("unknown operation kind", ops = Nil)).asLeft
    // $COVERAGE-ON$

private def decodeCare(cursor: HCursor) =
  for
    actions    <- decodeField[List[String]](cursor, "actions").flatMap(_.traverse(decodeAction))
    pesticides <- decodeField[List[String]](cursor, "pesticides").flatMap(_.traverse(decodePesticide))
    moisture   <- decodeField[String](cursor, "moisture").flatMap(decodeMoisture)
    note       <- decodeField[Option[String]](cursor, "note")
  yield OperationDetails.Care(actions.toSet, pesticides.toSet, moisture, maybeNote = note.map(Note.apply))

private def decodeRepot(cursor: HCursor) =
  for
    json      <- cursor.downField("substrate").focus.toRight(invalidOperationPayload(DecodingFailure("missing substrate", cursor.history)))
    substrate <- decodeSubstrate(json)
    note      <- decodeField[Option[String]](cursor, "note")
  yield OperationDetails.Repot(substrate, maybeNote = note.map(Note.apply))

private def decodeField[A: Decoder](cursor: HCursor, field: String) =
  cursor.get[A](field).leftMap(invalidOperationPayload)

private def decodeAction(encoded: String) =
  decodeEnum(encoded, ActionType.values.toSeq, "action")

private def decodeMoisture(encoded: String) =
  decodeEnum(encoded, MoistureLevel.values.toSeq, "moisture")

private def decodePesticide(encoded: String) =
  PesticideId.parse(encoded).toRight(invalidOperationPayload(DecodingFailure(s"invalid pesticide id: $encoded", ops = Nil)))

private def decodeSubstrate(json: Json) =
  json
    .as[List[StoredSubstratePart]]
    .leftMap(reason => invalidSubstrate(reason.message))
    .flatMap(decodeSubstrateParts)

private def decodeSubstrateParts(parts: List[StoredSubstratePart]) =
  parts
    .traverse(decodeSubstratePart)
    .flatMap(parts => Substrate.of(parts).leftMap(reason => invalidSubstrate(reason.toString)))

private def decodeSubstratePart(part: StoredSubstratePart) =
  for
    componentId <- SubstrateComponentId.parse(part.component).toRight(invalidSubstrate(s"invalid component id: ${part.component}"))
    share       <- part.share
      .refineOption[Interval.Closed[1, 100]]
      .toRight(invalidSubstrate(s"invalid share: ${part.share}"))
  yield SubstratePart(componentId, share)

private def decodeEnum[A](encoded: String, values: Seq[A], field: String) =
  values
    .find(_.toString.equals(encoded))
    .toRight(invalidOperationPayload(DecodingFailure(s"invalid $field: $encoded", ops = Nil)))

private def invalidOperationPayload(reason: Throwable) =
  val message = reason match
    case DecodingFailure(message, _) => message
    // A non-decoding failure can only be the schema-rejected malformed JSON case above.
    // $COVERAGE-OFF$
    case _ => reason.getMessage
    // $COVERAGE-ON$
  RuntimeException(s"invalid stored operation payload: $message", reason)

private def invalidSubstrate(reason: String) =
  RuntimeException(s"invalid stored substrate: $reason")

private case class StoredSubstratePart(component: String, share: Int)

private given Decoder[StoredSubstratePart] =
  Decoder.forProduct2("component", "share")(StoredSubstratePart.apply)
