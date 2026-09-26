package gardening.domain.pesticide

import gardening.domain.*

enum GetPesticideResult:
  case Read(pesticide: Pesticide)
  case RecordMissing
  case ReadFailed(reason: Throwable)

enum UpdatePesticideResult:
  case Updated
  case UpdateFailed(reason: Throwable)

enum PesticideUpdateResult:
  case Updated(pesticide: Pesticide)
  case PesticideMissing
  case PesticideArchived
  case UpdateFailed(reason: Throwable)
