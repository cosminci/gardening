package gardening.adapters.http

import cats.syntax.either.*
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{UriContext, basicRequest}
import sttp.model.StatusCode
import sttp.tapir.server.stub.TapirStubInterpreter

class HealthApiComponentTest extends munit.FunSuite:
  test("should report ok"):
    val backend =
      TapirStubInterpreter(SttpBackendStub.synchronous)
        .whenServerEndpoint(HealthApi.serverEndpoint)
        .thenRunLogic()
        .backend()

    val response = basicRequest.get(uri"http://test/health").send(backend)

    assertEquals(response.code, StatusCode.Ok)
    assertEquals(response.body, """{"status":"ok"}""".asRight)
