package gardening.domain.journal

import cats.syntax.either.*
import cats.syntax.eq.*
import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.PesticideStore
import gardening.domain.substrate.SubstrateComponentStore
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import monocle.syntax.all.*

import language.experimental.captureChecking

import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import scala.annotation.tailrec
import scala.util.chaining.scalaUtilChainingOps

trait PlantJournal:
  def createPlant(species: Species, maybeNickname: Option[Nickname], location: Location, substrate: Substrate): CreatePlantResult
  def getPlants(status: PlantStatus): GetPlantsResult
  def getArchivedCount: ArchivedCountResult
  def editPlant(id: PlantId, revise: PlantDetails => PlantDetails): EditPlantResult
  def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult
  def getOperationDateRange(plantId: PlantId): GetOperationDateRangeResult
  def logOperation(plantId: PlantId, date: Instant, op: OperationDetails): LogOperationResult
  def editOperation(id: OperationId, details: OperationDetails): EditOperationResult

object PlantJournal:

  def make(using
      store: PlantJournalStore^,
      substrateStore: SubstrateComponentStore^,
      pesticideStore: PesticideStore^,
      idGen: IdGenerator^
  ): PlantJournal^{store, substrateStore, pesticideStore, idGen} =
    new LivePlantJournal

  private class LivePlantJournal(using
      store: PlantJournalStore^,
      substrateStore: SubstrateComponentStore^,
      pesticideStore: PesticideStore^,
      idGen: IdGenerator^
  ) extends PlantJournal:
    private val operationMutex = ReentrantLock()

    override def createPlant(species: Species, maybeNickname: Option[Nickname], location: Location, substrate: Substrate): CreatePlantResult =
      substrateStore.getSubstrateComponents match
        case CatalogReadResult.ReadFailed(reason) => CreatePlantResult.CatalogReadFailed(reason)
        case CatalogReadResult.Read(components)   =>
          val known = components.map(_.id).toSet
          if !substrate.parts.forall(part => known.contains(part.componentId)) then CreatePlantResult.UnknownComponent
          else
            val plant = Plant(PlantId(idGen.nextId()), PlantDetails(species, maybeNickname, location, substrate, PlantStatus.Active))
            store.addPlant(plant) match
              case AddPlantResult.Added             => CreatePlantResult.Created(plant)
              case AddPlantResult.AddFailed(reason) => CreatePlantResult.CreateFailed(reason)

    override def getPlants(status: PlantStatus): GetPlantsResult = store.getPlants(status)

    override def getArchivedCount: ArchivedCountResult = store.getArchivedCount

    override def editPlant(id: PlantId, revise: PlantDetails => PlantDetails): EditPlantResult = operationMutex.exclusively:
      val outcome =
        for
          edited <- readAndRevise(id, revise)
          _      <- rejectUnknownSubstrate(edited.details.substrate)
          saved  <- persistEdit(edited)
        yield saved
      outcome.fold(identity, EditPlantResult.Edited.apply)

    private def readAndRevise(id: PlantId, revise: PlantDetails => PlantDetails): Either[EditPlantResult, Plant] =
      store.getPlant(id) match
        case GetPlantResult.RecordMissing                                                => EditPlantResult.PlantMissing.asLeft
        case GetPlantResult.ReadFailed(reason)                                           => EditPlantResult.EditFailed(reason).asLeft
        case GetPlantResult.Read(plant) if plant.details.status === PlantStatus.Archived => EditPlantResult.PlantArchived.asLeft
        case GetPlantResult.Read(plant)                                                  => plant.focus(_.details).modify(revise).asRight

    private def rejectUnknownSubstrate(substrate: Substrate): Either[EditPlantResult, Unit] =
      substrateStore.getSubstrateComponents match
        case CatalogReadResult.ReadFailed(reason) => EditPlantResult.CatalogReadFailed(reason).asLeft
        case CatalogReadResult.Read(components)   =>
          val known = components.map(_.id).toSet
          Either.cond(substrate.parts.forall(part => known.contains(part.componentId)), (), EditPlantResult.UnknownComponent)

    private def persistEdit(plant: Plant): Either[EditPlantResult, Plant] =
      store.updatePlant(plant) match
        case UpdatePlantResult.Updated              => plant.asRight
        case UpdatePlantResult.UpdateFailed(reason) => EditPlantResult.EditFailed(reason).asLeft

    override def getOperations(plantId: PlantId, window: OperationWindow): GetOperationsResult =
      store.getOperations(plantId, window)

    override def getOperationDateRange(plantId: PlantId): GetOperationDateRangeResult =
      store.getOperationDateRange(plantId)

    override def logOperation(plantId: PlantId, date: Instant, op: OperationDetails): LogOperationResult = operationMutex.exclusively:
      store.getPlant(plantId) match
        case GetPlantResult.RecordMissing                                                => LogOperationResult.PlantMissing
        case GetPlantResult.ReadFailed(reason)                                           => LogOperationResult.LoggingFailed(reason)
        case GetPlantResult.Read(plant) if plant.details.status === PlantStatus.Archived => LogOperationResult.PlantArchived
        case GetPlantResult.Read(_)                                                      =>
          val operation = Operation(OperationId(idGen.nextId()), plantId, date, op)
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
        pesticideStore.getPesticides match
          case CatalogReadResult.Read(pesticides) =>
            val known   = pesticides.map(_.id).toSet
            val missing = selected.diff(known)
            Either.cond(missing.isEmpty, (), unknownPesticides(missing))
          case CatalogReadResult.ReadFailed(reason) => reason.asLeft

    private def validateSubstrateComponents(substrate: Substrate) =
      substrateStore.getSubstrateComponents match
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
