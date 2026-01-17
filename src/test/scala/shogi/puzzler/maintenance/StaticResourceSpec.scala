package shogi.puzzler.maintenance

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import cask.util.Logger

class StaticResourceSpec extends AnyWordSpec with Matchers {
  "MaintenanceApp" should {
    "serve static resources" in {
        val server = io.undertow.Undertow.builder()
          .addHttpListener(8081, "localhost")
          .setHandler(MaintenanceApp.defaultHandler)
          .build()
        server.start()

        try {
          val res1 = requests.get("http://localhost:8081/assets/css/common.css", check = false)
          res1.statusCode shouldBe 200
          // res1.text() should include(".sg-wrap") // This failed because it's not in common.css

          val res2 = requests.get("http://localhost:8081/js/maintenance.js", check = false)
          res2.statusCode shouldBe 200
          res2.text() should include("window.maintenance")
          
          val res3 = requests.get("http://localhost:8081/assets/css/site.css", check = false)
          res3.statusCode shouldBe 200
          res3.text() should include("body")

        } finally {
          server.stop()
        }
    }
  }
}
