package gardening.domain.catalog

enum CatalogReadResult[+A]:
  case Read(entries: Vector[A])
  case ReadFailed(reason: Throwable)

enum CatalogAddResult[+A]:
  case Added(entry: A)
  case AddFailed(reason: Throwable)

enum CatalogDeleteResult:
  case Deleted
  case DeleteFailed(reason: Throwable)
