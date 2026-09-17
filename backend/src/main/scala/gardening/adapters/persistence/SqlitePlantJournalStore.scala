package gardening.adapters.persistence

import cats.data.{Validated, ValidatedNel}
import cats.syntax.apply.*
import cats.syntax.either.*
import cats.syntax.eq.*
import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.adapters.persistence.StoredOperationDetails.*
import gardening.domain.*
import gardening.domain.EditOperationResult.*
import gardening.domain.JournalReadResult.*
import gardening.domain.LogOperationResult.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval

import scala.util.chaining.scalaUtilChainingOps

object SqlitePlantJournalStore:

  def make(transactor: Transactor): PlantJournalStore = LiveSqlitePlantJournalStore(transactor)

  private class LiveSqlitePlantJournalStore(transactor: Transactor) extends PlantJournalStore:

    override def getPlants: JournalReadResult[Vector[Plant]] =
      try
        connect(transactor)(selectPlants.query[PlantRow].run()).traverse(toPlant) match
          case Validated.Valid(plants) =>
            Read(plants.filter(_.status === PlantStatus.Active))
          case Validated.Invalid(details) => JournalReadResult.Corrupted(details)
      catch case error: SqlException => ReadFailed(error)

    private def selectPlants: Frag =
      sql"select id, species, nickname, location, substrate, status from plant"

    override def getPlant(id: PlantId): JournalReadResult[Plant] =
      try
        connect(transactor)(selectPlant(id.value).query[PlantRow].run().headOption) match
          case None      => RecordMissing
          case Some(row) =>
            toPlant(row) match
              case Validated.Valid(plant)     => Read(plant)
              case Validated.Invalid(details) => JournalReadResult.Corrupted(details)
      catch case error: SqlException => ReadFailed(error)

    private def selectPlant(id: String): Frag =
      sql"select id, species, nickname, location, substrate, status from plant where id = $id"

    private def toPlant(row: PlantRow): ValidatedNel[JournalCorruption, Plant] =
      val record          = JournalRecord.Plant(PlantId(row.id))
      val substrateResult = decodeSubstrate(row.substrate).left
        .map(reason => JournalCorruption(record, RuntimeException(s"invalid stored substrate: $reason")))
        .toValidatedNel

      val statusResult = PlantStatus.values
        .find(_.toString.equals(row.status))
        .toRight(JournalCorruption(record, RuntimeException(s"invalid stored plant status: ${row.status}")))
        .toValidatedNel

      (substrateResult, statusResult).mapN: (substrate, status) =>
        Plant(
          PlantId(row.id),
          Species(row.species),
          row.nickname.map(Nickname.apply),
          Location(row.location),
          substrate,
          status
        )

    override def getOperations(plantId: PlantId): JournalReadResult[Vector[Operation]] =
      try
        connect(transactor)(selectOperationsForPlant(plantId.value).query[OperationRow].run()).traverse(toOperation) match
          case Validated.Valid(operations) => Read(operations)
          case Validated.Invalid(details)  => JournalReadResult.Corrupted(details)
      catch case error: SqlException => ReadFailed(error)

    private def selectOperationsForPlant(plantId: String): Frag =
      sql"select id, plant_id, details from operation where plant_id = $plantId"

    override def getOperation(id: OperationId): JournalReadResult[Operation] =
      try
        connect(transactor)(selectOperation(id.value).query[OperationRow].run().headOption) match
          case None      => RecordMissing
          case Some(row) =>
            toOperation(row) match
              case Validated.Valid(operation) => Read(operation)
              case Validated.Invalid(details) => JournalReadResult.Corrupted(details)
      catch case error: SqlException => ReadFailed(error)

    private def selectOperation(id: String): Frag =
      sql"select id, plant_id, details from operation where id = $id"

    private def toOperation(row: OperationRow): ValidatedNel[JournalCorruption, Operation] =
      val record = JournalRecord.Operation(OperationId(row.id))
      row.details.decode
        .leftMap(_.map(reason => JournalCorruption(record, reason)))
        .map: details =>
          Operation(OperationId(row.id), PlantId(row.plantId), details)

    override def addOperation(operation: Operation): LogOperationResult =
      try
        transact(transactor):
          insertOperationRow(operation).update.run().pipe(_ => Logged(operation.id))
      catch case e: SqlException => LoggingFailed(e)

    private def insertOperationRow(operation: Operation): Frag =
      val details = StoredOperationDetails.encode(operation.details)
      sql"insert into operation (id, plant_id, details) values (${operation.id.value}, ${operation.plantId.value}, $details)"

    override def updateOperation(id: OperationId, details: OperationDetails): EditOperationResult =
      try
        transact(transactor):
          updateOperationRow(id.value, details)
            .query[String]
            .run()
            .headOption
            .map(plantId => Edited(Operation(id, PlantId(plantId), details)))
            .getOrElse(OperationMissing)
      catch case e: SqlException => EditFailed(e)

    override def updatePlant(plant: Plant): Unit =
      transact(transactor):
        val _ = updatePlantRow(plant).update.run()

    private def updatePlantRow(plant: Plant): Frag =
      sql"update plant set substrate = ${encodeSubstrate(plant.substrate)} where id = ${plant.id.value}"

    private def updateOperationRow(operationId: String, details: OperationDetails): Frag =
      val encodedDetails = StoredOperationDetails.encode(details)
      sql"update operation set details = $encodedDetails where id = $operationId returning plant_id"

  private def decodeSubstrate(encoded: String): Either[SubstrateError, Substrate] =
    for
      parts     <- encoded.split(",", -1).toList.traverse(decodePart)
      substrate <- Substrate.of(parts)
    yield substrate

  private def decodePart(encoded: String): Either[SubstrateError, SubstratePart] =
    encoded.split(":", -1).toList match
      case component :: share :: Nil =>
        for
          parsedComponent <- SubstrateComponent.values.find(_.toString.equals(component)).toRight(SubstrateError.Malformed)
          parsedShare     <- share.toIntOption.flatMap(_.refineOption[Interval.Closed[1, 100]]).toRight(SubstrateError.Malformed)
        yield SubstratePart(parsedComponent, parsedShare)
      case _ => Left(SubstrateError.Malformed)

  private def encodeSubstrate(substrate: Substrate): String =
    substrate.parts.map(part => s"${part.component}:${part.share: Int}").mkString(",")

  private case class PlantRow(id: String, species: String, nickname: Option[String], location: String, substrate: String, status: String)
      derives DbCodec

  private case class OperationRow(id: String, plantId: String, details: StoredOperationDetails) derives DbCodec
