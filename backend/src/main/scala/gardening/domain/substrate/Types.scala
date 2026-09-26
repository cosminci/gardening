package gardening.domain.substrate

import gardening.domain.*

enum GetSubstrateComponentResult:
  case Read(component: SubstrateComponent)
  case RecordMissing
  case ReadFailed(reason: Throwable)

enum UpdateSubstrateComponentResult:
  case Updated
  case UpdateFailed(reason: Throwable)

enum SubstrateComponentUpdateResult:
  case Updated(component: SubstrateComponent)
  case ComponentMissing
  case ComponentArchived
  case UpdateFailed(reason: Throwable)
