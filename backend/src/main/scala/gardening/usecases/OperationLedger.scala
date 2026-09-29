package gardening.usecases

import cats.syntax.either.*
import cats.syntax.eq.*
import gardening.domain.*
import gardening.domain.operations.*
import gardening.ports.{OperationStore, PlantStore, SubstrateStore, PesticideStore, OperationLedgerMetricsApi}
import gardening.capabilities.{IdGenerator, PlantUpdateLock, Logger}
import gardening.domain.plants.*
import gardening.domain.pesticide.GetPesticidesResult
import gardening.domain.substrate.GetSubstrateComponentsResult

import language.experimental.captureChecking

import java.time.Instant
import scala.util.chaining.scalaUtilChainingOps

trait OperationLedger:
  def getOperations(plant: PlantId, window: OperationWindow): GetOperationsResult
  def getOperationDateRange(plant: PlantId): GetOperationDateRangeResult
  def logOperation(plant: PlantId, date: Instant, details: OperationDetails): LogOperationResult
  def editOperation(operation: OperationId, details: OperationDetails): EditOperationResult
  def deleteOperation(operation: OperationId): DeleteOperationResult

object OperationLedger:

  def make(using
      store: OperationStore^,
      plantStore: PlantStore^,
      substrateStore: SubstrateStore^,
      pesticideStore: PesticideStore^,
      idGen: IdGenerator^,
      lock: PlantUpdateLock^
  )(using
      log: Logger^,
      metrics: OperationLedgerMetricsApi^
  ): OperationLedger^{store, plantStore, substrateStore, pesticideStore, idGen, lock, log, metrics} =
    new LiveOperationLedger

  private class LiveOperationLedger(using
      store: OperationStore^,
      plantStore: PlantStore^,
      substrateStore: SubstrateStore^,
      pesticideStore: PesticideStore^,
      idGen: IdGenerator^,
      lock: PlantUpdateLock^
  )(using log: Logger^, metrics: OperationLedgerMetricsApi^) extends OperationLedger:

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
              recordOperation(operation) match
                case AddOperationResult.Logged(id) =>
                  log.info(s"operation logged $operation")
                  recordOperationMetrics(plant, details)
                  LogOperationResult.Logged(id)
                case AddOperationResult.LoggingFailed(reason) =>
                  LogOperationResult.LoggingFailed(reason).tap(_ => log.error("log operation", reason))

    private def recordOperation(operation: Operation): AddOperationResult =
      operation.details match
        case _: OperationDetails.Care      => store.addOperation(operation)
        case repot: OperationDetails.Repot => store.logRepot(operation.id, operation.plantId, operation.date, repot)

    override def editOperation(operation: OperationId, details: OperationDetails): EditOperationResult = lock.exclusively:
      store.getOperation(operation) match
        case GetOperationResult.ReadFailed(reason) => EditOperationResult.EditFailed(reason).tap(_ => log.error("edit operation", reason))
        case GetOperationResult.RecordMissing      => EditOperationResult.OperationMissing
        case GetOperationResult.Read(found) if !sameType(found.details, details) =>
          EditOperationResult.OperationTypeMismatch
        case GetOperationResult.Read(_) =>
          validateOperationDetails(details) match
            case Left(reason) => EditOperationResult.EditFailed(reason)
            case Right(_)     =>
              amendOperation(operation, details) match
                case res @ EditOperationResult.Edited(edited) =>
                  log.info(s"operation edited $edited")
                  res
                case failure: EditOperationResult.EditFailed => failure.tap(_ => log.error("edit operation", failure.reason))
                case other                                   => other

    private def amendOperation(operation: OperationId, details: OperationDetails): EditOperationResult =
      details match
        case care: OperationDetails.Care   => store.updateOperation(operation, care)
        case repot: OperationDetails.Repot => store.editRepot(operation, repot)

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
          case GetPesticidesResult.Read(pesticides) =>
            val known   = pesticides.filter(_.status === PesticideStatus.Active).map(_.id).toSet
            val missing = selected.diff(known)
            Either.cond(missing.isEmpty, (), unknownPesticides(missing))
          case GetPesticidesResult.ReadFailed(reason) => reason.asLeft

    private def validateSubstrateComponents(substrate: Substrate) =
      substrateStore.getSubstrateComponents match
        case GetSubstrateComponentsResult.Read(components) =>
          val known   = components.filter(_.status === SubstrateComponentStatus.Active).map(_.id).toSet
          val missing = substrate.parts.map(_.componentId).toSet.diff(known)
          Either.cond(missing.isEmpty, (), unknownSubstrateComponents(missing))
        case GetSubstrateComponentsResult.ReadFailed(reason) => reason.asLeft

    private def unknownPesticides(ids: Set[PesticideId]) =
      RuntimeException(s"unknown pesticide ids: ${ids.toVector.map(_.value).sorted.mkString(", ")}")

    private def unknownSubstrateComponents(ids: Set[SubstrateComponentId]) =
      RuntimeException(s"unknown substrate component ids: ${ids.toVector.map(_.value).sorted.mkString(", ")}")

    private def isLatestRepot(operation: Operation): Either[Throwable, Boolean] =
      store.getLatestRepot(operation.plantId) match
        case GetLatestRepotResult.Read(found)        => found.exists(_.id.value.equals(operation.id.value)).asRight
        case GetLatestRepotResult.ReadFailed(reason) => reason.asLeft

    private def sameType(first: OperationDetails, second: OperationDetails) =
      (first, second) match
        case (_: OperationDetails.Care, _: OperationDetails.Care)   => true
        case (_: OperationDetails.Repot, _: OperationDetails.Repot) => true
        case _                                                      => false
