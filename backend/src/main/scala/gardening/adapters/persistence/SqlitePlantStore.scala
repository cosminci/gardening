package gardening.adapters.persistence

import cats.syntax.eq.*
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

    override def getPlants: GetPlantsResult =
      try
        val plants = trust(connect(transactor)(selectPlants.query[PlantRow].run()).traverse(toPlant))
        GetPlantsResult.Read(plants.filter(_.details.status === PlantStatus.Active))
      catch case error: SqlException => GetPlantsResult.ReadFailed(error)

    private def selectPlants =
      sql"select id, species, nickname, location, substrate, status from plant"

    override def getPlant(id: PlantId): GetPlantResult =
      try
        connect(transactor)(selectPlant(id.value).query[PlantRow].run().headOption) match
          case None      => GetPlantResult.RecordMissing
          case Some(row) => GetPlantResult.Read(trust(toPlant(row)))
      catch case error: SqlException => GetPlantResult.ReadFailed(error)

    private def selectPlant(id: String) =
      sql"select id, species, nickname, location, substrate, status from plant where id = $id"

    private def toPlant(row: PlantRow) =
      for
        substrate <- decode[Substrate](row.substrate).leftMap(invalidSubstrate)
        // The schema check constrains every stored status to a PlantStatus name.
        // $COVERAGE-OFF$
        status <- PlantStatus.values
          .find(_.toString.equals(row.status))
          .toRight(RuntimeException(s"invalid stored plant status: ${row.status}"))
      // $COVERAGE-ON$
      yield
        val details = PlantDetails(Species(row.species), row.nickname.map(Nickname.apply), Location(row.location), substrate, status)
        Plant(PlantId(row.id), details)

    override def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult =
      try
        val rows       = connect(transactor)(selectOperationsForPlant(plantId.value, window).query[OperationRow].run())
        val operations = trust(rows.traverse(toOperation))
        GetOperationsResult.Read(OperationPage(operations.take(window.size), operations.size > window.size))
      catch case error: SqlException => GetOperationsResult.ReadFailed(error)

    override def getAttentionSamples(size: WateringSampleSize): GetAttentionSamplesResult =
      try
        val rows    = connect(transactor)(selectAttentionSamples(size).query[AttentionSampleRow].run())
        val samples = trust(rows.traverse(toAttentionSample))
        GetAttentionSamplesResult.Read(samples.filter(_.plant.details.status === PlantStatus.Active))
      catch
        case error: SqlException       => GetAttentionSamplesResult.ReadFailed(error)
        case error: DatabaseCorruption => GetAttentionSamplesResult.ReadFailed(error)

    private def selectAttentionSamples(size: WateringSampleSize) =
      val sampleSize: Int = size
      sql"""select plant.id,
                   plant.species,
                   plant.nickname,
                   plant.location,
                   plant.substrate,
                   plant.status,
                   coalesce((
                     select json_group_array(watering.date)
                     from (
                       select operation.date
                       from operation
                       where plant.status = 'Active'
                         and operation.plant_id = plant.id
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
            order by plant.rowid"""

    private def toAttentionSample(row: AttentionSampleRow) =
      val plantRow    = PlantRow(row.id, row.species, row.nickname, row.location, row.substrate, row.status)
      val storedDates = decodeWateringDates(row.wateringDates)
      for
        plant         <- toPlant(plantRow)
        wateringDates <- storedDates.traverse(parseOperationDate)
      yield PlantAttentionSample(plant, wateringDates)

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
          insertOperationRow(operation).update.run().pipe(_ => Logged(operation.id))
      catch case e: SqlException => LoggingFailed(e)

    private def insertOperationRow(operation: Operation) =
      val (operationKind, payload) = encodeOperationDetails(operation.details)
      val storedDate               = operationDateFormatter.format(operation.date)
      sql"insert into operation (id, plant_id, date, kind, payload) values (${operation.id.value}, ${operation.plantId.value}, $storedDate, $operationKind, $payload)"

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

    override def updatePlant(plant: Plant): UpdatePlantResult =
      try
        transact(transactor)(updatePlantRow(plant).update.run()) match
          case 1 => UpdatePlantResult.Updated
          case _ => UpdatePlantResult.UpdateFailed(RuntimeException(s"plant not found while updating: ${plant.id.value}"))
      catch case error: SqlException => UpdatePlantResult.UpdateFailed(error)

    override def getSubstrateComponents: CatalogReadResult[SubstrateComponent] =
      try
        CatalogReadResult.Read(
          trust(
            connect(transactor)(sql"select id, name, info from substrate_component order by rowid".query[ComponentRow].run()).traverse(toComponent)
          )
        )
      catch case error: SqlException => CatalogReadResult.ReadFailed(error)

    override def addSubstrateComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent] =
      try
        val row = ComponentRow(component.id.value.toString, component.data.name.value, component.data.maybeInfo.map(_.value))
        transact(transactor):
          sql"insert into substrate_component (id, name, info) values (${row.id}, ${row.name}, ${row.info})".update.run()
        CatalogAddResult.Added(component)
      catch case error: SqlException => CatalogAddResult.AddFailed(error)

    override def editSubstrateComponent(
        id: SubstrateComponentId,
        data: SubstrateComponentData
    ): CatalogEditResult[SubstrateComponent] =
      try
        transact(transactor):
          sql"""update substrate_component set name = ${data.name.value}, info = ${data.maybeInfo.map(_.value)}
               where id = ${id.value.toString}""".update.run()
        match
          case 1 => CatalogEditResult.Edited(SubstrateComponent(id, data))
          case _ => CatalogEditResult.RecordMissing
      catch case error: SqlException => CatalogEditResult.EditFailed(error)

    override def getPesticides: CatalogReadResult[Pesticide] =
      try
        CatalogReadResult.Read(
          trust(connect(transactor)(sql"select id, name, type, info from pesticide order by rowid".query[PesticideRow].run()).traverse(toPesticide))
        )
      catch case error: SqlException => CatalogReadResult.ReadFailed(error)

    override def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide] =
      try
        val data = pesticide.data
        transact(transactor):
          sql"""insert into pesticide (id, name, type, info)
               values (${pesticide.id.value.toString}, ${data.name.value}, ${data.pesticideType.toString}, ${data.maybeInfo.map(
              _.value
            )})""".update.run()
        CatalogAddResult.Added(pesticide)
      catch case error: SqlException => CatalogAddResult.AddFailed(error)

    override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
      try
        transact(transactor):
          sql"""update pesticide set name = ${data.name.value}, type = ${data.pesticideType.toString}, info = ${data.maybeInfo.map(_.value)}
               where id = ${id.value.toString}""".update.run()
        match
          case 1 => CatalogEditResult.Edited(Pesticide(id, data))
          case _ => CatalogEditResult.RecordMissing
      catch case error: SqlException => CatalogEditResult.EditFailed(error)

    private def toComponent(row: ComponentRow) =
      SubstrateComponentId
        .parse(row.id)
        .toRight(RuntimeException(s"invalid substrate component id: ${row.id}"))
        .map: id =>
          SubstrateComponent(id, SubstrateComponentData(NomenclatureName(row.name), row.info.map(NomenclatureInfo.apply)))

    private def toPesticide(row: PesticideRow) =
      for
        id <- PesticideId
          .parse(row.id)
          .toRight(RuntimeException(s"invalid pesticide id: ${row.id}"))
        // The schema check mirrors PesticideType; extending it requires a migration before persistence.
        // $COVERAGE-OFF$
        pesticideType <- Try(PesticideType.valueOf(row.pesticideType)).toEither.left.map: error =>
          RuntimeException(s"invalid pesticide type: ${row.pesticideType}", error)
      // $COVERAGE-ON$
      yield Pesticide(id, PesticideData(NomenclatureName(row.name), pesticideType, row.info.map(NomenclatureInfo.apply)))

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
           where id = ${plant.id.value}"""

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
  private case class AttentionSampleRow(
      id: String,
      species: String,
      nickname: Option[String],
      location: String,
      substrate: String,
      status: String,
      wateringDates: String
  ) derives DbCodec
  private case class ComponentRow(id: String, name: String, info: Option[String]) derives DbCodec
  private case class PesticideRow(id: String, name: String, pesticideType: String, info: Option[String]) derives DbCodec
