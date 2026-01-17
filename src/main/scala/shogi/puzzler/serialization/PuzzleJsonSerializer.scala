package shogi.puzzler.serialization

import shogi.puzzler.domain._

/* ===========================
   JSON SERIALIZATION

   Converts tactical puzzles to JSON format for export.
   =========================== */

object PuzzleJsonSerializer {
  /**
   * Convert a sequence of puzzles to formatted JSON string.
   *
   * @param puzzles Puzzles to serialize
   * @param indent Number of spaces for indentation (0 = compact)
   * @return JSON string representation
   */
  def puzzlesToJsonString(puzzles: Seq[TacticalPuzzle], indent: Int = 0): String = {
    val jsonArray = ujson.Arr(puzzles.map(puzzleToJson): _*)

    if (indent > 0) {
      ujson.write(jsonArray, indent = indent)
    } else {
      ujson.write(jsonArray)
    }
  }

  /**
   * Convert a single puzzle to JSON object.
   */
  def puzzleToJson(puzzle: TacticalPuzzle): ujson.Obj = {
    ujson.Obj(
      "id" -> puzzle.puzzleId,
      "move_number" -> puzzle.moveNumber,
      "sfen" -> puzzle.positionSfen,
      "your_move_usi" -> puzzle.playerBlunderMove,
      "opponent_last_move_usi" -> puzzle.opponentPreviousMove,
      "player" -> puzzle.playerColorName,
      "score" -> scoreToJson(puzzle.scoreAfterBlunder),
      "prev_score" -> scoreToJson(puzzle.scoreBeforeBlunder),
      "best" -> engineMoveToJson(puzzle.bestEngineLine),
      "second" -> puzzle.secondBestLine.map(engineMoveToJson).getOrElse(ujson.Null),
      "third" -> puzzle.thirdBestLine.map(engineMoveToJson).getOrElse(ujson.Null),
      "opponent_last_move_usi_positions" -> puzzle.opponentMoveSquares.map(seq => ujson.Arr(seq.map(ujson.Str): _*)).getOrElse(ujson.Null),
      "your_move" -> puzzle.blunderMoveDetails.map(pieceToJson).getOrElse(ujson.Null),
      "best_move" -> puzzle.bestMoveDetails.map(pieceToJson).getOrElse(ujson.Null),
      "second_move" -> puzzle.secondMoveDetails.map(pieceToJson).getOrElse(ujson.Null),
      "third_move" -> puzzle.thirdMoveDetails.map(pieceToJson).getOrElse(ujson.Null),
      "hands" -> puzzle.piecesInHand.map(ujson.Str).getOrElse(ujson.Null),
      "comment" -> puzzle.explanationComment.map(ujson.Str).getOrElse(ujson.Null),
      "sente" -> puzzle.sentePlayerName.map(ujson.Str).getOrElse(ujson.Null),
      "gote" -> puzzle.gotePlayerName.map(ujson.Str).getOrElse(ujson.Null),
      "timeControl" -> puzzle.timeControlSetting.map(ujson.Str).getOrElse(ujson.Null),
      "site" -> puzzle.gameSite.map(ujson.Str).getOrElse(ujson.Null),
      "date" -> puzzle.gameDate.map(ujson.Str).getOrElse(ujson.Null)
    )
  }

  private def scoreToJson(score: WrapScore): ujson.Obj = {
    ujson.Obj(
      "cp" -> score.centipawnScore.map(v => ujson.Num(v)).getOrElse(ujson.Null),
      "moves" -> score.mateInMoves.map(v => ujson.Num(v)).getOrElse(ujson.Null)
    )
  }

  private def engineMoveToJson(move: EngineMove): ujson.Obj = {
    ujson.Obj(
      "usi" -> move.usiNotation,
      "score" -> povScoreToJson(move.evaluationScore)
    )
  }

  private def povScoreToJson(povScore: PovScore): ujson.Obj = {
    povScore.relativeScore match {
      case CpScore(cp) => ujson.Obj("cp" -> ujson.Num(cp))
      case MateScore(moves) => ujson.Obj("mate" -> ujson.Num(moves))
    }
  }

  private def pieceToJson(piece: WrapPiece): ujson.Obj = {
    ujson.Obj(
      "drop" -> piece.dropPiece.map(dropToJson).getOrElse(ujson.Null),
      "move" -> piece.regularMove.map(moveToJson).getOrElse(ujson.Null),
      "score" -> piece.positionScore.map(scoreToJson).getOrElse(ujson.Null)
    )
  }

  private def dropToJson(drop: WrapDrop): ujson.Obj = {
    ujson.Obj(
      "drop" -> ujson.Obj(
        "role" -> drop.dropMove.pieceRole,
        "pos" -> drop.dropMove.destinationSquare
      ),
      "hint" -> ujson.Obj(
        "orig" -> ujson.Obj(
          "role" -> drop.visualHint.origin.pieceRole,
          "color" -> drop.visualHint.origin.pieceColor
        ),
        "dest" -> drop.visualHint.destination,
        "brush" -> drop.visualHint.visualBrush,
        "description" -> drop.visualHint.moveDescription
      )
    )
  }

  private def moveToJson(move: WrapMove): ujson.Obj = {
    ujson.Obj(
      "move" -> ujson.Obj(
        "orig" -> move.regularMove.originSquare,
        "dest" -> move.regularMove.destinationSquare,
        "promotion" -> ujson.Bool(move.regularMove.isPromotion)
      ),
      "hint" -> ujson.Obj(
        "orig" -> move.visualHint.originSquare,
        "dest" -> move.visualHint.destinationSquare,
        "brush" -> move.visualHint.visualBrush,
        "description" -> move.visualHint.moveDescription
      )
    )
  }
}