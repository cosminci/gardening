package gardening.domain.substrate

import gardening.domain.SubstrateMix

enum GetSubstrateMixesResult:
  case Read(entries: Vector[SubstrateMix])
  case ReadFailed(reason: Throwable)

enum SaveSubstrateMixResult:
  case Saved(entry: SubstrateMix)
  case SaveFailed(reason: Throwable)

enum DeleteSubstrateMixResult:
  case Deleted
  case DeleteFailed(reason: Throwable)
