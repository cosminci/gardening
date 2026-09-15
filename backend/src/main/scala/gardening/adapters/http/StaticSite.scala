package gardening.adapters.http

import sttp.tapir.emptyInput
import sttp.tapir.files.*

object StaticSite:
  def endpoint(directory: String) =
    staticFilesGetServerEndpoint[Identity](emptyInput)(directory)
