package gardening.adapters.persistence

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.operations.*
import gardening.domain.operations.EditOperationResult.*
import gardening.domain.operations.LogOperationResult.*
import io.circe.{Codec, Decoder, DecodingFailure, Encoder}
import io.circe.parser.decode
import io.circe.syntax.*
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps

import Codecs.given

object SqliteOperationStore:

  private val operationDateFormatter = DateTimeFormatterBuilder().appendInstant(9).toFormatter

  def make(transactor: Transactor): OperationStore = LiveSqliteOperationStore(transactor)

  private class LiveSqliteOperationStore(transactor: Transactor) extends OperationStore:

    override def getOperations(plant: PlantId, window: OperationWindow): GetOperationsResult =
      try
        val rows       = connect(transactor)(selectOperationsForPlant(plant.value, window).query[OperationRow].run())
        val operations = trust(rows.traverse(toOperation))
        GetOperationsResult.Read(OperationPage(operations.take(window.size), operations.size > window.size))
      catch case error: SqlException => GetOperationsResult.ReadFailed(error)

    override def getOperationDateRange(plant: PlantId): GetOperationDateRangeResult =
      try
        val rows = connect(transactor):
          sql"""select operation.date from plant
                left join operation on operation.plant_id = plant.id
                where plant.id = ${plant.value}""".query[OperationDateRow].run()
        rows.headOption match
          case None    => GetOperationDateRangeResult.PlantMissing
          case Some(_) =>
            val dates = rows.flatMap(_.date).traverse(parseOperationDate).map: parsed =>
              parsed.headOption match
                case None        => OperationDateRange.Empty
                case Some(first) =>
                  val earliest = parsed.foldLeft(first)((previous, current) => if current.isBefore(previous) then current else previous)
                  val latest   = parsed.foldLeft(first)((previous, current) => if current.isAfter(previous) then current else previous)
                  OperationDateRange.Recorded(earliest, latest)
            dates.fold(GetOperationDateRangeResult.ReadFailed.apply, GetOperationDateRangeResult.Read.apply)
      catch case error: SqlException => GetOperationDateRangeResult.ReadFailed(error)

    private def selectOperationsForPlant(plantId: String, window: OperationWindow) =
      val readSize: Int = window.size + 1
      val offset: Int   = window.offset
      sql"""select id, plant_id, date, kind, payload
            from operation
            where plant_id = $plantId
            order by date desc, id desc
            limit $readSize offset $offset"""

    override def getOperation(operation: OperationId): GetOperationResult =
      try
        connect(transactor)(selectOperation(operation.value).query[OperationRow].run().headOption) match
          case None      => GetOperationResult.RecordMissing
          case Some(row) => GetOperationResult.Read(trust(toOperation(row)))
      catch case error: SqlException => GetOperationResult.ReadFailed(error)

    private def selectOperation(id: String) =
      sql"select id, plant_id, date, kind, payload from operation where id = $id"

    private def toOperation(row: OperationRow) =
      for
        date    <- parseOperationDate(row.date)
        details <- decodeOperationDetails(row.kind, row.payload)
      yield Operation(OperationId(row.id), PlantId(row.plantId), date, details)

    private def parseOperationDate(value: String) =
      Try(Instant.parse(value)).toEither.left.map(_ => RuntimeException(s"invalid stored operation date: $value"))

    override def addOperation(operation: Operation): LogOperationResult =
      try
        transact(transactor):
          insertOperationRow(operation).update.run() match
            case 1 => Logged(operation.id)
            case _ =>
              sql"select status from plant where id = ${operation.plantId.value}".query[String].run().headOption match
                case Some(_) => LogOperationResult.PlantArchived
                case None    => LogOperationResult.PlantMissing
      catch case e: SqlException => LoggingFailed(e)

    private def insertOperationRow(operation: Operation) =
      val (operationKind, payload) = encodeOperationDetails(operation.details)
      val storedDate               = operationDateFormatter.format(operation.date)
      sql"""insert into operation (id, plant_id, date, kind, payload)
           select ${operation.id.value}, ${operation.plantId.value}, $storedDate, $operationKind, $payload
           where exists (select 1 from plant where id = ${operation.plantId.value} and status = 'Active')"""

    override def updateOperation(operation: OperationId, details: OperationDetails): EditOperationResult =
      try
        transact(transactor):
          val queryResult = updateOperationRow(operation.value, details)
            .query[OperationRow]
            .run()
            .headOption
          queryResult.fold[EditOperationResult](OperationMissing): row =>
            Edited(trust(toOperation(row)))
      catch case e: SqlException => EditFailed(e)

    override def removeOperation(operation: OperationId): OperationCompensationResult =
      try
        transact(transactor)(sql"delete from operation where id = ${operation.value}".update.run()).pipe(_ => OperationCompensationResult.Compensated)
      catch case error: SqlException => OperationCompensationResult.CompensationFailed(error)

    override def restoreOperation(operation: Operation): OperationCompensationResult =
      try
        val (operationKind, payload) = encodeOperationDetails(operation.details)
        transact(transactor)(
          sql"update operation set kind = $operationKind, payload = $payload where id = ${operation.id.value}".update.run()
        ) match
          case 1 => OperationCompensationResult.Compensated
          case _ => OperationCompensationResult.CompensationFailed(RuntimeException(s"operation not found while restoring: ${operation.id.value}"))
      catch case error: SqlException => OperationCompensationResult.CompensationFailed(error)

    @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
    private def trust[A](decoded: Either[Throwable, A]) =
      // Writes are validated before persistence; a decode failure is an invariant violation.
      decoded.left.map(DatabaseCorruption.apply).toTry.get

    private def updateOperationRow(operationId: String, details: OperationDetails) =
      val (operationKind, payload) = encodeOperationDetails(details)
      sql"update operation set kind = $operationKind, payload = $payload where id = $operationId returning id, plant_id, date, kind, payload"

    private def encodeOperationDetails(details: OperationDetails) =
      details match
        case care: OperationDetails.Care   => "Care"  -> care.asJson.noSpaces
        case repot: OperationDetails.Repot => "Repot" -> repot.asJson.noSpaces

    private def decodeOperationDetails(kind: String, payload: String) =
      kind match
        case "Care"  => decode[OperationDetails.Care](payload).leftMap(invalidOperationPayload)
        case "Repot" => decode[OperationDetails.Repot](payload).leftMap(invalidOperationPayload)
        // The schema check rejects operation kinds other than Care and Repot.
        // $COVERAGE-OFF$
        case _ => invalidOperationPayload(DecodingFailure("unknown operation kind", ops = Nil)).asLeft
        // $COVERAGE-ON$

    private def invalidOperationPayload(reason: io.circe.Error) =
      RuntimeException(s"invalid stored operation payload: ${reason.getMessage}", reason)

    private given Codec[Note] = Codec.from(
      Decoder.decodeString.map(Note.apply),
      Encoder.encodeString.contramap(_.value)
    )

    private given Codec[PesticideId] = Codec.from(
      Decoder.decodeString.emap(value => PesticideId.parse(value).toRight(s"invalid pesticide id: $value")),
      Encoder.encodeString.contramap(_.value.toString)
    )

    private given Codec[ActionType] = Codec.from(
      Decoder.decodeString.emap(value => ActionType.values.find(_.toString.equals(value)).toRight(s"invalid action: $value")),
      Encoder.encodeString.contramap(_.toString)
    )

    private given Codec[MoistureLevel] = Codec.from(
      Decoder.decodeString.emap(value => MoistureLevel.values.find(_.toString.equals(value)).toRight(s"invalid moisture: $value")),
      Encoder.encodeString.contramap(_.toString)
    )

    private given Decoder[OperationDetails.Care] =
      Decoder.forProduct4("actions", "pesticides", "moisture", "note"):
        (actions: List[ActionType], pesticides: List[PesticideId], moisture: MoistureLevel, note: Option[Note]) =>
          OperationDetails.Care(actions.toSet, pesticides.toSet, moisture, note)

    private given Encoder[OperationDetails.Care] =
      Encoder.forProduct4("actions", "pesticides", "moisture", "note"): care =>
        (
          care.actions.toList.sortBy(_.toString),
          care.pesticides.toList.sortBy(_.value.toString),
          care.moisture,
          care.maybeNote
        )

    private given Decoder[OperationDetails.Repot] =
      Decoder.forProduct2("substrate", "note")(OperationDetails.Repot.apply)

    private given Encoder[OperationDetails.Repot] =
      Encoder.forProduct2("substrate", "note")(repot => (repot.substrate, repot.maybeNote))

  private case class OperationRow(id: String, plantId: String, date: String, kind: String, payload: String) derives DbCodec
  private case class OperationDateRow(date: Option[String]) derives DbCodec
