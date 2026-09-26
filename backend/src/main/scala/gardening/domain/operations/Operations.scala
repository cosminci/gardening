package gardening.domain.operations

import cats.syntax.either.*
import cats.syntax.eq.*
import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.PesticideStore
import gardening.domain.plants.*
import gardening.domain.substrate.SubstrateStore
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import monocle.syntax.all.*

import language.experimental.captureChecking

import java.time.Instant
import scala.annotation.tailrec
import scala.util.chaining.scalaUtilChainingOps

trait Operations:
  def getOperations(plant: PlantId, window: OperationWindow): GetOperationsResult
  def getOperationDateRange(plant: PlantId): GetOperationDateRangeResult
  def logOperation(plant: PlantId, date: Instant, details: OperationDetails): LogOperationResult
  def editOperation(operation: OperationId, details: OperationDetails): EditOperationResult
  def deleteOperation(operation: OperationId): DeleteOperationResult

object Operations:

  def make(using
      store: OperationStore^,
      plantStore: PlantStore^,
      substrateStore: SubstrateStore^,
      pesticideStore: PesticideStore^,
      idGen: IdGenerator^,
      lock: PlantUpdateLock^
  )(using
      log: Logger^,
      metrics: OperationsMetricsApi^
  ): Operations^{store, plantStore, substrateStore, pesticideStore, idGen, lock, log, metrics} =
    new LiveOperations

  private class LiveOperations(using
      store: OperationStore^,
      plantStore: PlantStore^,
      substrateStore: SubstrateStore^,
      pesticideStore: PesticideStore^,
      idGen: IdGenerator^,
      lock: PlantUpdateLock^
  )(using log: Logger^, metrics: OperationsMetricsApi^) extends Operations:

    override def getOperations(plant: PlantId, window: OperationWindow): GetOperationsResult =
      store.getOperations(plant, window).tap:
        case GetOperationsResult.ReadFailed(reason) => log.error("get operations", reason)
        case _                                      => ()

    override def getOperationDateRange(plant: PlantId): GetOperationDateRangeResult =
      store.getOperationDateRange(plant).tap:
        case GetOperationDateRangeResult.ReadFailed(reason) => log.error("get operation date range", reason)
        case _                                              => ()

    override def logOperation(plant: PlantId, date: Instant, details: OperationDetails): LogOperationResult = lock.exclusively:
      plantStore.getPlant(plant) match
        case GetPlantResult.RecordMissing      => LogOperationResult.PlantMissing
        case GetPlantResult.ReadFailed(reason) =>
          LogOperationResult.LoggingFailed(reason).tap(_ => log.error("log operation", reason))
        case GetPlantResult.Read(found) if found.details.status === PlantStatus.Archived => LogOperationResult.PlantArchived
        case GetPlantResult.Read(_)                                                      =>
          val operation = Operation(OperationId(idGen.nextId()), plant, date, details)
          validateOperationDetails(details) match
            case Left(reason) => LogOperationResult.LoggingFailed(reason)
            case Right(_)     =>
              store.addOperation(operation) match
                case res: LogOperationResult.Logged =>
                  log.info(s"operation logged $operation")
                  updatePlantIfOperationIsLatestRepot(operation)
                    .compensateWith(store.removeOperation(operation.id))
                    .tap(_.left.foreach(reason => log.error("log operation", reason)))
                    .tap(_.foreach(_ => recordOperationMetrics(plant, details)))
                    .fold(LogOperationResult.LoggingFailed.apply, _ => res)
                case failure: LogOperationResult.LoggingFailed => failure.tap(_ => log.error("log operation", failure.reason))
                case other                                     => other

    override def editOperation(operation: OperationId, details: OperationDetails): EditOperationResult = lock.exclusively:
      store.getOperation(operation) match
        case GetOperationResult.ReadFailed(reason) => EditOperationResult.EditFailed(reason).tap(_ => log.error("edit operation", reason))
        case GetOperationResult.RecordMissing      => EditOperationResult.OperationMissing
        case GetOperationResult.Read(found) if !sameType(found.details, details) =>
          EditOperationResult.OperationTypeMismatch
        case GetOperationResult.Read(found) =>
          validateOperationDetails(details) match
            case Left(reason) => EditOperationResult.EditFailed(reason)
            case Right(_)     =>
              store.updateOperation(operation, details) match
                case res @ EditOperationResult.Edited(edited) =>
                  log.info(s"operation edited $edited")
                  updatePlantIfOperationIsLatestRepot(edited)
                    .compensateWith(store.restoreOperation(found))
                    .tap(_.left.foreach(reason => log.error("edit operation", reason)))
                    .fold(EditOperationResult.EditFailed.apply, _ => res)
                case failure: EditOperationResult.EditFailed => failure.tap(_ => log.error("edit operation", failure.reason))
                case other                                   => other

    override def deleteOperation(operation: OperationId): DeleteOperationResult = lock.exclusively:
      val outcome =
        for
          found   <- readOperation(operation)
          _       <- rejectLatestRepot(found)
          deleted <- deleteFromStore(found.id)
        yield deleted.tap(_ => log.info(s"operation deleted $found"))
      outcome.merge

    private def readOperation(operation: OperationId): Either[DeleteOperationResult, Operation] =
      store.getOperation(operation) match
        case GetOperationResult.Read(found)        => found.asRight
        case GetOperationResult.RecordMissing      => DeleteOperationResult.OperationMissing.asLeft
        case GetOperationResult.ReadFailed(reason) =>
          DeleteOperationResult.DeleteFailed(reason).asLeft.tap(_ => log.error("delete operation", reason))

    private def rejectLatestRepot(operation: Operation): Either[DeleteOperationResult, Unit] =
      operation.details match
        case _: OperationDetails.Care  => ().asRight
        case _: OperationDetails.Repot =>
          isLatestRepot(operation) match
            case Right(true)  => DeleteOperationResult.CannotDeleteLatestRepot.asLeft
            case Right(false) => ().asRight
            case Left(reason) => DeleteOperationResult.DeleteFailed(reason).asLeft.tap(_ => log.error("delete operation", reason))

    private def deleteFromStore(operation: OperationId): Either[DeleteOperationResult, DeleteOperationResult] =
      store.removeOperation(operation) match
        case OperationCompensationResult.Compensated                => DeleteOperationResult.Deleted.asRight
        case OperationCompensationResult.CompensationFailed(reason) =>
          DeleteOperationResult.DeleteFailed(reason).asLeft.tap(_ => log.error("delete operation", reason))

    private enum PlantUpdateInterruption:
      case NotLatestRepot
      case Failed(reason: Throwable)

    private def recordOperationMetrics(plant: PlantId, details: OperationDetails): Unit =
      details match
        case care: OperationDetails.Care =>
          care.actions.foreach(metrics.incrementAction)
          metrics.incrementMoisture(care.moisture)
          care.pesticides.foreach(metrics.incrementPesticide)
        case repot: OperationDetails.Repot =>
          metrics.incrementRepot(plant)
          repot.substrate.parts.foreach(part => metrics.incrementSubstrateComponent(part.componentId))

    private def validateOperationDetails(details: OperationDetails) =
      details match
        case op: OperationDetails.Care  => validatePesticides(op.pesticides)
        case op: OperationDetails.Repot => validateSubstrateComponents(op.substrate)

    private def validatePesticides(selected: Set[PesticideId]) =
      if selected.isEmpty then ().asRight
      else
        pesticideStore.getPesticides match
          case CatalogReadResult.Read(pesticides) =>
            val known   = pesticides.filter(_.status === PesticideStatus.Active).map(_.id).toSet
            val missing = selected.diff(known)
            Either.cond(missing.isEmpty, (), unknownPesticides(missing))
          case CatalogReadResult.ReadFailed(reason) => reason.asLeft

    private def validateSubstrateComponents(substrate: Substrate) =
      substrateStore.getSubstrateComponents match
        case CatalogReadResult.Read(components) =>
          val known   = components.filter(_.status === SubstrateComponentStatus.Active).map(_.id).toSet
          val missing = substrate.parts.map(_.componentId).toSet.diff(known)
          Either.cond(missing.isEmpty, (), unknownSubstrateComponents(missing))
        case CatalogReadResult.ReadFailed(reason) => reason.asLeft

    private def unknownPesticides(ids: Set[PesticideId]) =
      RuntimeException(s"unknown pesticide ids: ${ids.toVector.map(_.value).sorted.mkString(", ")}")

    private def unknownSubstrateComponents(ids: Set[SubstrateComponentId]) =
      RuntimeException(s"unknown substrate component ids: ${ids.toVector.map(_.value).sorted.mkString(", ")}")

    private def updatePlantIfOperationIsLatestRepot(operation: Operation) =
      operation.details match
        case _: OperationDetails.Care      => ().asRight
        case repot: OperationDetails.Repot =>
          for
            isLatest <- isLatestRepot(operation).leftMap(PlantUpdateInterruption.Failed.apply)
            _        <- isLatest.orSkip
            plant    <- readPlant(operation.plantId)
            updated  <- updatePlant(plant.focus(_.details.substrate).replace(repot.substrate))
          yield updated

    private def isLatestRepot(operation: Operation): Either[Throwable, Boolean] =
      readLatestRepot(operation.plantId).map(_.exists(_.id.value.equals(operation.id.value)))

    private def readLatestRepot(plant: PlantId): Either[Throwable, Option[Operation]] =
      @tailrec
      def read(window: OperationWindow): Either[Throwable, Option[Operation]] =
        store.getOperations(plant, window) match
          case GetOperationsResult.Read(page) =>
            page.operations.find(isRepot) match
              case found @ Some(_)          => found.asRight
              case None if page.hasNextPage =>
                val nextOffset = (window.offset + window.size).refineUnsafe[GreaterEqual[0]]
                read(OperationWindow(nextOffset, window.size))
              case None => none[Operation].asRight
          case GetOperationsResult.ReadFailed(reason) => reason.asLeft

      read(OperationWindow(offset = 0, size = 10))

    private def isRepot(operation: Operation) =
      operation.details match
        case _: OperationDetails.Repot => true
        case _: OperationDetails.Care  => false

    private def readPlant(plant: PlantId) =
      plantStore.getPlant(plant) match
        case GetPlantResult.Read(found)        => found.asRight
        case GetPlantResult.ReadFailed(reason) => PlantUpdateInterruption.Failed(reason).asLeft
        case GetPlantResult.RecordMissing      => PlantUpdateInterruption.Failed(RuntimeException("cannot read plant after repot")).asLeft

    private def updatePlant(plant: Plant) =
      plantStore.updatePlant(plant) match
        case UpdatePlantResult.Updated              => ().asRight
        case UpdatePlantResult.UpdateFailed(reason) => PlantUpdateInterruption.Failed(reason).asLeft

    extension (result: Either[PlantUpdateInterruption, Unit])
      private def compensateWith(compensationResult: => OperationCompensationResult): Either[Throwable, Unit] =
        result match
          case Right(_) | Left(PlantUpdateInterruption.NotLatestRepot) => ().asRight
          case Left(PlantUpdateInterruption.Failed(primary))           =>
            compensationResult match
              case OperationCompensationResult.Compensated                      => primary.asLeft
              case OperationCompensationResult.CompensationFailed(compensation) =>
                RuntimeException("repot persistence and compensation failed", primary).tap(_.addSuppressed(compensation)).asLeft

    extension (condition: Boolean)
      private def orSkip =
        Either.cond(condition, (), PlantUpdateInterruption.NotLatestRepot)

    private def sameType(first: OperationDetails, second: OperationDetails) =
      (first, second) match
        case (_: OperationDetails.Care, _: OperationDetails.Care)   => true
        case (_: OperationDetails.Repot, _: OperationDetails.Repot) => true
        case _                                                      => false
