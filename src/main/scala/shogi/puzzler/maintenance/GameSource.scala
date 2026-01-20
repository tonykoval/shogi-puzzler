package shogi.puzzler.maintenance

import shogi.puzzler.domain.SearchGame

trait GameSource {
  def name: String
  def fetchGames(playerName: String, limit: Int, userEmail: Option[String] = None, onProgress: String => Unit = _ => ()): Seq[SearchGame]
}
