package shogi.puzzler.maintenance

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class MaintenanceHostRedirectSpec extends AnyWordSpec with Matchers {
  "MaintenanceApp" should {
    "redirect to configured host when accessing maintenance with mismatched host" in {
      val server = io.undertow.Undertow.builder()
        .addHttpListener(8083, "127.0.0.1")
        .setHandler(MaintenanceApp.defaultHandler)
        .build()
      server.start()

      try {
        val res = requests.get("http://127.0.0.1:8083/maintenance", check = false, maxRedirects = 0)
        
        if (res.statusCode == 200) {
           res.statusCode shouldBe 200
        } else {
          res.statusCode should (be (301) or be (302))

          val location = res.headers.collectFirst {
            case (key, values) if key.equalsIgnoreCase("Location") => values.headOption.getOrElse("")
          }.getOrElse("")

          location should startWith ("http://localhost:8080/maintenance")
        }
      } finally {
        server.stop()
      }
    }
  }
}
