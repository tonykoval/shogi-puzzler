package shogi.puzzler.maintenance

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class MaintenanceNoCacheRedirectSpec extends AnyWordSpec with Matchers {
  "MaintenanceApp" should {
    "send no-cache headers when redirecting unauthenticated maintenance access" in {
      val server = io.undertow.Undertow.builder()
        .addHttpListener(8080, "localhost")
        .setHandler(MaintenanceApp.defaultHandler)
        .build()
      server.start()

      try {
        val res = requests.get("http://localhost:8080/maintenance", check = false, maxRedirects = 0)
        res.statusCode should (be (301) or be (302))

        val location = res.headers.collectFirst {
          case (key, values) if key.equalsIgnoreCase("Location") => values.headOption.getOrElse("")
        }.getOrElse("")
        location should include ("/login")

        val cacheControl = res.headers.collectFirst {
          case (key, values) if key.equalsIgnoreCase("Cache-Control") => values.headOption.getOrElse("")
        }.getOrElse("")
        cacheControl.toLowerCase should include ("no-store")

        val pragma = res.headers.collectFirst {
          case (key, values) if key.equalsIgnoreCase("Pragma") => values.headOption.getOrElse("")
        }.getOrElse("")
        pragma.toLowerCase should include ("no-cache")
      } finally {
        server.stop()
      }
    }
  }
}
