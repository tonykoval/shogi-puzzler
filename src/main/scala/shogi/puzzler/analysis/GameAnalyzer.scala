package shogi.puzzler.analysis

import shogi.Color
import shogi.format.forsyth.Sfen
import shogi.format.usi.Usi
import shogi.puzzler.domain.{AnalysisResult, ParsedGame, Score}
import shogi.puzzler.engine.{EngineManager, Limit}
import org.slf4j.LoggerFactory

/* ===========================
   GAME ANALYSIS

   Coordinates engine analysis of game positions.
   Provides both shallow (quick) and deep (thorough) analysis modes.
   =========================== */

/**
 * Analyzes game positions using a chess engine.
 *
 * Business Logic:
 * - Shallow analysis: Quick evaluation of each position (for blunder detection)
 * - Deep analysis: Thorough multi-PV search (for finding best alternatives)
 */
class GameAnalyzer(engineManager: EngineManager) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Perform quick analysis of all positions in a game.
   *
   * Used for: Initial pass to identify blunders
   *
   * @param game The parsed game to analyze
   * @param secondsPerMove Time limit for each position
   * @return Analysis result for each position
   */
  def analyzeShallow(game: ParsedGame, secondsPerMove: Int = 1, onProgress: String => Unit = _ => ()): Seq[AnalysisResult] = {
    logger.info(s"[ANALYZER] Starting shallow analysis of ${game.allPositions.size} positions")
    logger.info(s"[ANALYZER] Time per move: ${secondsPerMove}s")

    val allResults = game.allPositions.zipWithIndex.map { case ((moveHistory, positionSfen), positionIndex) =>
      onProgress(s"Analyzing move ${positionIndex + 1}/${game.allPositions.size}...")
      val rawEngineResults = engineManager.analyze(
        positionSfen.value,
        Limit(time = Some(secondsPerMove))
      )

      if (rawEngineResults.isEmpty) {
        logger.warn(s"[ANALYZER] No engine results for position ${positionIndex + 1}")
      }

      val parsedResults = rawEngineResults.map(rawData =>
        parseEngineResult(rawData, positionSfen, moveHistory, positionIndex + 1)
      )

      if (parsedResults.nonEmpty) {
        val displayScore = parsedResults.last.evaluationScore.forPlayer(Color.Sente)
        logger.info(s"[ANALYZER] Analyzing position ${positionIndex + 1}/${game.allPositions.size} " +
          s"Score: $displayScore")
      }

      // Return the last (and only) result since multiPV=1 in shallow analysis
      parsedResults.lastOption.getOrElse {
         // Fallback if engine fails to return anything
         parseEngineResult(Map.empty, positionSfen, moveHistory, positionIndex + 1)
      }
    }

    logger.info(s"[ANALYZER] Shallow analysis complete. Processed ${allResults.size} positions.")
    allResults
  }

  /**
   * Perform thorough analysis of a single position.
   *
   * Used for: Finding best moves and alternatives after detecting a blunder
   *
   * @param sfenPosition Position to analyze
   * @param moveHistory Moves leading to this position
   * @param analysisSeconds Time to spend on analysis
   * @return Top 3 moves with evaluations
   */
  def analyzeDeep(sfenPosition: String, moveHistory: Vector[Usi], moveNumber: Int, analysisSeconds: Int = 10): Seq[AnalysisResult] = {
    logger.info(s"[ANALYZER] Starting deep analysis (${analysisSeconds}s, MultiPV=3)")

    val rawEngineResults = engineManager.analyze(
      sfenPosition,
      Limit(time = Some(analysisSeconds)),
      multiPvCount = 3
    )

    val parsedResults = rawEngineResults.map(rawData =>
      parseEngineResult(rawData, Sfen(sfenPosition), moveHistory, moveNumber)
    )

    logger.info(s"[ANALYZER] Deep analysis found ${parsedResults.size} lines")
    parsedResults.foreach { result =>
      logger.info(s"[ANALYZER]   Line: ${result.principalVariation.getOrElse("N/A")} - Score: ${result.evaluationScore}")
    }

    parsedResults
  }

  /**
   * Convert raw engine output to structured AnalysisResult.
   *
   * Business Logic:
   * - Parses score (cp or mate)
   * - Extracts principal variation
   * - Associates with position context
   */
  private def parseEngineResult(
                                 rawEngineData: Map[String, Any],
                                 positionSfen: Sfen,
                                 moveHistory: Vector[Usi],
                                 moveNumber: Int
                               ): AnalysisResult = {
    val parsedScore = Score.fromEngine(rawEngineData.get("score"), positionSfen.color.get)

    AnalysisResult(
      positionSfen = positionSfen,
      moveHistoryToPosition = moveHistory,
      evaluationScore = parsedScore,
      moveNumber = moveNumber,
      nodesPerSecond = rawEngineData.get("nps").collect { case nodeCount: Int => nodeCount },
      principalVariation = rawEngineData.get("pv").collect { case pvLines: List[_] => pvLines.head.asInstanceOf[String] }
    )
  }
}