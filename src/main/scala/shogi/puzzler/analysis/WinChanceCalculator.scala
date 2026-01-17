package shogi.puzzler.analysis

import shogi.puzzler.domain.{CpScore, MateScore, Score}

/* ===========================
   UTILITY FUNCTIONS

   Mathematical helpers and converters.
   =========================== */

object WinChanceCalculator {
  /**
   * Convert a score to estimated winning probability.
   *
   * Business Logic:
   * - Uses logistic function to map centipawns to probability
   * - Mate scores are 100% or 0% depending on direction
   * - Formula: 2/(1 + e^(-0.0007*cp)) - 1
   *   This gives a value from -1.0 (losing) to +1.0 (winning)
   *
   * @param evaluationScore Score to convert
   * @return Win chance from -1.0 to 1.0
   */
  def calculate(evaluationScore: Score): Double = {
    evaluationScore match {
      case MateScore(movesToMate) =>
        if (movesToMate > 0) 1.0 else -1.0
      case CpScore(centipawnValue)  =>
        2.0 / (1.0 + math.exp(-0.0007 * centipawnValue)) - 1.0
    }
  }
}