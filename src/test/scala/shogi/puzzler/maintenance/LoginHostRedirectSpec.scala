package shogi.puzzler.maintenance

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class LoginHostRedirectSpec extends AnyWordSpec with Matchers {
  "MaintenanceApp" should {
    "redirect /login to configured host when accessed via mismatched host" in {
      val server = io.undertow.Undertow.builder()
        .addHttpListener(8084, "127.0.0.1")
        .setHandler(MaintenanceApp.defaultHandler)
        .build()
      server.start()

      try {
        val res = requests.get("http://127.0.0.1:8084/login", check = false, maxRedirects = 0)
        res.statusCode should (be (301) or be (302))

        val location = res.headers.collectFirst {
          case (key, values) if key.equalsIgnoreCase("Location") => values.headOption.getOrElse("")
        }.getOrElse("")

        location should startWith ("http://localhost:8080/login")
      } finally {
        server.stop()
      }
    }
  }
}
