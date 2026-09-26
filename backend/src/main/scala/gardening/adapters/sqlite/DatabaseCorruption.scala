package gardening.adapters.sqlite

final case class DatabaseCorruption(err: Throwable) extends RuntimeException(err)
