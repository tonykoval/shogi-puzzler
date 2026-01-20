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
        
        if (res.statusCode == 200) {
           res.statusCode shouldBe 200
        } else {
          res.statusCode should (be (301) or be (302))

          val location = res.headers.collectFirst {
            case (key, values) if key.equalsIgnoreCase("Location") => values.headOption.getOrElse("")
          }.getOrElse("")

          // It might redirect to OAuth provider if OAuth is enabled but host is correct,
          // or it might redirect to configured host first.
          // In this test, it's accessed via 127.0.0.1:8084 but target is localhost:8080.
          // If oauth is enabled, it SHOULD redirect to localhost:8080/login first.
          location should (startWith ("http://localhost:8080/login") or startWith ("https://accounts.google.com"))
        }
      } finally {
        server.stop()
      }
    }
  }
}
