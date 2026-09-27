package gardening.capabilities

trait Logger:
  def info(message: String): Unit
  def error(message: String): Unit

  final def error(operation: String, cause: Throwable): Unit =
    error(s"$operation failed: ${cause.getClass.getSimpleName}: ${cause.getMessage}")
