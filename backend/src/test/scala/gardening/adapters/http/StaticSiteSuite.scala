package gardening.adapters.http

import ox.supervised
import sttp.client3.{HttpClientSyncBackend, UriContext, basicRequest}
import sttp.model.StatusCode
import sttp.tapir.server.netty.sync.NettySyncServer

import java.nio.file.Files

class StaticSiteSuite extends munit.FunSuite:
  test("should serve the built single-page app from a directory"):
    val directory = Files.createTempDirectory("gardening-static")
    Files.writeString(directory.resolve("index.html"), "<!doctype html><title>Gardening</title>")

    val response = supervised {
      val binding =
        NettySyncServer().host("127.0.0.1").port(0).addEndpoint(StaticSite.endpoint(directory.toString)).start()
      basicRequest.get(uri"http://127.0.0.1:${binding.port}/index.html").send(HttpClientSyncBackend())
    }

    assertEquals(response.code, StatusCode.Ok)
    assert(response.body.exists(_.contains("Gardening")))
