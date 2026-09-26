package gardening.capabilities

import org.slf4j.LoggerFactory

trait TestImplicits:
  given log: Logger = new Logger:
    private val underlying           = LoggerFactory.getLogger("gardening-test")
    def info(message: String): Unit  = underlying.info(message)
    def error(message: String): Unit = underlying.error(message)
