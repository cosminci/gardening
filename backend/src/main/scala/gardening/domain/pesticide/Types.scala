package gardening.domain.pesticide

import gardening.domain.*

enum PesticideEditResult:
  case Edited(pesticide: Pesticide)
  case PesticideMissing
  case PesticideArchived
  case EditFailed(reason: Throwable)

enum PesticideArchiveResult:
  case Archived(pesticide: Pesticide)
  case PesticideMissing
  case AlreadyArchived
  case ArchiveFailed(reason: Throwable)
