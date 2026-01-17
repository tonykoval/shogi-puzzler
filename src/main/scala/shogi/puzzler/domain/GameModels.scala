package shogi.puzzler.domain

import shogi.Color
import shogi.format.forsyth.Sfen
import shogi.format.usi.Usi

/**
 * Represents a parsed game with all positions and metadata.
 *
 * @param gameIdentifier Unique identifier for this game
 * @param playerColorInGame Which color the analyzed player was playing
 * @param allPositions Sequence of (move history, position) pairs for the entire game
 * @param sentePlayerName Name of the sente (first) player
 * @param gotePlayerName Name of the gote (second) player
 * @param timeControlSetting Time control used in the game
 * @param gameSite Platform or location where game was played
 * @param gameDate When the game was played
 */
case class ParsedGame(
                       gameIdentifier: String,
                       playerColorInGame: Color,
                       allPositions: Seq[(Vector[Usi], Sfen)],
                       sentePlayerName: Option[String] = None,
                       gotePlayerName: Option[String] = None,
                       timeControlSetting: Option[String] = None,
                       gameSite: Option[String] = None,
                       gameDate: Option[String] = None
                     )

/**
 * Result of analyzing a single position with a chess engine.
 *
 * @param positionSfen SFEN notation of the position
 * @param moveHistoryToPosition All moves leading to this position
 * @param evaluationScore Engine's evaluation of the position
 * @param nodesPerSecond Engine's search speed
 * @param principalVariation Best continuation found by engine
 */
case class AnalysisResult(
                           positionSfen: Sfen,
                           moveHistoryToPosition: Vector[Usi],
                           evaluationScore: PovScore,
                           moveNumber: Int,
                           nodesPerSecond: Option[Int] = None,
                           principalVariation: Option[String] = None
                         )

/**
 * A move recommended by the engine with its evaluation.
 */
case class EngineMove(usiNotation: String, evaluationScore: PovScore)
case class SearchGame(
  sente: String,
  gote: String,
  date: String,
  kif: Option[String] = None,
  existsInDb: Boolean = false,
  isAnalyzed: Boolean = false,
  puzzleCount: Int = 0,
  site: Option[String] = None
)