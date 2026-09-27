package gardening.domain.substrate

import gardening.domain.*

enum GetSubstrateComponentsResult:
  case Read(entries: Vector[SubstrateComponent])
  case ReadFailed(reason: Throwable)

enum AddSubstrateComponentResult:
  case Added(entry: SubstrateComponent)
  case AddFailed(reason: Throwable)

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
