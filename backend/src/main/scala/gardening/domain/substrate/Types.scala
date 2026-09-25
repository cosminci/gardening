package gardening.domain.substrate

import gardening.domain.*

enum GetSubstrateComponentResult:
  case Read(component: SubstrateComponent)
  case RecordMissing
  case ReadFailed(reason: Throwable)

enum UpdateSubstrateComponentResult:
  case Updated
  case UpdateFailed(reason: Throwable)

enum SubstrateComponentEditResult:
  case Edited(component: SubstrateComponent)
  case ComponentMissing
  case ComponentArchived
  case EditFailed(reason: Throwable)

enum SubstrateComponentArchiveResult:
  case Archived(component: SubstrateComponent)
  case ComponentMissing
  case AlreadyArchived
  case ArchiveFailed(reason: Throwable)
