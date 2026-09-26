package gardening.domain.plants

enum GetPlantResult:
  case Read(plant: Plant)
  case RecordMissing
  case ReadFailed(reason: Throwable)

enum GetPlantsResult:
  case Read(plants: Vector[Plant])
  case ReadFailed(reason: Throwable)

enum CreatePlantResult:
  case Created(plant: Plant)
  case UnknownComponent
  case CatalogReadFailed(reason: Throwable)
  case CreateFailed(reason: Throwable)

enum AddPlantResult:
  case Added
  case AddFailed(reason: Throwable)

sealed trait ArchivedCountResult

object ArchivedCountResult:
  final case class Counted(count: Long) extends ArchivedCountResult:
    require(count >= 0L, "archived plant count must be non-negative")
  final case class ReadFailed(reason: Throwable) extends ArchivedCountResult

enum EditPlantResult:
  case Edited(plant: Plant)
  case PlantMissing
  case PlantArchived
  case UnknownComponent
  case CatalogReadFailed(reason: Throwable)
  case EditFailed(reason: Throwable)

enum UpdatePlantResult:
  case Updated
  case UpdateFailed(reason: Throwable)
