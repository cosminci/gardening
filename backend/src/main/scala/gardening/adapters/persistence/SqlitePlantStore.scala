package gardening.adapters.persistence

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.domain.journal.*
import gardening.domain.journal.EditOperationResult.*
import gardening.domain.journal.LogOperationResult.*
import io.circe.{Codec, Decoder, DecodingFailure, Encoder}
import io.circe.parser.decode
import io.circe.syntax.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps

object SqlitePlantStore:

  private val operationDateFormatter = DateTimeFormatterBuilder().appendInstant(9).toFormatter

  def make(transactor: Transactor): PlantJournalStore & PlantAttentionStore = LiveSqlitePlantStore(transactor)

  private class LiveSqlitePlantStore(transactor: Transactor) extends PlantJournalStore, PlantAttentionStore:

    override def getPlants(status: PlantStatus): GetPlantsResult =
      try
        val rows = connect(transactor):
          sql"select id, species, nickname, location, substrate, status from plant where status = ${status.toString} order by rowid".query[
            PlantRow
          ].run()
        val plants = trust(rows.traverse(toPlant))
        GetPlantsResult.Read(plants)
      catch
        case error: SqlException       => GetPlantsResult.ReadFailed(error)
        case error: DatabaseCorruption => GetPlantsResult.ReadFailed(error)

    override def getArchivedCount: ArchivedCountResult =
      try
        connect(transactor)(sql"select count(*) from plant where status = 'Archived'".query[Long].run().headOption) match
          case Some(count) => ArchivedCountResult.Counted(count)
          // An aggregate without GROUP BY always returns exactly one row.
          // $COVERAGE-OFF$
          case None => ArchivedCountResult.ReadFailed(DatabaseCorruption(IllegalStateException("archived plant count query returned no row")))
          // $COVERAGE-ON$
      catch case error: SqlException => ArchivedCountResult.ReadFailed(error)

    override def archivePlant(id: PlantId): ArchivePlantResult =
      try
        transact(transactor):
          sql"update plant set status = 'Archived' where id = ${id.value} and status = 'Active'".update.run() match
            case 1 => ArchivePlantResult.Archived
            case _ =>
              sql"select status from plant where id = ${id.value}".query[String].run().headOption match
                case Some(_) => ArchivePlantResult.AlreadyArchived
                case None    => ArchivePlantResult.PlantMissing
      catch case error: SqlException => ArchivePlantResult.ArchiveFailed(error)

    override def getPlant(id: PlantId): GetPlantResult =
      try
        connect(transactor)(selectPlant(id.value).query[PlantRow].run().headOption) match
          case None      => GetPlantResult.RecordMissing
          case Some(row) => GetPlantResult.Read(trust(toPlant(row)))
      catch case error: SqlException => GetPlantResult.ReadFailed(error)

    override def updatePlant(plant: Plant): UpdatePlantResult =
      try
        transact(transactor)(updatePlantRow(plant).update.run()) match
          case 1 => UpdatePlantResult.Updated
          case _ => UpdatePlantResult.UpdateFailed(RuntimeException(s"plant not found while updating: ${plant.id.value}"))
      catch case error: SqlException => UpdatePlantResult.UpdateFailed(error)

    private def selectPlant(id: String) =
      sql"select id, species, nickname, location, substrate, status from plant where id = $id"

    private def toPlant(row: PlantRow) =
      for
        substrate <- decode[Substrate](row.substrate).leftMap(invalidSubstrate)
        status    <- PlantStatus.values
          .find(_.toString.equals(row.status))
          .toRight(
            // The schema check constrains every stored status to a PlantStatus name.
            // $COVERAGE-OFF$
            RuntimeException(s"invalid stored plant status: ${row.status}")
            // $COVERAGE-ON$
          )
      yield
        val details = PlantDetails(Species(row.species), row.nickname.map(Nickname.apply), Location(row.location), substrate, status)
        Plant(PlantId(row.id), details)

    override def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult =
      try
        val rows       = connect(transactor)(selectOperationsForPlant(plantId.value, window).query[OperationRow].run())
        val operations = trust(rows.traverse(toOperation))
        GetOperationsResult.Read(OperationPage(operations.take(window.size), operations.size > window.size))
      catch case error: SqlException => GetOperationsResult.ReadFailed(error)

    override def getOperationDateRange(plantId: PlantId): GetOperationDateRangeResult =
      try
        val rows = connect(transactor):
          sql"""select operation.date from plant
                left join operation on operation.plant_id = plant.id
                where plant.id = ${plantId.value}""".query[OperationDateRow].run()
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

    override def getAttentionSamples(size: WateringSampleSize): GetAttentionSamplesResult =
      try
        val rows    = connect(transactor)(selectAttentionSamples(size).query[AttentionSampleRow].run())
        val samples = trust(rows.traverse(toAttentionSample))
        GetAttentionSamplesResult.Read(samples)
      catch
        case error: SqlException       => GetAttentionSamplesResult.ReadFailed(error)
        case error: DatabaseCorruption => GetAttentionSamplesResult.ReadFailed(error)

    private def selectAttentionSamples(size: WateringSampleSize) =
      val sampleSize: Int = size
      sql"""select plant.id,
                   coalesce((
                     select json_group_array(watering.date order by watering.date desc, watering.id desc)
                     from (
                       select operation.id, operation.date
                       from operation
                       where operation.plant_id = plant.id
                         and operation.kind = 'Care'
                         and exists (
                           select 1
                           from json_each(operation.payload, '$$.actions')
                           where value = 'Watered'
                         )
                       order by operation.date desc, operation.id desc
                       limit $sampleSize
                     ) watering
                   ), json('[]')) as watering_dates
            from plant
            where plant.status = 'Active'
            order by plant.rowid"""

    private def toAttentionSample(row: AttentionSampleRow) =
      val storedDates = decodeWateringDates(row.wateringDates)
      for
        wateringDates   <- storedDates.traverse(parseOperationDate)
        wateringHistory <- WateringHistory
          .from(wateringDates)
          .leftMap:
            message =>
              // The SQL query limits each history to the maximum representable length.
              // $COVERAGE-OFF$
              DatabaseCorruption(RuntimeException(s"invalid stored watering history: $message"))
              // $COVERAGE-ON$
      yield PlantAttentionSample(PlantId(row.id), wateringHistory)

    private def decodeWateringDates(value: String) =
      trust(decode[Vector[String]](value))

    private def selectOperationsForPlant(plantId: String, window: OperationWindow) =
      val readSize: Int = window.size + 1
      val offset: Int   = window.offset
      sql"""select id, plant_id, date, kind, payload
            from operation
            where plant_id = $plantId
            order by date desc, id desc
            limit $readSize offset $offset"""

    override def getOperation(id: OperationId): GetOperationResult =
      try
        connect(transactor)(selectOperation(id.value).query[OperationRow].run().headOption) match
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

    override def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult =
      try
        transact(transactor):
          val queryResult = updateOperationRow(id.value, details)
            .query[OperationRow]
            .run()
            .headOption
          queryResult.fold[EditOperationResult](OperationMissing): row =>
            Edited(trust(toOperation(row)))
      catch case e: SqlException => EditFailed(e)

    override def removeOperation(id: OperationId): OperationCompensationResult =
      try transact(transactor)(sql"delete from operation where id = ${id.value}".update.run()).pipe(_ => OperationCompensationResult.Compensated)
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

    private def updatePlantRow(plant: Plant) =
      val details = plant.details
      sql"""update plant
           set species = ${details.species.value},
               nickname = ${details.maybeNickname.map(_.value)},
               location = ${details.location.value},
               substrate = ${details.substrate.asJson.noSpaces},
               status = ${details.status.toString}
           where id = ${plant.id.value} and status = ${details.status.toString}"""

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

    private def invalidSubstrate(reason: io.circe.Error) =
      RuntimeException(s"invalid stored substrate: ${reason.getMessage}", reason)

    private given Codec[Note] = Codec.from(
      Decoder.decodeString.map(Note.apply),
      Encoder.encodeString.contramap(_.value)
    )

    private given Codec[SubstrateComponentId] = Codec.from(
      Decoder.decodeString.emap(value => SubstrateComponentId.parse(value).toRight(s"invalid component id: $value")),
      Encoder.encodeString.contramap(_.value.toString)
    )

    private given Codec[PesticideId] = Codec.from(
      Decoder.decodeString.emap(value => PesticideId.parse(value).toRight(s"invalid pesticide id: $value")),
      Encoder.encodeString.contramap(_.value.toString)
    )

    private given Codec[Percentage] = Codec.from(
      Decoder.decodeInt.emap(value =>
        value
          .refineOption[Interval.Closed[1, 100]]
          .toRight(s"invalid share: $value")
      ),
      Encoder.encodeInt.contramap(value => value: Int)
    )

    private given Codec[ActionType] = Codec.from(
      Decoder.decodeString.emap(value => ActionType.values.find(_.toString.equals(value)).toRight(s"invalid action: $value")),
      Encoder.encodeString.contramap(_.toString)
    )

    private given Codec[MoistureLevel] = Codec.from(
      Decoder.decodeString.emap(value => MoistureLevel.values.find(_.toString.equals(value)).toRight(s"invalid moisture: $value")),
      Encoder.encodeString.contramap(_.toString)
    )

    private given Decoder[SubstratePart] =
      Decoder.forProduct2("component", "share")(SubstratePart.apply)

    private given Encoder[SubstratePart] =
      Encoder.forProduct2("component", "share")(part => (part.componentId, part.share))

    private given Decoder[Substrate] =
      Decoder.decodeList[SubstratePart].emap(parts => Substrate.of(parts).leftMap(_.toString))

    private given Encoder[Substrate] =
      Encoder.encodeList[SubstratePart].contramap(_.parts)

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

  private case class PlantRow(id: String, species: String, nickname: Option[String], location: String, substrate: String, status: String)
      derives DbCodec

  private case class OperationRow(id: String, plantId: String, date: String, kind: String, payload: String) derives DbCodec
  private case class OperationDateRow(date: Option[String]) derives DbCodec
  private case class AttentionSampleRow(
      id: String,
      wateringDates: String
  ) derives DbCodec
