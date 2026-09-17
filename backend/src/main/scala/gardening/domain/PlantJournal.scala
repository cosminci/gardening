package gardening.domain

import language.experimental.captureChecking

import scala.util.chaining.scalaUtilChainingOps

trait PlantJournal:
  def getPlants: JournalReadResult[Vector[Plant]]
  def getOperations(plantId: PlantId): JournalReadResult[Vector[Operation]]
  def logOperation(plantId: PlantId, op: OperationDetails): LogOperationResult
  def editOperation(id: OperationId, details: OperationDetails): EditOperationResult

object PlantJournal:

  def make(using store: PlantJournalStore^, idGen: IdGenerator^): PlantJournal^{store, idGen} =
    new LivePlantJournal

  private class LivePlantJournal(using store: PlantJournalStore^, idGen: IdGenerator^) extends PlantJournal:

    override def getPlants: JournalReadResult[Vector[Plant]] =
      store.getPlants

    override def getOperations(plantId: PlantId): JournalReadResult[Vector[Operation]] =
      store.getOperations(plantId)

    override def logOperation(plantId: PlantId, op: OperationDetails): LogOperationResult =
      val operation = Operation(OperationId(idGen.nextId()), plantId, op)
      val result    = store.addOperation(operation)
      result match
        case LogOperationResult.Logged(_) =>
          op match
            case _: OperationDetails.Care   => ()
            case op: OperationDetails.Repot => updatePlantAfterRepot(plantId, op.substrate)
        case _ => ()
      result

    override def editOperation(id: OperationId, details: OperationDetails): EditOperationResult =
      store.getOperation(id) match
        case JournalReadResult.Corrupted(corruptions)                                   => EditOperationResult.Corrupted(corruptions)
        case JournalReadResult.ReadFailed(reason)                                       => EditOperationResult.EditFailed(reason)
        case JournalReadResult.RecordMissing                                            => EditOperationResult.OperationMissing
        case JournalReadResult.Read(operation) if !sameType(operation.details, details) =>
          EditOperationResult.OperationTypeMismatch
        case JournalReadResult.Read(operation) =>
          store.updateOperation(id, details).tap:
            case EditOperationResult.Edited(_) =>
              details match
                case _: OperationDetails.Care   => ()
                case op: OperationDetails.Repot => updatePlantAfterRepot(operation.plantId, op.substrate)
            case _ => ()

    private def updatePlantAfterRepot(plantId: PlantId, substrate: Substrate): Unit =
      store.getPlant(plantId) match
        case JournalReadResult.Read(plant)                                    => store.updatePlant(plant.copy(substrate = substrate))
        case JournalReadResult.Corrupted(_) | JournalReadResult.ReadFailed(_) => ()
        // Operations cannot outlive plants because their foreign key is enforced and plants are never deleted.
        // $COVERAGE-OFF$
        case JournalReadResult.RecordMissing => ()
        // $COVERAGE-ON$

    private def sameType(first: OperationDetails, second: OperationDetails): Boolean =
      (first, second) match
        case (_: OperationDetails.Care, _: OperationDetails.Care)   => true
        case (_: OperationDetails.Repot, _: OperationDetails.Repot) => true
        case _                                                      => false
