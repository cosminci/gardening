package gardening.adapters.sqlite

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.operations.*
import gardening.ports.OperationStore
import gardening.domain.operations.AddOperationResult.*
import gardening.domain.operations.EditOperationResult.*
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

        val result =
          for
            _      <- rows.headOption.toRight(GetOperationDateRangeResult.PlantMissing)
            parsed <- rows.flatMap(_.date).traverse(parseOperationDate).left.map(GetOperationDateRangeResult.ReadFailed.apply)
            first  <- parsed.headOption.toRight(GetOperationDateRangeResult.Read(OperationDateRange.Empty))
          yield
            val earliest = parsed.foldLeft(first)((previous, current) => if current.isBefore(previous) then current else previous)
            val latest   = parsed.foldLeft(first)((previous, current) => if current.isAfter(previous) then current else previous)
            GetOperationDateRangeResult.Read(OperationDateRange.Recorded(earliest, latest))
        result.merge

      catch case error: SqlException => GetOperationDateRangeResult.ReadFailed(error)

    private def selectOperationsForPlant(plantId: String, window: OperationWindow) =
      val readSize: Int = window.size + 1
      val offset: Int   = window.offset
      sql"""select id, plant_id, date, kind, payload
            from operation
            where plant_id = $plantId
            order by date desc, id desc
            limit $readSize offset $offset"""

    override def getLatestRepot(plant: PlantId): GetLatestRepotResult =
      try
        val row = connect(transactor)(selectLatestRepot(plant.value).query[OperationRow].run().headOption)
        GetLatestRepotResult.Read(row.map(r => trust(toOperation(r))))
      catch case error: SqlException => GetLatestRepotResult.ReadFailed(error)

    private def selectLatestRepot(plantId: String) =
      sql"""select id, plant_id, date, kind, payload
            from operation
            where plant_id = $plantId and kind = 'Repot'
            order by date desc, id desc
            limit 1"""

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

    override def addOperation(operation: Operation): AddOperationResult =
      try
        transact(transactor)(insertOperationRow(operation).update.run())
        Logged(operation.id)
      catch case e: SqlException => LoggingFailed(e)

    override def logRepot(id: OperationId, plant: PlantId, date: Instant, details: OperationDetails.Repot): AddOperationResult =
      try
        transact(transactor):
          val _ = insertOperationRow(Operation(id, plant, date, details)).update.run()
          val _ = syncPlantSubstrateIfLatestRepot(plant, id.value, operationDateFormatter.format(date), details.substrate).update.run()
          Logged(id)
      catch case error: SqlException => LoggingFailed(error)

    private def insertOperationRow(operation: Operation) =
      val (operationKind, payload) = encodeOperationDetails(operation.details)
      val storedDate               = operationDateFormatter.format(operation.date)
      sql"""insert into operation (id, plant_id, date, kind, payload)
            values (${operation.id.value}, ${operation.plantId.value}, $storedDate, $operationKind, $payload)"""

    // A repot only overwrites the plant's stored substrate when no other recorded repot outranks it
    // by (date, id); this makes "sync the plant if this is the latest repot" a single atomic statement
    // instead of a read-then-write race between this store and PlantStore.
    private def syncPlantSubstrateIfLatestRepot(plant: PlantId, operationId: String, storedDate: String, substrate: Substrate) =
      sql"""update plant set substrate = ${substrate.asJson.noSpaces}
            where id = ${plant.value}
              and not exists (
                select 1 from operation
                where plant_id = ${plant.value} and kind = 'Repot'
                  and (date > $storedDate or (date = $storedDate and id > $operationId))
              )"""

    override def updateOperation(operation: OperationId, details: OperationDetails): EditOperationResult =
      try
        transact(transactor):
          val queryResult = updateOperationRow(operation.value, details).query[OperationRow].run().headOption
          queryResult.fold[EditOperationResult](OperationMissing): row =>
            Edited(trust(toOperation(row)))
      catch case e: SqlException => EditFailed(e)

    override def editRepot(operation: OperationId, details: OperationDetails.Repot): EditOperationResult =
      try
        transact(transactor):
          updateOperationRow(operation.value, details).query[OperationRow].run().headOption match
            case None      => OperationMissing
            case Some(row) =>
              val _ = syncPlantSubstrateIfLatestRepot(PlantId(row.plantId), row.id, row.date, details.substrate).update.run()
              Edited(trust(toOperation(row)))
      catch case error: SqlException => EditFailed(error)

    override def removeOperation(operation: OperationId): OperationCompensationResult =
      try
        transact(transactor)(sql"delete from operation where id = ${operation.value}".update.run()).pipe(_ => OperationCompensationResult.Compensated)
      catch case error: SqlException => OperationCompensationResult.CompensationFailed(error)

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
        (care.actions.toList.sortBy(_.toString), care.pesticides.toList.sortBy(_.value.toString), care.moisture, care.maybeNote)

    private given Decoder[OperationDetails.Repot] =
      Decoder.forProduct2("substrate", "note")(OperationDetails.Repot.apply)

    private given Encoder[OperationDetails.Repot] =
      Encoder.forProduct2("substrate", "note")(repot => (repot.substrate, repot.maybeNote))

  private case class OperationRow(id: String, plantId: String, date: String, kind: String, payload: String) derives DbCodec
  private case class OperationDateRow(date: Option[String]) derives DbCodec
