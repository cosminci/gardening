package gardening.domain.journal

import cats.syntax.either.*
import cats.syntax.option.*
import gardening.domain.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import monocle.syntax.all.*

import language.experimental.captureChecking

import java.util.concurrent.locks.ReentrantLock
import scala.annotation.tailrec
import scala.util.chaining.scalaUtilChainingOps

trait PlantJournal:
  def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult
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

    override def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult =
      store.getOperations(plantId, window)

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
      validateOperationDetails(op) match
        case Left(reason) => LogOperationResult.LoggingFailed(reason)
        case Right(_)     =>
          store.addOperation(operation) match
            case res: LogOperationResult.Logged =>
              updatePlantIfOperationIsLatestRepot(operation)
                .compensateWith(store.removeOperation(operation.id))
                .leftMap(LogOperationResult.LoggingFailed.apply)
                .fold(identity, _ => res)
            case failure => failure

    override def editOperation(id: OperationId, details: OperationDetails): EditOperationResult = operationMutex.exclusively:
      store.getOperation(id) match
        case GetOperationResult.ReadFailed(reason)                                       => EditOperationResult.EditFailed(reason)
        case GetOperationResult.RecordMissing                                            => EditOperationResult.OperationMissing
        case GetOperationResult.Read(operation) if !sameType(operation.details, details) =>
          EditOperationResult.OperationTypeMismatch
        case GetOperationResult.Read(operation) =>
          validateOperationDetails(details) match
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

    private def validateOperationDetails(details: OperationDetails) =
      details match
        case op: OperationDetails.Care  => validatePesticides(op.pesticides)
        case op: OperationDetails.Repot => validateSubstrateComponents(op.substrate)

    private def validatePesticides(selected: Set[PesticideId]) =
      if selected.isEmpty then ().asRight
      else
        store.getPesticides match
          case CatalogReadResult.Read(pesticides) =>
            val known   = pesticides.map(_.id).toSet
            val missing = selected.diff(known)
            Either.cond(missing.isEmpty, (), unknownPesticides(missing))
          case CatalogReadResult.ReadFailed(reason) => reason.asLeft

    private def validateSubstrateComponents(substrate: Substrate) =
      store.getSubstrateComponents match
        case CatalogReadResult.Read(components) =>
          val known   = components.map(_.id).toSet
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
            maybeLatestRepot <- readLatestOtherRepot(operation)
            _                <- maybeLatestRepot.forall(other => isNewer(operation, other)).orSkip
            plant            <- readPlant(operation.plantId)
            updated          <- updatePlant(plant.focus(_.details.substrate).replace(repot.substrate))
          yield updated

    private def readLatestOtherRepot(operation: Operation) =
      @tailrec
      def read(window: OperationWindow): Either[PlantUpdateInterruption, Option[Operation]] =
        store.getOperations(operation.plantId, window) match
          case GetOperationsResult.Read(page) =>
            page.operations
              .find(other => !other.id.value.equals(operation.id.value) && isRepot(other)) match
              case found @ Some(_)          => found.asRight
              case None if page.hasNextPage =>
                val nextOffset = (window.offset + window.size).refineUnsafe[GreaterEqual[0]]
                read(OperationWindow(nextOffset, window.size))
              case None => none[Operation].asRight
          case GetOperationsResult.ReadFailed(reason) =>
            PlantUpdateInterruption.Failed(reason).asLeft

      read(OperationWindow(offset = 0, size = 10))

    private def isRepot(operation: Operation) =
      operation.details match
        case _: OperationDetails.Repot => true
        case _: OperationDetails.Care  => false

    private def readPlant(plantId: PlantId) =
      store.getPlant(plantId) match
        case GetPlantResult.Read(plant)        => plant.asRight
        case GetPlantResult.ReadFailed(reason) => PlantUpdateInterruption.Failed(reason).asLeft
        case GetPlantResult.RecordMissing      => PlantUpdateInterruption.Failed(RuntimeException("cannot read plant after repot")).asLeft

    private def updatePlant(plant: Plant) =
      store.updatePlant(plant) match
        case UpdatePlantResult.Updated              => ().asRight
        case UpdatePlantResult.UpdateFailed(reason) => PlantUpdateInterruption.Failed(reason).asLeft

    private def isNewer(operation: Operation, other: Operation) =
      operation.date.isAfter(other.date) ||
        operation.date.equals(other.date) && operation.id.value.compareTo(other.id.value) > 0

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
