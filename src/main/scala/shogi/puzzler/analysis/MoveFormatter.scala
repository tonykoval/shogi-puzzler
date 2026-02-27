package shogi.puzzler.analysis

import shogi.{Color, Pos}
import shogi.format.forsyth.Sfen
import shogi.format.usi.Usi
import shogi.puzzler.domain.{CpScore, Drop, HintDropWrap, HintMoveWrap, HintOrig, MateScore, Move, PovScore, WrapDrop, WrapMove, WrapPiece, WrapScore}
import shogi.variant.Standard

object MoveFormatter {
  /**
   * Get visual arrow color for UI based on move ranking.
   * 1st = primary (green), 2nd = alternative1 (purple), 3rd = alternative2 (cyan)
   */
  def getArrowColor(moveRanking: Option[Int] = None): String = {
    moveRanking match {
      case Some(1) => "primary"
      case Some(2) => "alternative1"
      case Some(3) => "alternative2"
      case _       => "alternative0"
    }
  }

  /**
   * Get text description for move ranking.
   */
  def getArrowDescription(moveRanking: Option[Int] = None): String = {
    moveRanking match {
      case Some(1) => "1st"
      case Some(2) => "2nd"
      case Some(3) => "3rd"
      case _       => "your"
    }
  }

  /**
   * Convert WrapScore to display string.
   */
  def getScore(wrappedScore: WrapScore): Option[String] = {
    wrappedScore.centipawnScore match {
      case Some(cpValue) => Some(cpValue.toString)
      case None => wrappedScore.mateInMoves.map(moves => s"#$moves")
    }
  }

  /**
   * Convert internal position representation to standard notation.
   * Maps rank letters (a-i) to numbers (1-9).
   */
  def getPosition(position: Pos): String = {
    val rankNumber = position.rank.key match {
      case "a" => "1"
      case "b" => "2"
      case "c" => "3"
      case "d" => "4"
      case "e" => "5"
      case "f" => "6"
      case "g" => "7"
      case "h" => "8"
      case "i" => "9"
    }
    position.file.key + rankNumber
  }

  /**
   * Convert USI notation to human-readable move description.
   *
   * Examples:
   * - "drop silver 5e"
   * - "move knight from 7g to 5f"
   * - "move promote rook from 2h to 2a"
   */
  def getMove(usiNotation: String, sfenPosition: String): Option[String] = {
    Usi.apply(usiNotation).flatMap {
      case Usi.Drop(pieceRole, destinationPos) =>
        Some(s"drop ${pieceRole.toString.toLowerCase} ${getPosition(destinationPos)}")

      case Usi.Move(originPos, destinationPos, isPromotion, _) =>
        val movingPiece = Sfen(sfenPosition).toBoard(Standard).get(originPos).get.role.name.toLowerCase
        val promotionText = if (isPromotion) "promote " else ""
        Some(s"move ${promotionText}${movingPiece} from ${getPosition(originPos)} to ${getPosition(destinationPos)}")
    }
  }

  /**
   * Convert POV score to player-perspective wrapped score for JSON.
   */
  def povScoreToWrapScoreForPlayer(povScore: PovScore, playerColor: Color): WrapScore = {
    val scoreFromPlayerPerspective = povScore.forPlayer(playerColor)
    scoreFromPlayerPerspective match {
      case CpScore(cpValue) => WrapScore(centipawnScore = Some(cpValue))
      case MateScore(movesToMate) => WrapScore(mateInMoves = Some(movesToMate))
    }
  }

  /**
   * Convert USI move to detailed piece structure for UI rendering.
   *
   * Handles both drops and regular moves, including:
   * - Visual hints (arrows/highlights)
   * - Move descriptions
   * - Score annotations
   */
  def getPiece(usiMove: String, colorToMove: Color, moveRanking: Option[Int], evaluationScore: Option[WrapScore]): Option[WrapPiece] = {
    Usi.apply(usiMove).flatMap { parsedUsi =>
      // Check if this is a drop or regular move
      if (parsedUsi.positions.size == 1) {
        // This is a drop move
        val dropMove = Usi.Drop(usiMove).get
        Some(
          WrapPiece(
            positionScore = evaluationScore,
            dropPiece = Some(
              WrapDrop(
                dropMove = Drop(
                  pieceRole = dropMove.role.name,
                  destinationSquare = dropMove.pos.key
                ),
                visualHint = HintDropWrap(
                  origin = HintOrig(
                    pieceRole = dropMove.role.name,
                    pieceColor = colorToMove.name
                  ),
                  destination = dropMove.pos.key,
                  visualBrush = getArrowColor(moveRanking),
                  moveDescription = getArrowDescription(moveRanking)
                )
              )
            )
          )
        )
      } else {
        // This is a regular move
        val regularMove = Usi.Move(usiMove).get
        Some(
          WrapPiece(
            positionScore = evaluationScore,
            regularMove = Some(
              WrapMove(
                regularMove = Move(
                  originSquare = regularMove.orig.key,
                  destinationSquare = regularMove.dest.key,
                  isPromotion = regularMove.promotion
                ),
                visualHint = HintMoveWrap(
                  originSquare = regularMove.orig.key,
                  destinationSquare = regularMove.dest.key,
                  visualBrush = getArrowColor(moveRanking),
                  moveDescription = getArrowDescription(moveRanking)
                )
              )
            )
          )
        )
      }
    }
  }
}