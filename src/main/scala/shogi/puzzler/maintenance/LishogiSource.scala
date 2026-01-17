package shogi.puzzler.maintenance

import shogi.puzzler.domain.SearchGame
import requests._
import org.slf4j.LoggerFactory

object LishogiSource extends GameSource {
  private val logger = LoggerFactory.getLogger(getClass)

  override def name: String = "Lishogi"

  override def fetchGames(playerName: String, limit: Int = 10, userEmail: Option[String] = None): Seq[SearchGame] = {
    try {
      val url = s"https://lishogi.org/api/games/user/$playerName"
      logger.info(s"[LISHOGI] Calling API: $url")

      val response = requests.get(
        url,
        params = Map("max" -> limit.toString, "clocks" -> "false", "evals" -> "false", "opening" -> "false"),
        headers = Map("Accept" -> "application/x-ndjson")
      )

      if (response.statusCode != 200) {
        logger.error(s"[LISHOGI] API call failed: ${response.statusCode}")
        return Seq.empty
      }

      // Lishogi returns newline-delimited JSON (NDJSON)
      val text = response.text()
      if (text.startsWith("<!DOCTYPE")) {
        logger.error(s"[LISHOGI] Received HTML instead of JSON. First 100 chars: ${text.take(100)}")
        return Seq.empty
      }
      val lines = text.split("\n").filter(_.nonEmpty)
      
      lines.flatMap { line =>
        try {
          val data = ujson.read(line)
          val players = data("players")
          
          def getPlayerName(color: String): String = {
            players.obj.get(color).flatMap { p =>
              p.obj.get("user").flatMap(_.obj.get("name")).map(_.str)
            }.getOrElse("AI")
          }

          val sente = getPlayerName("sente")
          val gote = getPlayerName("gote")
          
          val date = java.time.Instant.ofEpochMilli(data("createdAt").num.toLong)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate.toString
          
          val id = data("id").str
          
          logger.info(s"[LISHOGI] Fetching KIF for $id")
          
          // Fetch KIF
          val kifResponse = requests.get(s"https://lishogi.org/game/export/$id.kif")
          val kif = if (kifResponse.statusCode == 200) Some(kifResponse.text()) else None

          Some(SearchGame(sente, gote, date, kif, site = Some("lishogi")))
        } catch {
          case e: Exception =>
            logger.error(s"[LISHOGI] Error parsing game line: ${e.getMessage}")
            None
        }
      }.toSeq

    } catch {
      case e: Exception =>
        logger.error(s"[LISHOGI ERROR] ${e.getMessage}", e)
        Seq.empty
    }
  }
}
