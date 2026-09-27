package gardening.adapters.sqlite

import cats.syntax.either.*
import gardening.domain.*
import io.circe.{Codec, Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval

// Shared between SqlitePlantStore and SqliteOperationStore: a plant's substrate and a repot
// operation's substrate are both JSON-encoded with the same shape.
private[sqlite] object Codecs:

  given Codec[SubstrateComponentId] = Codec.from(
    Decoder.decodeString.emap(value => SubstrateComponentId.parse(value).toRight(s"invalid component id: $value")),
    Encoder.encodeString.contramap(_.value.toString)
  )

  given Codec[Percentage] = Codec.from(
    Decoder.decodeInt.emap(value => value.refineOption[Interval.Closed[1, 100]].toRight(s"invalid share: $value")),
    Encoder.encodeInt.contramap(value => value: Int)
  )

  given Decoder[SubstratePart] =
    Decoder.forProduct2("component", "share")(SubstratePart.apply)

  given Encoder[SubstratePart] =
    Encoder.forProduct2("component", "share")(part => (part.componentId, part.share))

  given Decoder[Substrate] =
    Decoder.decodeList[SubstratePart].emap(parts => Substrate.of(parts).leftMap(_.toString))

  given Encoder[Substrate] =
    Encoder.encodeList[SubstratePart].contramap(_.parts)
