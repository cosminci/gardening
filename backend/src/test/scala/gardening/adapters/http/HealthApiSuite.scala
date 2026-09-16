package gardening.adapters.http

import sttp.client3.testing.SttpBackendStub
import sttp.client3.{UriContext, basicRequest}
import sttp.model.StatusCode
import sttp.tapir.server.stub.TapirStubInterpreter

class HealthApiSuite extends munit.FunSuite:
  test("should report ok and the running version"):
    val backend =
      TapirStubInterpreter(SttpBackendStub.synchronous)
        .whenServerEndpoint(HealthApi.serverEndpoint("1.2.3"))
        .thenRunLogic()
        .backend()

    val response = basicRequest.get(uri"http://test/health").send(backend)

    assertEquals(response.code, StatusCode.Ok)
    assertEquals(response.body, Right("""{"status":"ok","version":"1.2.3"}"""))
