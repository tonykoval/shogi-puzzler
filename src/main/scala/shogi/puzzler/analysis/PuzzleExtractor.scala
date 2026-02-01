package shogi.puzzler.analysis

import shogi.{Color, Pos}
import shogi.format.forsyth.Sfen
import shogi.format.usi.Usi
import shogi.puzzler.domain._
import shogi.variant.Standard
import MoveFormatter._
import org.slf4j.LoggerFactory

/* ===========================
   PUZZLE EXTRACTION

   Core business logic for identifying tactical puzzles from analyzed games.

   Puzzle Detection Algorithm:
   1. Find positions where player had the move
   2. Detect significant evaluation drops (blunders)
   3. Verify that a better move existed
   4. Create puzzle: "Find the better move"
   =========================== */
class PuzzleExtractor(gameAnalyzer: GameAnalyzer) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Extract tactical puzzles from an analyzed game.
   *
   * Business Logic:
   * - Sliding window over consecutive positions
   * - Identifies when player's move caused significant evaluation drop
   * - Verifies puzzle quality with deep analysis
   * - Filters out positions where the blunder was unavoidable
   *
   * @param game The game being analyzed
   * @param shallowAnalysisResults Quick analysis of all positions
   * @param winChanceDropThreshold Minimum win% drop to consider (0.0-1.0)
   * @return Sequence of valid tactical puzzles
   */
  def extract(
               game: ParsedGame,
               shallowAnalysisResults: Seq[AnalysisResult],
               winChanceDropThreshold: Double,
               deepAnalysisSeconds: Int = 10
             ): Seq[TacticalPuzzle] = {

    logger.info(s"[EXTRACTOR] Starting puzzle extraction with threshold: $winChanceDropThreshold")
    logger.info(s"[EXTRACTOR] Analyzing ${shallowAnalysisResults.size} positions")

    val extractedPuzzles = shallowAnalysisResults
      .sliding(2)
      .zipWithIndex
      .flatMap { case (positionPair, pairIndex) =>
        positionPair match {
          case Seq(positionBeforeBlunder, positionAfterBlunder)
            if isPlayerTurn(positionBeforeBlunder, game.playerColorInGame) =>

            val evaluationDrop = calculateWinChanceDelta(
              positionBeforeBlunder,
              positionAfterBlunder,
              game.playerColorInGame
            )

            val isMateInZero = positionAfterBlunder.evaluationScore.relativeScore match {
              case MateScore(0) => true
              case _ => false
            }

            logger.info(s"[EXTRACTOR] Position ${pairIndex + 1}: Win chance drop = ${(evaluationDrop * 100).round}%")

            if (evaluationDrop > winChanceDropThreshold && !isMateInZero) {
              logger.info(s"[EXTRACTOR] Potential blunder detected! Performing deep analysis...")
              buildPuzzle(game, positionBeforeBlunder, positionAfterBlunder, winChanceDropThreshold, deepAnalysisSeconds)
            } else {
              if (isMateInZero) logger.info(s"[EXTRACTOR]   Skipping: Terminal mate position")
              None
            }

          case _ => None
        }
      }
      .toSeq

    logger.info(s"[EXTRACTOR] Extraction complete. Found ${extractedPuzzles.size} puzzles")
    extractedPuzzles
  }

  /**
   * Check if it's the analyzed player's turn to move.
   */
  private def isPlayerTurn(analysisResult: AnalysisResult, playerColor: Color): Boolean = {
    val isPlayersTurn = analysisResult.positionSfen.color.contains(playerColor)
    isPlayersTurn
  }

  /**
   * Calculate the change in winning chances between two positions.
   *
   * Business Logic:
   * - Converts centipawn scores to win probability (0.0 to 1.0)
   * - Handles mate scores as 100% or 0% winning chances
   * - Returns absolute change to catch both positive and negative swings
   *
   * @return Win chance delta from 0.0 (no change) to 1.0 (completely reversed)
   */
  private def calculateWinChanceDelta(
                                       positionBefore: AnalysisResult,
                                       positionAfter: AnalysisResult,
                                       playerColor: Color
                                     ): Double = {
    val winChanceBefore = WinChanceCalculator.calculate(
      positionBefore.evaluationScore.forPlayer(playerColor)
    )
    val winChanceAfter = WinChanceCalculator.calculate(
      positionAfter.evaluationScore.forPlayer(playerColor)
    )

    val delta = winChanceBefore - winChanceAfter
    logger.info(s"[EXTRACTOR]   Win chances: ${(winChanceBefore * 100).round}% → ${(winChanceAfter * 100).round}% (Δ${(delta * 100).round}%)")
    delta
  }

  /**
   * Construct a tactical puzzle from a detected blunder.
   *
   * Business Logic:
   * - Performs deep analysis to find the best move
   * - Verifies the puzzle is interesting (best move significantly better)
   * - Only creates puzzle if improvement threshold is met
   *
   * @return Some(puzzle) if quality threshold met, None otherwise
   */
  private def buildPuzzle(
                           game: ParsedGame,
                           positionBeforeBlunder: AnalysisResult,
                           positionAfterBlunder: AnalysisResult,
                           qualityThreshold: Double,
                           deepAnalysisSeconds: Int
                         ): Option[TacticalPuzzle] = {

    val moveNumber = positionBeforeBlunder.moveNumber
    logger.info(s"[EXTRACTOR] Deep analyzing move $moveNumber to verify puzzle quality")

    // Find what the best move(s) should have been
    val deepAnalysisLines = gameAnalyzer.analyzeDeep(
      positionBeforeBlunder.positionSfen.value,
      positionBeforeBlunder.moveHistoryToPosition,
      moveNumber,
      deepAnalysisSeconds
    )

    val bestMoveResult = deepAnalysisLines.head

    // Calculate how much better the best move was compared to played move
    val improvementFromBestMove = calculateImprovement(
      bestMoveResult,
      positionAfterBlunder,
      game.playerColorInGame
    )

    logger.info(s"[EXTRACTOR]   Best move improvement: ${(improvementFromBestMove * 100).round}%")

    if (improvementFromBestMove > qualityThreshold) {
      logger.info(s"[EXTRACTOR]   ✓ Puzzle quality sufficient, creating puzzle")
      Some(createPuzzle(game, positionBeforeBlunder, positionAfterBlunder, deepAnalysisLines, moveNumber))
    } else {
      logger.info(s"[EXTRACTOR]   ✗ Puzzle quality insufficient (below ${(qualityThreshold * 100).round}% threshold)")
      None
    }
  }

  /**
   * Calculate improvement potential from best move vs played move.
   *
   * This determines if the puzzle is "interesting" - i.e., was there
   * really a much better move available?
   */
  private def calculateImprovement(
                                    bestMoveResult: AnalysisResult,
                                    actualMoveResult: AnalysisResult,
                                    playerColor: Color
                                  ): Double = {
    val bestMoveWinChance = WinChanceCalculator.calculate(
      bestMoveResult.evaluationScore.forPlayer(playerColor)
    )
    val actualMoveWinChance = WinChanceCalculator.calculate(
      actualMoveResult.evaluationScore.forPlayer(playerColor)
    )

    bestMoveWinChance - actualMoveWinChance
  }

  /**
   * Create the final puzzle structure with all metadata.
   *
   * Business Logic:
   * - Packages puzzle position, moves, and explanations
   * - Includes alternative moves for learning
   * - Generates human-readable comments
   * - Preserves game context (players, date, etc.)
   */
  private def createPuzzle(
                            game: ParsedGame,
                            positionBeforeBlunder: AnalysisResult,
                            positionAfterBlunder: AnalysisResult,
                            deepAnalysisLines: Seq[AnalysisResult],
                            moveNumber: Int
                          ): TacticalPuzzle = {

    val sfenComponents = positionBeforeBlunder.positionSfen.value.split(" ")

    // Extract top engine moves
    val bestMove = toEngineMove(deepAnalysisLines.head)
    val secondBestMove = deepAnalysisLines.lift(1).map(toEngineMove)
    val thirdBestMove = deepAnalysisLines.lift(2).map(toEngineMove)

    // Generate explanation comment
    val explanationText = generatePuzzleExplanation(
      game,
      positionBeforeBlunder,
      positionAfterBlunder,
      bestMove,
      secondBestMove,
      thirdBestMove
    )

    logger.info(s"[EXTRACTOR] Created puzzle ID: ${game.gameIdentifier}#${positionBeforeBlunder.positionSfen.boardString.getOrElse("unknown")}")

    val opponentLastMove = positionBeforeBlunder.moveHistoryToPosition.lastOption
    val playerBlunderMove = positionAfterBlunder.moveHistoryToPosition.lastOption

    TacticalPuzzle(
      puzzleId = game.gameIdentifier + "#" + positionBeforeBlunder.positionSfen.boardString.getOrElse(""),
      moveNumber = moveNumber,
      opponentMoveSquares = opponentLastMove.map(_.positions.map(_.key)),
      blunderMoveDetails = playerBlunderMove.flatMap(move =>
        getPiece(
          move.usi,
          Color(sfenComponents(1)(0)).get,
          None,
          Some(povScoreToWrapScoreForPlayer(positionAfterBlunder.evaluationScore, game.playerColorInGame))
        )
      ),
      bestMoveDetails = getPiece(
        bestMove.usiNotation.split(" ").head,
        Color(sfenComponents(1)(0)).get,
        Some(1),
        Some(povScoreToWrapScoreForPlayer(bestMove.evaluationScore, game.playerColorInGame))
      ),
      secondMoveDetails = secondBestMove.flatMap(move =>
        getPiece(
          move.usiNotation.split(" ").head,
          Color(sfenComponents(1)(0)).get,
          Some(2),
          Some(povScoreToWrapScoreForPlayer(move.evaluationScore, game.playerColorInGame))
        )
      ),
      thirdMoveDetails = thirdBestMove.flatMap(move =>
        getPiece(
          move.usiNotation.split(" ").head,
          Color(sfenComponents(1)(0)).get,
          Some(3),
          Some(povScoreToWrapScoreForPlayer(move.evaluationScore, game.playerColorInGame))
        )
      ),
      piecesInHand = Some(sfenComponents(2)),
      explanationComment = Some(explanationText),
      sentePlayerName = game.sentePlayerName,
      gotePlayerName = game.gotePlayerName,
      timeControlSetting = game.timeControlSetting,
      gameSite = game.gameSite,
      gameDate = game.gameDate,
      positionSfen = positionBeforeBlunder.positionSfen.value,
      playerBlunderMove = playerBlunderMove.map(_.usi).getOrElse(""),
      opponentPreviousMove = opponentLastMove.map(_.usi).getOrElse(""),
      playerColorName = game.playerColorInGame.name,
      scoreAfterBlunder = povScoreToWrapScoreForPlayer(positionAfterBlunder.evaluationScore, game.playerColorInGame),
      scoreBeforeBlunder = povScoreToWrapScoreForPlayer(positionBeforeBlunder.evaluationScore, game.playerColorInGame),
      bestEngineLine = toEngineMove(deepAnalysisLines.head),
      secondBestLine = deepAnalysisLines.lift(1).map(toEngineMove),
      thirdBestLine = deepAnalysisLines.lift(2).map(toEngineMove)
    )
  }

  /**
   * Generate human-readable explanation of the puzzle.
   *
   * Includes:
   * - What move was played (the blunder)
   * - What the best move was
   * - Score changes for each option
   */
  private def generatePuzzleExplanation(
                                         game: ParsedGame,
                                         positionBefore: AnalysisResult,
                                         positionAfter: AnalysisResult,
                                         bestMove: EngineMove,
                                         secondBest: Option[EngineMove],
                                         thirdBest: Option[EngineMove]
                                       ): String = {
    val blunderMoveUsi = positionAfter.moveHistoryToPosition.lastOption.map(_.usi).getOrElse("")
    val blunderMoveDesc = getMove(blunderMoveUsi, positionBefore.positionSfen.value).getOrElse("unknown")
    val bestMoveDesc = getMove(bestMove.usiNotation.split(" ").head, positionBefore.positionSfen.value).getOrElse("unknown")

    val blunderLine = s"Blunder [$blunderMoveDesc, score: (${positionBefore.evaluationScore.forPlayer(game.playerColorInGame)} → ${positionAfter.evaluationScore.forPlayer(game.playerColorInGame)})]"
    val bestLine = s"Best [$bestMoveDesc, score: (${positionBefore.evaluationScore.forPlayer(game.playerColorInGame)} → ${bestMove.evaluationScore.forPlayer(game.playerColorInGame)})]"

    val secondLine = secondBest.map { move =>
      val moveDesc = getMove(move.usiNotation.split(" ").head, positionBefore.positionSfen.value).getOrElse("unknown")
      s"Second [$moveDesc, score: (${positionBefore.evaluationScore.forPlayer(game.playerColorInGame)} → ${move.evaluationScore.forPlayer(game.playerColorInGame)})]"
    }.getOrElse("")

    val thirdLine = thirdBest.map { move =>
      val moveDesc = getMove(move.usiNotation.split(" ").head, positionBefore.positionSfen.value).getOrElse("unknown")
      s"Third [$moveDesc, score: (${positionBefore.evaluationScore.forPlayer(game.playerColorInGame)} → ${move.evaluationScore.forPlayer(game.playerColorInGame)})]"
    }.getOrElse("")

    Seq(blunderLine, bestLine, secondLine, thirdLine)
      .filter(_.nonEmpty)
      .mkString("\n")
  }

  /** Convert AnalysisResult to simplified EngineMove */
  private def toEngineMove(analysisResult: AnalysisResult): EngineMove =
    EngineMove(
      usiNotation = analysisResult.principalVariation.getOrElse(""),
      evaluationScore = analysisResult.evaluationScore
    )
}