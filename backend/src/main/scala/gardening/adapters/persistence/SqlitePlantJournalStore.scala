package gardening.adapters.persistence

import cats.syntax.eq.*
import cats.syntax.traverse.*
import com.augustnagro.magnum.*
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
        val plants = trust(connect(transactor)(selectPlants.query[PlantRow].run()).traverse(toPlant))
        GetPlantsResult.Read(plants.filter(_.details.status === PlantStatus.Active))
      catch case error: SqlException => GetPlantsResult.ReadFailed(error)

    private def selectPlants: Frag =
      sql"select id, species, nickname, location, substrate, status from plant"

    override def getPlant(id: PlantId): GetPlantResult =
      try
        connect(transactor)(selectPlant(id.value).query[PlantRow].run().headOption) match
          case None      => GetPlantResult.RecordMissing
          case Some(row) => GetPlantResult.Read(trust(toPlant(row)))
      catch case error: SqlException => GetPlantResult.ReadFailed(error)

    private def selectPlant(id: String): Frag =
      sql"select id, species, nickname, location, substrate, status from plant where id = $id"

    private def toPlant(row: PlantRow) =
      for
        substrate <- decodeSubstrate(row.substrate)
        // The schema check constrains every stored status to a PlantStatus name.
        // $COVERAGE-OFF$
        status <- PlantStatus.values
          .find(_.toString.equals(row.status))
          .toRight(RuntimeException(s"invalid stored plant status: ${row.status}"))
      // $COVERAGE-ON$
      yield
        val details = PlantDetails(Species(row.species), row.nickname.map(Nickname.apply), Location(row.location), substrate, status)
        Plant(PlantId(row.id), details)

    override def getOperations(plantId: PlantId): GetOperationsResult =
      try
        val operations = trust(connect(transactor)(selectOperationsForPlant(plantId.value).query[OperationRow].run()).traverse(toOperation))
        GetOperationsResult.Read(operations)
      catch case error: SqlException => GetOperationsResult.ReadFailed(error)

    private def selectOperationsForPlant(plantId: String): Frag =
      sql"select id, plant_id, date, kind, payload from operation where plant_id = $plantId"

    override def getOperation(id: OperationId): GetOperationResult =
      try
        connect(transactor)(selectOperation(id.value).query[OperationRow].run().headOption) match
          case None      => GetOperationResult.RecordMissing
          case Some(row) => GetOperationResult.Read(trust(toOperation(row)))
      catch case error: SqlException => GetOperationResult.ReadFailed(error)

    private def selectOperation(id: String): Frag =
      sql"select id, plant_id, date, kind, payload from operation where id = $id"

    private def toOperation(row: OperationRow) =
      for
        date    <- Try(Instant.parse(row.date)).toEither.left.map(_ => RuntimeException(s"invalid stored operation date: ${row.date}"))
        details <- row.payload.decodeStoredOperation(row.kind)
      yield Operation(OperationId(row.id), PlantId(row.plantId), date, details)

    override def addOperation(operation: Operation): LogOperationResult =
      try
        transact(transactor):
          insertOperationRow(operation).update.run().pipe(_ => Logged(operation.id))
      catch case e: SqlException => LoggingFailed(e)

    private def insertOperationRow(operation: Operation): Frag =
      val operationKind = storedOperationKind(operation.details)
      val payload       = encodeStoredOperation(operation.details)
      sql"insert into operation (id, plant_id, date, kind, payload) values (${operation.id.value}, ${operation.plantId.value}, ${operation.date.toString}, $operationKind, $payload)"

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
        val operationKind = storedOperationKind(operation.details)
        val payload       = encodeStoredOperation(operation.details)
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
               values (${pesticide.id.value.toString}, ${data.name.value}, ${data.pesticideType.value}, ${data.maybeInfo.map(
              _.value
            )})""".update.run()
        CatalogAddResult.Added(pesticide)
      catch case error: SqlException => CatalogAddResult.AddFailed(error)

    override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
      try
        transact(transactor):
          sql"""update pesticide set name = ${data.name.value}, type = ${data.pesticideType.value}, info = ${data.maybeInfo.map(_.value)}
               where id = ${id.value.toString}""".update.run()
        match
          case 1 => CatalogEditResult.Edited(Pesticide(id, data))
          case _ => CatalogEditResult.RecordMissing
      catch case error: SqlException => CatalogEditResult.EditFailed(error)

    private def toComponent(row: ComponentRow): Either[Throwable, SubstrateComponent] =
      SubstrateComponentId
        .parse(row.id)
        .toRight(RuntimeException(s"invalid substrate component id: ${row.id}"))
        .map: id =>
          SubstrateComponent(id, SubstrateComponentData(NomenclatureName(row.name), row.info.map(NomenclatureInfo.apply)))

    private def toPesticide(row: PesticideRow): Either[Throwable, Pesticide] =
      PesticideId
        .parse(row.id)
        .toRight(RuntimeException(s"invalid pesticide id: ${row.id}"))
        .map: id =>
          Pesticide(id, PesticideData(NomenclatureName(row.name), PesticideType(row.pesticideType), row.info.map(NomenclatureInfo.apply)))

    @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
    private def trust[A](decoded: Either[Throwable, A]) =
      // Writes are validated before persistence; a decode failure is an invariant violation.
      decoded.left.map(DatabaseCorruption.apply).toTry.get

    private def updatePlantRow(plant: Plant): Frag =
      val details = plant.details
      sql"""update plant
           set species = ${details.species.value},
               nickname = ${details.maybeNickname.map(_.value)},
               location = ${details.location.value},
               substrate = ${encodeSubstrate(details.substrate).noSpaces},
               status = ${details.status.toString}
           where id = ${plant.id.value}"""

    private def updateOperationRow(operationId: String, details: OperationDetails): Frag =
      val operationKind = storedOperationKind(details)
      val payload       = encodeStoredOperation(details)
      sql"update operation set kind = $operationKind, payload = $payload where id = $operationId returning id, plant_id, date, kind, payload"

  private case class PlantRow(id: String, species: String, nickname: Option[String], location: String, substrate: String, status: String)
      derives DbCodec

  private case class OperationRow(id: String, plantId: String, date: String, kind: String, payload: String) derives DbCodec
  private case class ComponentRow(id: String, name: String, info: Option[String]) derives DbCodec
  private case class PesticideRow(id: String, name: String, pesticideType: String, info: Option[String]) derives DbCodec
