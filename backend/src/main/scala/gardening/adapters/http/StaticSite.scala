package gardening.adapters.http

import sttp.tapir.emptyInput
import sttp.tapir.files.*
import sttp.tapir.server.ServerEndpoint

/** Serves the built single-page frontend from a directory. */
object StaticSite:
  def endpoints(directory: String): List[ServerEndpoint[Any, Identity]] =
    List(staticFilesGetServerEndpoint[Identity](emptyInput)(directory))
