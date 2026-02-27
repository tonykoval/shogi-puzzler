package shogi.puzzler.analysis

import shogi.Color
import shogi.puzzler.domain.{CpScore, MateScore, PovScore, Score}

object SfenUtils {

  /**
   * Determine which color has the move from SFEN.
   * SFEN format: board turn hands moves
   * turn is 'b' for sente (Black) and 'w' for gote (White)
   */
  def sfenTurnToColor(sfen: String): Color = {
    val parts = sfen.split(" ")
    if (parts.length >= 2) {
      if (parts(1) == "b") Color.Sente else Color.Gote
    } else {
      Color.Sente
    }
  }

  /**
   * Determine if a move number is sente's turn.
   * Accounts for initial position color.
   */
  def isSenteTurn(moveNum: Int, initialSfen: String): Boolean = {
    val initialIsSente = sfenTurnToColor(initialSfen) == Color.Sente
    if (initialIsSente) (moveNum % 2) == 1
    else (moveNum % 2) == 0
  }

  /**
   * Convert a PovScore to a ujson.Obj with kind/value from sente's perspective.
   */
  def scoreToUjson(score: PovScore): ujson.Obj = {
    val senteScore = score.forPlayer(Color.Sente)
    senteScore match {
      case CpScore(cp) => ujson.Obj("kind" -> "cp", "value" -> cp)
      case MateScore(m) => ujson.Obj("kind" -> "mate", "value" -> m)
      case _ => ujson.Obj("kind" -> "cp", "value" -> 0)
    }
  }

  /**
   * Convert a Score to a ujson.Obj with kind/value.
   */
  def scoreToUjsonForPlayer(score: Score): ujson.Obj = {
    score match {
      case CpScore(cp) => ujson.Obj("kind" -> "cp", "value" -> cp)
      case MateScore(m) => ujson.Obj("kind" -> "mate", "value" -> m)
      case _ => ujson.Obj("kind" -> "cp", "value" -> 0)
    }
  }

  /**
   * Extract the first USI move from an engine result map.
   * Tries PV list first, then PV string, then usi/move keys.
   */
  def extractUsiFromResult(result: Map[String, Any]): String = {
    result.get("pv") match {
      case Some(pvList: List[_]) if pvList.nonEmpty => pvList.head.toString
      case Some(pvStr: String) if pvStr.nonEmpty => pvStr.split(" ").head
      case _ => result.get("usi").orElse(result.get("move")).getOrElse("").toString
    }
  }

  /**
   * Extract the PV string from an engine result map.
   */
  def extractPvString(result: Map[String, Any]): String = {
    result.get("pv") match {
      case Some(pvList: List[_]) => pvList.mkString(" ")
      case Some(pvStr: String) => pvStr
      case _ => ""
    }
  }
}
