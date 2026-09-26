package gardening.adapters.http

import gardening.domain.PlantId
import gardening.domain.attention.*
import io.circe.derivation.{Configuration as CirceConfiguration, ConfiguredCodec}
import io.circe.{Codec, Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval
import ox.flow.Flow
import sttp.capabilities.WebSockets
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.generic.Configuration as TapirConfiguration
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.sync.OxStreams

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
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

  private val feedPollInterval = 1.second

  private val getAttentionEndpoint = endpoint.get.in("attention").out(jsonBody[AttentionProjection]).summary("Read plant attention")

  val attentionFeedEndpoint = endpoint.get
    .in("attention" / "feed")
    .out(webSocketBody[String, CodecFormat.TextPlain, AttentionProjection, CodecFormat.Json](OxStreams))
    .summary("Push plant attention updates")

  // The feed is pushed over a websocket; it has no OpenAPI/HTTP contract to document.
  private[http] val publicEndpoints: List[AnyEndpoint] = List(getAttentionEndpoint)

  private[http] def httpServerEndpoint(using attention: PlantAttentionMonitor): ServerEndpoint[Any, Identity] =
    getAttentionEndpoint.handleSuccess(_ => attention.current)

  /**
   * Neither tapir's `webSocketBody` nor Ox provide a broadcast/topic primitive, so each connection independently ticks and pushes the monitor's
   * current projection (an in-memory read, kept fresh by the recompute loop), deduplicating unchanged values. Merging the drained incoming frames
   * ends the feed as soon as the client disconnects.
   */
  def serverEndpoints(using
      attention: PlantAttentionMonitor,
      heartbeats: ConnectionHeartbeats
  ): List[ServerEndpoint[OxStreams & WebSockets, Identity]] =
    // tapir-sttp-stub-server can't run an OxStreams endpoint's logic, so this dispatch isn't seam-tested; `attentionFeed` is exercised directly.
    // $COVERAGE-OFF$
    List(httpServerEndpoint, attentionFeedEndpoint.handleSuccess(_ => attentionFeed(attention, feedPollInterval, heartbeats)))
    // $COVERAGE-ON$

  private[http] def attentionFeed(
      attention: PlantAttentionMonitor,
      pollInterval: FiniteDuration,
      heartbeats: ConnectionHeartbeats
  ): OxStreams.Pipe[String, AttentionProjection] =
    incoming =>
      val connection = UUID.randomUUID()
      Flow.tick(pollInterval).map { _ => heartbeats.touch(connection); attention.current }.debounceBy(_.measuredAt).merge(
        incoming.drain(),
        propagateDoneRight = true
      )

  private given circeConfiguration: CirceConfiguration =
    CirceConfiguration.default.withTransformMemberNames(encodedFieldName).withTransformConstructorNames(lowerCamel).withDiscriminator("kind")
  private given tapirConfiguration: TapirConfiguration =
    TapirConfiguration.default.copy(
      toEncodedName = encodedFieldName,
      discriminator = Some("kind"),
      // Only OpenAPI generation translates Tapir's discriminator names.
      // $COVERAGE-OFF$
      toDiscriminatorValue = name => lowerCamel(name.fullName.split('.').last.stripSuffix("$"))
      // $COVERAGE-ON$
    )

  // Types appear only in response bodies; no request decodes them here.
  // $COVERAGE-OFF$
  private given Codec[PlantId] = Codec.from(Decoder.decodeString.map(PlantId.apply), Encoder.encodeString.contramap(_.value))
  private given Codec[Instant] =
    Codec.from(Decoder.decodeString.emapTry(value => Try(Instant.parse(value))), Encoder.encodeString.contramap(_.toString))
  private given Codec[WateringSampleCount] =
    Codec.from(Decoder.failedWithMessage("watering attention is output-only"), Encoder.encodeInt.contramap(value => value))
  private given Codec[FiniteDuration] =
    Codec.from(Decoder.failedWithMessage("watering duration is output-only"), Encoder.encodeString.contramap(_.toMillis.toString))
  // $COVERAGE-ON$
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

  private def lowerCamel(name: String) = name.substring(0, 1).toLowerCase + name.substring(1)
