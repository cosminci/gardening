package gardening.domain

import cats.data.NonEmptyList
import cats.syntax.either.*
import monocle.syntax.all.*

import language.experimental.captureChecking

import java.util.concurrent.locks.ReentrantLock
import scala.util.chaining.scalaUtilChainingOps

trait PlantJournal:
  def getPlants: GetPlantsResult
  def getOperations(plantId: PlantId): GetOperationsResult
  def logOperation(plantId: PlantId, op: OperationDetails): LogOperationResult
  def editOperation(id: OperationId, details: OperationDetails): EditOperationResult
  def getSubstrateComponents: CatalogReadResult[SubstrateComponent]
  def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent]
  def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent]
  def getPesticides: CatalogReadResult[Pesticide]
  def addPesticide(data: PesticideData): CatalogAddResult[Pesticide]
  def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide]

object PlantJournal:

  def make(using store: PlantJournalStore^, idGen: IdGenerator^, clock: Clock^): PlantJournal^{store, idGen, clock} =
    new LivePlantJournal

  private class LivePlantJournal(using store: PlantJournalStore^, idGen: IdGenerator^, clock: Clock^) extends PlantJournal:
    private val operationMutex = ReentrantLock()

    override def getPlants: GetPlantsResult = store.getPlants

    override def getOperations(plantId: PlantId): GetOperationsResult = store.getOperations(plantId)

    override def getSubstrateComponents: CatalogReadResult[SubstrateComponent] = store.getSubstrateComponents

    override def addSubstrateComponent(data: SubstrateComponentData): CatalogAddResult[SubstrateComponent] =
      store.addSubstrateComponent(SubstrateComponent(SubstrateComponentId(java.util.UUID.fromString(idGen.nextId())), data))

    override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
      store.editSubstrateComponent(id, data)

    override def getPesticides: CatalogReadResult[Pesticide] = store.getPesticides

    override def addPesticide(data: PesticideData): CatalogAddResult[Pesticide] =
      store.addPesticide(Pesticide(PesticideId(java.util.UUID.fromString(idGen.nextId())), data))

    override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
      store.editPesticide(id, data)

    override def logOperation(plantId: PlantId, op: OperationDetails): LogOperationResult = operationMutex.exclusively:
      val operation = Operation(OperationId(idGen.nextId()), plantId, clock.now(), op)
      validateReferences(op) match
        case Left(reason) => LogOperationResult.LoggingFailed(reason)
        case Right(_)     =>
          store.addOperation(operation) match
            case res: LogOperationResult.Logged =>
              updatePlantAfterLogging(operation)
                .compensateWith(store.removeOperation(operation.id))
                .leftMap(LogOperationResult.LoggingFailed.apply)
                .fold(identity, _ => res)
            case failure => failure

    override def editOperation(id: OperationId, details: OperationDetails): EditOperationResult = operationMutex.exclusively:
      store.getOperation(id) match
        case GetOperationResult.Corrupted(corruptions)                                   => EditOperationResult.Corrupted(corruptions)
        case GetOperationResult.ReadFailed(reason)                                       => EditOperationResult.EditFailed(reason)
        case GetOperationResult.RecordMissing                                            => EditOperationResult.OperationMissing
        case GetOperationResult.Read(operation) if !sameType(operation.details, details) =>
          EditOperationResult.OperationTypeMismatch
        case GetOperationResult.Read(operation) =>
          validateReferences(details) match
            case Left(reason) => EditOperationResult.EditFailed(reason)
            case Right(_)     =>
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
            updated <- updatePlant(plant.focus(_.details.substrate).replace(repot.substrate))
          yield updated

    private def validateReferences(details: OperationDetails): Either[Throwable, Unit] =
      details match
        case care: OperationDetails.Care if care.pesticides.nonEmpty =>
          store.getPesticides match
            case CatalogReadResult.Read(pesticides) =>
              val known   = pesticides.map(_.id.value).toSet
              val missing = care.pesticides.map(_.value).diff(known)
              Either.cond(missing.isEmpty, (), RuntimeException(s"unknown pesticide ids: ${missing.toVector.sortBy(_.toString).mkString(", ")}"))
            case CatalogReadResult.ReadFailed(reason) => reason.asLeft
        case _: OperationDetails.Care      => ().asRight
        case repot: OperationDetails.Repot =>
          store.getSubstrateComponents match
            case CatalogReadResult.Read(components) =>
              val known   = components.map(_.id.value).toSet
              val missing = repot.substrate.parts.map(_.componentId.value).toSet.diff(known)
              Either.cond(
                missing.isEmpty,
                (),
                RuntimeException(s"unknown substrate component ids: ${missing.toVector.sortBy(_.toString).mkString(", ")}")
              )
            case CatalogReadResult.ReadFailed(reason) => reason.asLeft

    private def updatePlantIfOperationIsLatestRepot(operation: Operation) =
      operation.details match
        case _: OperationDetails.Care      => ().asRight
        case repot: OperationDetails.Repot =>
          for
            operations <- readOperations(operation.plantId)
            _          <- isLatestRepot(operation, operations.filterNot(_.id.value.equals(operation.id.value))).orSkip
            plant      <- readPlant(operation.plantId)
            updated    <- updatePlant(plant.focus(_.details.substrate).replace(repot.substrate))
          yield updated

    private def readOperations(plantId: PlantId) =
      store.getOperations(plantId) match
        case GetOperationsResult.Read(operations)   => operations.asRight
        case GetOperationsResult.ReadFailed(reason) => PlantUpdateInterruption.Failed(reason).asLeft
        case GetOperationsResult.Corrupted(details) =>
          PlantUpdateInterruption.Failed(readFailure("cannot read operations after editing repot", details)).asLeft

    private def readPlant(plantId: PlantId) =
      store.getPlant(plantId) match
        case GetPlantResult.Read(plant)        => plant.asRight
        case GetPlantResult.ReadFailed(reason) => PlantUpdateInterruption.Failed(reason).asLeft
        case GetPlantResult.RecordMissing      => PlantUpdateInterruption.Failed(RuntimeException("cannot read plant after repot")).asLeft
        case GetPlantResult.Corrupted(details) =>
          PlantUpdateInterruption.Failed(readFailure("cannot read plant after repot", details)).asLeft

    private def updatePlant(plant: Plant) =
      store.updatePlant(plant) match
        case UpdatePlantResult.Updated              => ().asRight
        case UpdatePlantResult.UpdateFailed(reason) => PlantUpdateInterruption.Failed(reason).asLeft

    private def isLatestRepot(operation: Operation, others: Vector[Operation]) =
      others.forall: other =>
        other.details match
          case _: OperationDetails.Repot => operation.date.isAfter(other.date)
          case _                         => true

    private def readFailure(context: String, details: NonEmptyList[JournalCorruption]) =
      val reasons = details.map(_.reason)
      RuntimeException(context, reasons.head).tap(error => reasons.tail.foreach(error.addSuppressed))

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
