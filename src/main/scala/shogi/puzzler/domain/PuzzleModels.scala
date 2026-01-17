package shogi.puzzler.domain

/* ===========================
   UI/PRESENTATION MODELS

   These models are specifically designed for JSON serialization
   and presentation in the puzzle interface.
   =========================== */

case class Drop(pieceRole: String, destinationSquare: String)

case class HintOrig(pieceRole: String, pieceColor: String)

case class HintDropWrap(
                         origin: HintOrig,
                         destination: String,
                         visualBrush: String = "alternative0",
                         moveDescription: String = ""
                       )

case class WrapDrop(dropMove: Drop, visualHint: HintDropWrap)

case class Move(originSquare: String, destinationSquare: String, isPromotion: Boolean)

case class HintMoveWrap(
                         originSquare: String,
                         destinationSquare: String,
                         visualBrush: String = "alternative0",
                         moveDescription: String = ""
                       )

case class WrapMove(regularMove: Move, visualHint: HintMoveWrap)

case class WrapScore(centipawnScore: Option[Int] = None, mateInMoves: Option[Int] = None)

case class WrapPiece(
                      dropPiece: Option[WrapDrop] = None,
                      regularMove: Option[WrapMove] = None,
                      positionScore: Option[WrapScore] = None
                    )

/**
 * Complete tactical puzzle structure ready for JSON export.
 *
 * Business Logic:
 * - Player made a blunder (your_move_usi)
 * - Best continuation was different (best move)
 * - Puzzle asks player to find the best move in the position BEFORE the blunder
 * - Second and third best moves provide learning opportunities
 */
case class TacticalPuzzle(
                           puzzleId: String,
                           moveNumber: Int,
                           positionSfen: String,
                           playerBlunderMove: String,
                           opponentPreviousMove: String,
                           playerColorName: String,
                           scoreAfterBlunder: WrapScore,
                           scoreBeforeBlunder: WrapScore,
                           bestEngineLine: EngineMove,
                           secondBestLine: Option[EngineMove] = None,
                           thirdBestLine: Option[EngineMove] = None,
                           opponentMoveSquares: Option[Seq[String]] = None,
                           blunderMoveDetails: Option[WrapPiece] = None,
                           bestMoveDetails: Option[WrapPiece] = None,
                           secondMoveDetails: Option[WrapPiece] = None,
                           thirdMoveDetails: Option[WrapPiece] = None,
                           piecesInHand: Option[String] = None,
                           explanationComment: Option[String] = None,
                           sentePlayerName: Option[String] = None,
                           gotePlayerName: Option[String] = None,
                           timeControlSetting: Option[String] = None,
                           gameSite: Option[String] = None,
                           gameDate: Option[String] = None
                         )