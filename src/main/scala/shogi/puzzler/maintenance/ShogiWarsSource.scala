package shogi.puzzler.maintenance

import com.microsoft.playwright._
import shogi.puzzler.domain.SearchGame
import scala.jdk.CollectionConverters._
import org.slf4j.LoggerFactory

object ShogiWarsSource extends GameSource {
  private val logger = LoggerFactory.getLogger(getClass)

  override def name: String = "ShogiWars (via Shogi-Extend)"

  private def cleanPlayerName(name: String): String = {
    // ShogiWars names often look like "Tonyko 3級" or "Tonyko 2段"
    // Remove the rank part: a space followed by digits and rank characters (級, 段, etc.)
    name.replaceAll("\\s+\\d+[級段].*$", "").trim
  }

  override def fetchGames(playerName: String, limit: Int = 10, userEmail: Option[String] = None, onProgress: String => Unit = _ => ()): Seq[SearchGame] = {
    onProgress(s"Initializing ShogiWars fetch for $playerName...")
    val playwright = Playwright.create()
    val browser = playwright.chromium().launch(
      new BrowserType.LaunchOptions()
        .setHeadless(true)
        .setArgs(java.util.List.of("--no-sandbox", "--disable-setuid-sandbox"))
    )
    onProgress("Browser launched. Connecting to Shogi-Extend API...")
    val context = browser.newContext(new Browser.NewContextOptions()
      .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
      .setExtraHTTPHeaders(Map(
        "Accept-Language" -> "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" -> "https://www.shogi-extend.com/swars"
      ).asJava)
    )
    val page = context.newPage()

    try {
      // Use the JSON API instead of scraping
      val apiUrl = s"https://www.shogi-extend.com/w.json?query=$playerName"
      logger.info(s"[SHOGIWARS] Calling API: $apiUrl")
      
      val response = page.navigate(apiUrl, new Page.NavigateOptions().setTimeout(60000))
      if (response == null || !response.ok()) {
        logger.error(s"[SHOGIWARS] API call failed: ${if (response != null) response.status() else "null"}")
        onProgress("API call failed.")
        return Seq.empty
      }

      val jsonText = page.evaluate("document.body.innerText").asInstanceOf[String]
      logger.info(s"[SHOGIWARS] Received JSON length: ${jsonText.length}")
      val data = ujson.read(jsonText)
      val records = data("records").arr
      
      logger.info(s"[SHOGIWARS] API returned ${records.length} records")
      val count = Math.min(records.length, limit)
      onProgress(s"Found ${records.length} records. Fetching KIFs for $count games...")

      records.take(limit).zipWithIndex.map { case (r, idx) =>
        val key = r("key").str
        val sente = cleanPlayerName(r("player_info")("black")("name").str)
        val gote = cleanPlayerName(r("player_info")("white")("name").str)
        val date = r("battled_at").str.split("T").head // Simple date extraction
        
        onProgress(s"[$idx/${count}] Fetching KIF for $sente vs $gote ($date)...")
        logger.info(s"[SHOGIWARS] Fetching KIF for $key")
        val kifUrl = s"https://www.shogi-extend.com/w/$key.kif"
        val kifResp = context.request().get(kifUrl)
        
        val kif = if (kifResp.ok()) {
          Some(kifResp.text())
        } else {
          logger.error(s"[SHOGIWARS] Failed to fetch KIF for $key: ${kifResp.status()}")
          None
        }

        SearchGame(sente, gote, date, kif, site = Some("shogiwars"))
      }.toSeq

    } catch {
      case e: Exception =>
        logger.error(s"[SHOGIWARS ERROR] ${e.getMessage}", e)
        onProgress(s"Error: ${e.getMessage}")
        Seq.empty
    } finally {
      browser.close()
      playwright.close()
    }
  }
}
