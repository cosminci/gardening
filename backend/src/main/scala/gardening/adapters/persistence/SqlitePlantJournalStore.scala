package gardening.adapters.persistence

import cats.data.ValidatedNel
import cats.syntax.apply.*
import cats.syntax.either.*
import cats.syntax.eq.*
import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.adapters.persistence.StoredOperationPayload.*
import gardening.domain.*
import gardening.domain.EditOperationResult.*
import gardening.domain.LogOperationResult.*
import java.time.Instant
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps

object SqlitePlantJournalStore:

  def make(transactor: Transactor): PlantJournalStore = LiveSqlitePlantJournalStore(transactor)

  private class LiveSqlitePlantJournalStore(transactor: Transactor) extends PlantJournalStore:

    override def getPlants: GetPlantsResult =
      try
        connect(transactor)(selectPlants.query[PlantRow].run())
          .traverse(toPlant)
          .map(_.filter(_.details.status === PlantStatus.Active))
          .fold(GetPlantsResult.Corrupted.apply, GetPlantsResult.Read.apply)
      catch case error: SqlException => GetPlantsResult.ReadFailed(error)

    private def selectPlants: Frag =
      sql"select id, species, nickname, location, substrate, status from plant"

    override def getPlant(id: PlantId): GetPlantResult =
      try
        connect(transactor)(selectPlant(id.value).query[PlantRow].run().headOption) match
          case None      => GetPlantResult.RecordMissing
          case Some(row) => toPlant(row).fold(GetPlantResult.Corrupted.apply, GetPlantResult.Read.apply)
      catch case error: SqlException => GetPlantResult.ReadFailed(error)

    private def selectPlant(id: String): Frag =
      sql"select id, species, nickname, location, substrate, status from plant where id = $id"

    private def toPlant(row: PlantRow): ValidatedNel[JournalCorruption, Plant] =
      val record          = JournalRecord.Plant(PlantId(row.id))
      val substrateResult = StoredSubstrate.decodeString(row.substrate).leftMap(JournalCorruption(record, _)).toValidatedNel

      // The schema check constrains every stored status to a PlantStatus name.
      // $COVERAGE-OFF$
      val statusResult = PlantStatus.values
        .find(_.toString.equals(row.status))
        .toRight(JournalCorruption(record, RuntimeException(s"invalid stored plant status: ${row.status}")))
        .toValidatedNel
      // $COVERAGE-ON$

      (substrateResult, statusResult).mapN: (substrate, status) =>
        val details = PlantDetails(Species(row.species), row.nickname.map(Nickname.apply), Location(row.location), substrate, status)
        Plant(PlantId(row.id), details)

    override def getOperations(plantId: PlantId): GetOperationsResult =
      try
        connect(transactor)(selectOperationsForPlant(plantId.value).query[OperationRow].run())
          .traverse(toOperation)
          .fold(GetOperationsResult.Corrupted.apply, GetOperationsResult.Read.apply)
      catch case error: SqlException => GetOperationsResult.ReadFailed(error)

    private def selectOperationsForPlant(plantId: String): Frag =
      sql"select id, plant_id, date, kind, payload from operation where plant_id = $plantId"

    override def getOperation(id: OperationId): GetOperationResult =
      try
        connect(transactor)(selectOperation(id.value).query[OperationRow].run().headOption) match
          case None      => GetOperationResult.RecordMissing
          case Some(row) => toOperation(row).fold(GetOperationResult.Corrupted.apply, GetOperationResult.Read.apply)
      catch case error: SqlException => GetOperationResult.ReadFailed(error)

    private def selectOperation(id: String): Frag =
      sql"select id, plant_id, date, kind, payload from operation where id = $id"

    private def toOperation(row: OperationRow): ValidatedNel[JournalCorruption, Operation] =
      val record     = JournalRecord.Operation(OperationId(row.id))
      val dateResult = Try(Instant.parse(row.date)).toEither.left
        .map(_ => JournalCorruption(record, RuntimeException(s"invalid stored operation date: ${row.date}")))
        .toValidatedNel
      val detailResult = row.payload.decode(row.kind).leftMap(_.map(reason => JournalCorruption(record, reason)))

      (dateResult, detailResult).mapN: (date, details) =>
        Operation(OperationId(row.id), PlantId(row.plantId), date, details)

    override def addOperation(operation: Operation): LogOperationResult =
      try
        transact(transactor):
          insertOperationRow(operation).update.run().pipe(_ => Logged(operation.id))
      catch case e: SqlException => LoggingFailed(e)

    private def insertOperationRow(operation: Operation): Frag =
      val kind    = StoredOperationPayload.kind(operation.details)
      val payload = StoredOperationPayload.encode(operation.details)
      sql"insert into operation (id, plant_id, date, kind, payload) values (${operation.id.value}, ${operation.plantId.value}, ${operation.date.toString}, $kind, $payload)"

    override def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult =
      try
        transact(transactor):
          val queryResult = updateOperationRow(id.value, details)
            .query[OperationRow]
            .run()
            .headOption
          queryResult.fold[EditOperationResult](OperationMissing): row =>
            toOperation(row).fold(EditOperationResult.Corrupted.apply, Edited.apply)
      catch case e: SqlException => EditFailed(e)

    override def removeOperation(id: OperationId): OperationCompensationResult =
      try transact(transactor)(sql"delete from operation where id = ${id.value}".update.run()).pipe(_ => OperationCompensationResult.Compensated)
      catch case error: SqlException => OperationCompensationResult.CompensationFailed(error)

    override def restoreOperation(operation: Operation): OperationCompensationResult =
      try
        val kind    = StoredOperationPayload.kind(operation.details)
        val payload = StoredOperationPayload.encode(operation.details)
        transact(transactor)(sql"update operation set kind = $kind, payload = $payload where id = ${operation.id.value}".update.run()) match
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
      SqliteCatalogQueries.getSubstrateComponents(transactor)

    override def addSubstrateComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent] =
      SqliteCatalogQueries.addSubstrateComponent(transactor, component)

    override def editSubstrateComponent(
        id: SubstrateComponentId,
        data: SubstrateComponentData
    ): CatalogEditResult[SubstrateComponent] =
      SqliteCatalogQueries.editSubstrateComponent(transactor, id, data)

    override def getPesticides: CatalogReadResult[Pesticide] =
      SqliteCatalogQueries.getPesticides(transactor)

    override def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide] =
      SqliteCatalogQueries.addPesticide(transactor, pesticide)

    override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
      SqliteCatalogQueries.editPesticide(transactor, id, data)

    private def updatePlantRow(plant: Plant): Frag =
      val details = plant.details
      sql"""update plant
           set species = ${details.species.value},
               nickname = ${details.maybeNickname.map(_.value)},
               location = ${details.location.value},
               substrate = ${StoredSubstrate.encodeString(details.substrate)},
               status = ${details.status.toString}
           where id = ${plant.id.value}"""

    private def updateOperationRow(operationId: String, details: OperationDetails): Frag =
      val kind    = StoredOperationPayload.kind(details)
      val payload = StoredOperationPayload.encode(details)
      sql"update operation set kind = $kind, payload = $payload where id = $operationId returning id, plant_id, date, kind, payload"

  private case class PlantRow(id: String, species: String, nickname: Option[String], location: String, substrate: String, status: String)
      derives DbCodec

  private case class OperationRow(id: String, plantId: String, date: String, kind: String, payload: String) derives DbCodec
