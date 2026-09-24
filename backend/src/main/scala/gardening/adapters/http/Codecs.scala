package gardening.adapters.http

import gardening.domain.*
import io.circe.derivation.{Configuration as CirceConfiguration, ConfiguredCodec}
import io.circe.{Codec, Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval
import sttp.tapir.*
import sttp.tapir.generic.Configuration as TapirConfiguration
import sttp.tapir.generic.auto.*

import scala.deriving.Mirror

private[http] object Codecs:

  given CirceConfiguration =
    CirceConfiguration.default
      .withTransformMemberNames(encodedFieldName)
      .withTransformConstructorNames(lowerCamel)
      .withDiscriminator("kind")
  given TapirConfiguration =
    TapirConfiguration.default.copy(
      toEncodedName = encodedFieldName,
      discriminator = Some("kind"),
      toDiscriminatorValue = name => lowerCamel(name.fullName.split('.').last.stripSuffix("$"))
    )

  // Plant identifiers are output-only in JSON bodies.
  // $COVERAGE-OFF$
  given Codec[PlantId] = Codec.from(Decoder.decodeString.map(PlantId.apply), Encoder.encodeString.contramap(_.value))
  // $COVERAGE-ON$
  given Codec[SubstrateComponentId] = Codec.from(
    Decoder.decodeString.emap(value => SubstrateComponentId.parse(value).toRight(s"invalid substrate component id: $value")),
    Encoder.encodeString.contramap(_.value.toString)
  )
  given Codec[Percentage] = Codec.from(
    Decoder.decodeInt.emap(value => value.refineOption[Interval.Closed[1, 100]].toRight(s"invalid share: $value")),
    Encoder.encodeInt.contramap(value => value)
  )
  inline given productCodec[A](using Mirror.ProductOf[A]): Codec.AsObject[A] = ConfiguredCodec.derived
  given Codec[Substrate]                                                     = Codec.from(
    Decoder.decodeList[SubstratePart].emap(parts => Substrate.of(parts).left.map(_.toString)),
    Encoder.encodeList[SubstratePart].contramap(_.parts)
  )

  given Schema[PlantId] = Schema.string
  // JSON bodies use Circe; Tapir does not invoke this identifier schema mapping at runtime.
  // $COVERAGE-OFF$
  given Schema[SubstrateComponentId] = Schema.string.map(SubstrateComponentId.parse)(_.value.toString).format("uuid")
  // $COVERAGE-ON$
  given Schema[Percentage] = Schema.schemaForInt
    .validate(Validator.min(1).and(Validator.max(100)))
    // JSON bodies use Circe rather than this percentage schema's inverse mapping.
    // $COVERAGE-OFF$
    .map(_.refineOption[Interval.Closed[1, 100]])(value => value)
  // $COVERAGE-ON$
  given Schema[Substrate] = summon[Schema[List[SubstratePart]]]
    .validate(Validator.minSize(1))
    // JSON bodies use Circe rather than this substrate schema's inverse mapping.
    // $COVERAGE-OFF$
    .map(parts => Substrate.of(parts).toOption)(_.parts)
  // $COVERAGE-ON$

  inline def enumSchema[A <: Product]: Schema[A] = Schema.derivedEnumeration[A].apply(encode = Some(value => lowerCamel(value.productPrefix)))

  private def encodedFieldName(name: String) =
    name match
      case "maybeNickname" => "nickname"
      case "maybeNote"     => "notes"
      case _               => name

  def lowerCamel(name: String): String = name.substring(0, 1).toLowerCase + name.substring(1)
