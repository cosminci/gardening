package gardening.domain.catalog

enum CatalogReadResult[+A]:
  case Read(entries: Vector[A])
  case ReadFailed(reason: Throwable)

enum CatalogAddResult[+A]:
  case Added(entry: A)
  case AddFailed(reason: Throwable)

enum CatalogEditResult[+A]:
  case Edited(entry: A)
  case RecordMissing
  case EditFailed(reason: Throwable)
