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
import gardening.domain.JournalReadFailure.*
import gardening.domain.LogOperationResult.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval

import java.time.Instant
import scala.util.Try
import scala.util.chaining.scalaUtilChainingOps

object SqlitePlantJournalStore:

  def make(transactor: Transactor): PlantJournalStore = LiveSqlitePlantJournalStore(transactor)

  private class LiveSqlitePlantJournalStore(transactor: Transactor) extends PlantJournalStore:

    override def getPlants: Either[JournalReadFailure, Vector[Plant]] =
      try
        connect(transactor)(selectPlants.query[PlantRow].run())
          .traverse(toPlant)
          .map(_.filter(_.details.status === PlantStatus.Active))
          .leftMap(JournalReadFailure.Corrupted.apply)
          .toEither
      catch case error: SqlException => ReadFailed(error).asLeft

    private def selectPlants: Frag =
      sql"select id, species, nickname, location, substrate, status from plant"

    override def getPlant(id: PlantId): Either[JournalReadFailure, Plant] =
      try
        connect(transactor)(selectPlant(id.value).query[PlantRow].run().headOption) match
          case None      => RecordMissing.asLeft
          case Some(row) => toPlant(row).leftMap(JournalReadFailure.Corrupted.apply).toEither
      catch case error: SqlException => ReadFailed(error).asLeft

    private def selectPlant(id: String): Frag =
      sql"select id, species, nickname, location, substrate, status from plant where id = $id"

    private def toPlant(row: PlantRow): ValidatedNel[JournalCorruption, Plant] =
      val record          = JournalRecord.Plant(PlantId(row.id))
      val substrateResult = decodeSubstrate(row.substrate).leftMap(JournalCorruption(record, _)).toValidatedNel

      // The schema check constrains every stored status to a PlantStatus name.
      // $COVERAGE-OFF$
      val statusResult = PlantStatus.values
        .find(_.toString.equals(row.status))
        .toRight(JournalCorruption(record, RuntimeException(s"invalid stored plant status: ${row.status}")))
        .toValidatedNel
      // $COVERAGE-ON$

      (substrateResult, statusResult).mapN: (substrate, status) =>
        Plant(
          PlantId(row.id),
          PlantDetails(
            Species(row.species),
            row.nickname.map(Nickname.apply),
            Location(row.location),
            substrate,
            status
          )
        )

    override def getOperations(plantId: PlantId): Either[JournalReadFailure, Vector[Operation]] =
      try
        connect(transactor)(selectOperationsForPlant(plantId.value).query[OperationRow].run())
          .traverse(toOperation)
          .leftMap(JournalReadFailure.Corrupted.apply)
          .toEither
      catch case error: SqlException => ReadFailed(error).asLeft

    private def selectOperationsForPlant(plantId: String): Frag =
      sql"select id, plant_id, date, kind, payload from operation where plant_id = $plantId"

    override def getOperation(id: OperationId): Either[JournalReadFailure, Operation] =
      try
        connect(transactor)(selectOperation(id.value).query[OperationRow].run().headOption) match
          case None      => RecordMissing.asLeft
          case Some(row) => toOperation(row).leftMap(JournalReadFailure.Corrupted.apply).toEither
      catch case error: SqlException => ReadFailed(error).asLeft

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
          updateOperationRow(id.value, details)
            .query[OperationRow]
            .run()
            .headOption
            .fold[EditOperationResult](OperationMissing): row =>
              toOperation(row).fold(EditOperationResult.Corrupted.apply, Edited.apply)
      catch case e: SqlException => EditFailed(e)

    override def removeOperation(id: OperationId): Either[Throwable, Unit] =
      try transact(transactor)(sql"delete from operation where id = ${id.value}".update.run()).pipe(_ => ().asRight)
      catch case error: SqlException => error.asLeft

    override def restoreOperation(operation: Operation): Either[Throwable, Unit] =
      try
        val kind    = StoredOperationPayload.kind(operation.details)
        val payload = StoredOperationPayload.encode(operation.details)
        transact(transactor)(sql"update operation set kind = $kind, payload = $payload where id = ${operation.id.value}".update.run()) match
          case 1 => ().asRight
          case _ => RuntimeException(s"operation not found while restoring: ${operation.id.value}").asLeft
      catch case error: SqlException => error.asLeft

    override def updatePlant(plant: Plant): Either[Throwable, Unit] =
      try
        transact(transactor)(updatePlantRow(plant).update.run()) match
          case 1 => ().asRight
          case _ => RuntimeException(s"plant not found while updating: ${plant.id.value}").asLeft
      catch case error: SqlException => error.asLeft

    private def updatePlantRow(plant: Plant): Frag =
      val details = plant.details
      sql"""update plant
           set species = ${details.species.value},
               nickname = ${details.maybeNickname.map(_.value)},
               location = ${details.location.value},
               substrate = ${encodeSubstrate(details.substrate)},
               status = ${details.status.toString}
           where id = ${plant.id.value}"""

    private def updateOperationRow(operationId: String, details: OperationDetails): Frag =
      val kind    = StoredOperationPayload.kind(details)
      val payload = StoredOperationPayload.encode(details)
      sql"update operation set kind = $kind, payload = $payload where id = $operationId returning id, plant_id, date, kind, payload"

  private def decodeSubstrate(encoded: String): Either[Throwable, Substrate] =
    for
      parts     <- encoded.split(",", -1).toList.traverse(decodePart)
      substrate <- Substrate.of(parts).leftMap(reason => invalidSubstrate(reason.toString))
    yield substrate

  private def decodePart(encoded: String): Either[Throwable, SubstratePart] =
    encoded.split(":", -1).toList match
      case component :: share :: Nil =>
        for
          parsedComponent <- SubstrateComponent.values
            .find(_.toString.equals(component))
            .toRight(invalidSubstrate(s"unknown component: $component"))
          parsedShare <- share.toIntOption
            .flatMap(_.refineOption[Interval.Closed[1, 100]])
            .toRight(invalidSubstrate(s"invalid share: $share"))
        yield SubstratePart(parsedComponent, parsedShare)
      case _ => invalidSubstrate(s"malformed part: $encoded").asLeft

  private def invalidSubstrate(reason: String): RuntimeException =
    RuntimeException(s"invalid stored substrate: $reason")

  private def encodeSubstrate(substrate: Substrate): String =
    substrate.parts.map(part => s"${part.component}:${part.share: Int}").mkString(",")

  private case class PlantRow(id: String, species: String, nickname: Option[String], location: String, substrate: String, status: String)
      derives DbCodec

  private case class OperationRow(id: String, plantId: String, date: String, kind: String, payload: String) derives DbCodec
