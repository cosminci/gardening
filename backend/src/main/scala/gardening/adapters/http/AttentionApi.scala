package gardening.adapters.http

import gardening.domain.PlantId
import gardening.domain.attention.*
import io.circe.derivation.{Configuration as CirceConfiguration, ConfiguredCodec}
import io.circe.{Codec, Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.generic.Configuration as TapirConfiguration
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint

import java.time.Instant
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.Try

object AttentionApi:

  extension (attention: WateringAttention)
    // Only OpenAPI schema generation uses this discriminator; responses use Circe's discriminator.
    // $COVERAGE-OFF$
    private def kind =
      attention match
        case _: WateringAttention.Unavailable => "unavailable"
        case _: WateringAttention.Current     => "current"
        case _: WateringAttention.Overdue     => "overdue"
        case _: WateringAttention.RedAlert    => "redAlert"
  // $COVERAGE-ON$

  private val getAttentionEndpoint =
    endpoint.get.in("attention").out(jsonBody[AttentionProjection]).summary("Read plant attention")

  private[http] val publicEndpoints: List[AnyEndpoint] = List(getAttentionEndpoint)

  def serverEndpoints(using attention: PlantAttentionMonitor): List[ServerEndpoint[Any, Identity]] =
    List(getAttentionEndpoint.handleSuccess(_ => attention.current))

  private given circeConfiguration: CirceConfiguration =
    CirceConfiguration.default
      .withTransformMemberNames(encodedFieldName)
      .withTransformConstructorNames(lowerCamel)
      .withDiscriminator("kind")
  private given tapirConfiguration: TapirConfiguration =
    TapirConfiguration.default.copy(
      toEncodedName = encodedFieldName,
      discriminator = Some("kind"),
      // Only OpenAPI generation translates Tapir's discriminator names.
      // $COVERAGE-OFF$
      toDiscriminatorValue = name => lowerCamel(name.fullName.split('.').last.stripSuffix("$"))
      // $COVERAGE-ON$
    )

  private given Codec[PlantId] = Codec.from(
    // Plant identifiers appear only in response bodies; no request decodes them here.
    // $COVERAGE-OFF$
    Decoder.decodeString.map(PlantId.apply),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.value)
  )
  private given Codec[Instant] = Codec.from(
    // Attention timestamps appear only in response bodies.
    // $COVERAGE-OFF$
    Decoder.decodeString.emapTry(value => Try(Instant.parse(value))),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.toString)
  )
  private given Codec[WateringSampleCount] = Codec.from(
    // Attention sample counts appear only in response bodies.
    // $COVERAGE-OFF$
    Decoder.failedWithMessage("watering attention is output-only"),
    // $COVERAGE-ON$
    Encoder.encodeInt.contramap(value => value)
  )
  private given Codec[FiniteDuration] = Codec.from(
    // Attention durations appear only in response bodies.
    // $COVERAGE-OFF$
    Decoder.failedWithMessage("watering duration is output-only"),
    // $COVERAGE-ON$
    Encoder.encodeString.contramap(_.toMillis.toString)
  )
  private given Codec.AsObject[WateringAttention]   = ConfiguredCodec.derived
  private given Codec.AsObject[PlantAttention]      = ConfiguredCodec.derived
  private given Codec.AsObject[AttentionProjection] = ConfiguredCodec.derived

  private given Schema[PlantId]             = Schema.string
  private given Schema[WateringSampleCount] = Schema.schemaForInt
    .validate(Validator.min(0).and(Validator.max(20)))
    // Tapir never decodes output-only attention sample counts through their schema.
    // $COVERAGE-OFF$
    .map(_.refineOption[Interval.Closed[0, 20]])(value => value)
  // $COVERAGE-ON$
  private given Schema[FiniteDuration] = Schema.schemaForString
    // Tapir only invokes duration schema mappings while generating OpenAPI.
    // $COVERAGE-OFF$
    .map(value => Try(FiniteDuration(value.toLong, MILLISECONDS)).toOption)(_.toMillis.toString)
  // $COVERAGE-ON$
  private given Schema[WateringAttention.Current]     = Schema.derived[WateringAttention.Current].name(Schema.SName("WateringCurrent"))
  private given Schema[WateringAttention.Overdue]     = Schema.derived[WateringAttention.Overdue].name(Schema.SName("WateringOverdue"))
  private given Schema[WateringAttention.RedAlert]    = Schema.derived[WateringAttention.RedAlert].name(Schema.SName("WateringRedAlert"))
  private given Schema[WateringAttention.Unavailable] = Schema
    .derived[WateringAttention.Unavailable]
    .name(Schema.SName("WateringUnavailable"))
    .modify(_.maybeElapsed)(_.copy(isOptional = false).nullable)
  private given Schema[WateringAttention] = Schema
    .oneOfUsingField[WateringAttention, String](_.kind, identity)(
      "unavailable" -> summon[Schema[WateringAttention.Unavailable]],
      "current"     -> summon[Schema[WateringAttention.Current]],
      "overdue"     -> summon[Schema[WateringAttention.Overdue]],
      "redAlert"    -> summon[Schema[WateringAttention.RedAlert]]
    )
    .name(Schema.SName("WateringAttention"))
  private given Schema[PlantAttention]      = Schema.derived
  private given Schema[AttentionProjection] = Schema.derived[AttentionProjection].modify(_.plants)(_.copy(isOptional = false))

  private def encodedFieldName(name: String) =
    name match
      case "maybeElapsed"    => "elapsedMillis"
      case "averageInterval" => "averageIntervalMillis"
      case "elapsed"         => "elapsedMillis"
      case _                 => name

  private def lowerCamel(name: String) =
    name.substring(0, 1).toLowerCase + name.substring(1)
