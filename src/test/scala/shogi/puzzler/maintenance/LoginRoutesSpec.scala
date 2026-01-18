package shogi.puzzler.maintenance

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class LoginRoutesSpec extends AnyWordSpec with Matchers {
  "MaintenanceApp" should {
    "serve the login endpoint" in {
      val server = io.undertow.Undertow.builder()
        .addHttpListener(8082, "localhost")
        .setHandler(MaintenanceApp.defaultHandler)
        .build()
      server.start()

      try {
        val res = requests.get("http://localhost:8082/login", check = false, maxRedirects = 0)
        res.statusCode should (be (301) or be (302))

        val location = res.headers.collectFirst {
          case (key, values) if key.equalsIgnoreCase("Location") => values.headOption.getOrElse("")
        }.getOrElse("")
        location should not be empty
        location should (include ("accounts.google.com") or startWith ("http://localhost:8080/login"))
      } finally {
        server.stop()
      }
    }
  }
}
