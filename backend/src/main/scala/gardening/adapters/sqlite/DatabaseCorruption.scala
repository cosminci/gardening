package gardening.adapters.sqlite

final case class DatabaseCorruption(err: Throwable) extends RuntimeException(err)

// Writes are validated before persistence, so a decode failure here is an invariant violation.
@SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
private[sqlite] def trust[A](decoded: Either[Throwable, A]): A =
  decoded.left.map(DatabaseCorruption.apply).toTry.get
