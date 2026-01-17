package shogi.puzzler.domain

import shogi.Color
import scala.math.Ordered.orderingToOrdered

/* ===========================
   DOMAIN MODELS

   These classes represent the core business entities:
   - Score types (centipawn and mate scores)
   - Game representation and analysis results
   - Tactical puzzle structure
   =========================== */

/**
 * Base trait for chess engine evaluation scores.
 * Supports both centipawn (cp) evaluations and mate-in-N moves.
 *
 * Business Logic:
 * - Positive scores favor the side to move
 * - Mate scores are prioritized over centipawn scores
 * - Ordering allows comparison between different score types
 */
sealed trait Score extends Ordered[Score] {
  def mateMoves: Option[Int]
  def centipawns: Option[Int]

  def isMate: Boolean = mateMoves.isDefined

  /** Negate the score for opponent's perspective */
  def negate: Score

  def toNumeric: Int = centipawns.getOrElse {
    val m = mateMoves.getOrElse(0)
    if (m > 0) 30000 - m else if (m < 0) -30000 - m else 0
  }

  /**
   * Score comparison logic:
   * 1. Forced mate wins (positive mate moves) are best
   * 2. No-mate positions ranked by centipawns
   * 3. Being mated (negative mate moves) is worst
   */
  override def compare(that: Score): Int =
    scoreTuple.compare(that.scoreTuple)

  private def scoreTuple: (Boolean, Boolean, Int, Option[Int]) =
    (mateMoves.exists(_ > 0), mateMoves.isEmpty, -mateMoves.getOrElse(0), centipawns)
}

object Score {
  /**
   * Parse engine output into a Score from the perspective of a specific color.
   *
   * @param rawEngineOutput Raw score from engine (tuple of type and value)
   * @param colorToMove Color that has the move in this position
   * @return Score from the perspective of colorToMove
   */
  def fromEngine(rawEngineOutput: Option[Any], colorToMove: Color): PovScore =
    rawEngineOutput match {
      case Some(("cp", centipawnValue: Int))   => PovScore(colorToMove, CpScore(centipawnValue))
      case Some(("mate", movesToMate: Int))    => PovScore(colorToMove, MateScore(movesToMate))
      case _                                   => PovScore(colorToMove, MateScore(0)) // Draw or unknown
    }
}

/**
 * Mate score: position can be forced to checkmate in N moves.
 * Positive values indicate we're mating, negative means we're being mated.
 */
case class MateScore(moves: Int) extends Score {
  def mateMoves: Option[Int] = Some(moves)
  def centipawns: Option[Int] = None
  def negate: Score = MateScore(-moves)

  override def toString: String =
    if (moves > 0) s"#+$moves" else s"#-${math.abs(moves)}"
}

/**
 * Centipawn score: evaluation in hundredths of a pawn.
 * Standard chess engine evaluation (e.g., +100 = up one pawn).
 */
case class CpScore(cp: Int) extends Score {
  def mateMoves: Option[Int] = None
  def centipawns: Option[Int] = Some(cp)
  def negate: Score = CpScore(-cp)

  override def toString: String =
    if (cp > 0) s"+$cp" else cp.toString
}

/**
 * Point-of-view score: combines a score with the color that has the move.
 * Allows conversion between "relative to side to move" and "from player's perspective".
 */
case class PovScore(colorToMove: Color, relativeScore: Score) {
  /**
   * Convert score to a specific player's perspective.
   * If the player has the move, score stays the same.
   * If opponent has the move, score is negated.
   */
  def forPlayer(playerColor: Color): Score =
    if (colorToMove == playerColor) relativeScore else relativeScore.negate

  override def toString: String = relativeScore.toString
}