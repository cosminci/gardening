package gardening.adapters.persistence

final case class DatabaseCorruption(err: Throwable) extends RuntimeException(err)
