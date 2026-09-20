package gardening.domain

import cats.syntax.either.*
import monocle.syntax.all.*

import language.experimental.captureChecking

import java.util.concurrent.locks.ReentrantLock
import scala.util.chaining.scalaUtilChainingOps

trait PlantJournal:
  def getPlants: Either[JournalReadFailure, Vector[Plant]]
  def getOperations(plantId: PlantId): Either[JournalReadFailure, Vector[Operation]]
  def logOperation(plantId: PlantId, op: OperationDetails): LogOperationResult
  def editOperation(id: OperationId, details: OperationDetails): EditOperationResult

object PlantJournal:

  def make(using store: PlantJournalStore^, idGen: IdGenerator^, clock: Clock^): PlantJournal^{store, idGen, clock} =
    new LivePlantJournal

  private class LivePlantJournal(using store: PlantJournalStore^, idGen: IdGenerator^, clock: Clock^) extends PlantJournal:
    private val operationMutex = ReentrantLock()

    override def getPlants: Either[JournalReadFailure, Vector[Plant]] =
      store.getPlants

    override def getOperations(plantId: PlantId): Either[JournalReadFailure, Vector[Operation]] =
      store.getOperations(plantId)

    override def logOperation(plantId: PlantId, op: OperationDetails): LogOperationResult = operationMutex.exclusively:
      val operation = Operation(OperationId(idGen.nextId()), plantId, clock.now(), op)
      store.addOperation(operation) match
        case res: LogOperationResult.Logged =>
          updatePlantAfterLogging(operation)
            .compensateWith(store.removeOperation(operation.id))
            .leftMap(LogOperationResult.LoggingFailed.apply)
            .fold(identity, _ => res)
        case failure => failure

    override def editOperation(id: OperationId, details: OperationDetails): EditOperationResult = operationMutex.exclusively:
      store.getOperation(id) match
        case Left(JournalReadFailure.Corrupted(corruptions))           => EditOperationResult.Corrupted(corruptions)
        case Left(JournalReadFailure.ReadFailed(reason))               => EditOperationResult.EditFailed(reason)
        case Left(JournalReadFailure.RecordMissing)                    => EditOperationResult.OperationMissing
        case Right(operation) if !sameType(operation.details, details) => EditOperationResult.OperationTypeMismatch
        case Right(operation)                                          =>
          store.updateOperation(id, details) match
            case res @ EditOperationResult.Edited(edited) =>
              updatePlantIfOperationIsLatestRepot(edited)
                .compensateWith(store.restoreOperation(operation))
                .leftMap(EditOperationResult.EditFailed.apply)
                .fold(identity, _ => res)
            case failure => failure

    private enum PlantUpdateInterruption:
      case NotLatestRepot
      case Failed(reason: Throwable)

    private def updatePlantAfterLogging(operation: Operation) =
      operation.details match
        case _: OperationDetails.Care      => ().asRight
        case repot: OperationDetails.Repot =>
          for
            plant   <- readPlant(operation.plantId)
            updated <- store.updatePlant(plant.focus(_.details.substrate).replace(repot.substrate)).leftMap(PlantUpdateInterruption.Failed.apply)
          yield updated

    private def updatePlantIfOperationIsLatestRepot(operation: Operation) =
      operation.details match
        case _: OperationDetails.Care      => ().asRight
        case repot: OperationDetails.Repot =>
          for
            operations <- readOperations(operation.plantId)
            _          <- isLatestRepot(operation, operations.filterNot(_.id.value.equals(operation.id.value))).orSkip
            plant      <- readPlant(operation.plantId)
            updated    <- store.updatePlant(plant.focus(_.details.substrate).replace(repot.substrate)).leftMap(PlantUpdateInterruption.Failed.apply)
          yield updated

    private def readOperations(plantId: PlantId) =
      store
        .getOperations(plantId)
        .leftMap(failure => PlantUpdateInterruption.Failed(readFailure("cannot read operations after editing repot", failure)))

    private def readPlant(plantId: PlantId) =
      store
        .getPlant(plantId)
        .leftMap(failure => PlantUpdateInterruption.Failed(readFailure("cannot read plant after repot", failure)))

    private def isLatestRepot(operation: Operation, others: Vector[Operation]) =
      others.forall: other =>
        other.details match
          case _: OperationDetails.Repot => operation.date.isAfter(other.date)
          case _                         => true

    private def readFailure(context: String, result: JournalReadFailure) =
      result match
        case JournalReadFailure.ReadFailed(reason) => reason
        case JournalReadFailure.RecordMissing      => RuntimeException(context)
        case JournalReadFailure.Corrupted(details) =>
          val reasons = details.map(_.reason)
          RuntimeException(context, reasons.head).tap(error => reasons.tail.foreach(error.addSuppressed))

    extension (result: Either[PlantUpdateInterruption, Unit])
      private def compensateWith(compensationResult: => Either[Throwable, Unit]): Either[Throwable, Unit] =
        result match
          case Right(_) | Left(PlantUpdateInterruption.NotLatestRepot) => ().asRight
          case Left(PlantUpdateInterruption.Failed(primary))           =>
            compensationResult.leftMap: compensation =>
              RuntimeException("repot persistence and compensation failed", primary).tap(_.addSuppressed(compensation))
            .flatMap(_ => primary.asLeft)

    extension (condition: Boolean)
      private def orSkip: Either[PlantUpdateInterruption, Unit] =
        Either.cond(condition, (), PlantUpdateInterruption.NotLatestRepot)

    extension (mutex: ReentrantLock)
      private def exclusively[A](operation: => A): A =
        mutex.lock()
        try operation
        finally mutex.unlock()

    private def sameType(first: OperationDetails, second: OperationDetails) =
      (first, second) match
        case (_: OperationDetails.Care, _: OperationDetails.Care)   => true
        case (_: OperationDetails.Repot, _: OperationDetails.Repot) => true
        case _                                                      => false
