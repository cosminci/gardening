package gardening.adapters.http

import sttp.shared.Identity
import sttp.tapir.emptyInput
import sttp.tapir.files.*
import sttp.tapir.server.ServerEndpoint

object StaticSite:

  def endpoint(directory: String): ServerEndpoint[Any, Identity] =
    staticFilesGetServerEndpoint[Identity](emptyInput)(directory)
