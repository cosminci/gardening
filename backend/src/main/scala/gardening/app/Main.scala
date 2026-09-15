package gardening.app

import gardening.adapters.http.{HealthApi, StaticSite}
import sttp.tapir.server.netty.sync.NettySyncServer

object Main:
  def main(args: Array[String]): Unit =
    val version   = sys.env.getOrElse("GARDENING_APP_VERSION", "0.0.0-dev")
    val staticDir = sys.env.getOrElse("GARDENING_STATIC_DIR", "static")
    val port      = sys.env.get("GARDENING_PORT").flatMap(_.toIntOption).getOrElse(8080)
    val host      = sys.env.getOrElse("GARDENING_HOST", "0.0.0.0")

    val endpoints = List(HealthApi.serverEndpoint(version), StaticSite.endpoint(staticDir))
    val _         = NettySyncServer().host(host).port(port).addEndpoints(endpoints).startAndWait()
