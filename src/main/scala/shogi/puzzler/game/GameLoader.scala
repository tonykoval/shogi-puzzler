package shogi.puzzler.game

import cats.data.Validated.{Invalid, Valid}
import shogi.{Color, Game, Replay}
import shogi.format.Reader.Result.{Complete, Incomplete}
import shogi.format.{ParsedStep, ParsedNotation, Reader, Tag}
import shogi.format.forsyth.Sfen
import shogi.format.kif.KifParser
import shogi.format.usi.Usi
import shogi.puzzler.domain.ParsedGame
import org.slf4j.LoggerFactory

import scala.io.Source

/* ===========================
   GAME LOADING

   Parses game files (KIF format) and extracts:
   - Player information
   - Move sequence
   - Position history
   - Game metadata
   =========================== */

object GameLoader {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Load and parse a KIF game file.
   *
   * Business Logic:
   * - Identifies which color the specified player was playing
   * - Extracts all positions in the game for analysis
   * - Preserves game metadata for puzzle context
   *
   * @param kifFilePath Path to the KIF file
   * @param targetPlayerName Name of the player to analyze
   * @return Parsed game ready for analysis
   */
  def load(kifFilePath: String, targetPlayerName: String): ParsedGame = {
    logger.info(s"[LOADER] Loading game from: $kifFilePath")
    val kifFileContent = Source.fromFile(kifFilePath).mkString
    parseKif(kifFileContent, Some(targetPlayerName), Some(kifFilePath))
  }

  private def cleanPlayerName(name: String): String = {
    // ShogiWars names often look like "Tonyko 3級" or "Tonyko 2段"
    // Remove the rank part: a space followed by digits and rank characters (級, 段, etc.)
    name.replaceAll("\\s+\\d+[級段].*$", "").trim
  }

  def parseKif(kifContent: String, targetPlayerName: Option[String] = None, identifier: Option[String] = None): ParsedGame = {
    logger.info(s"[LOADER] Parsing KIF content. Length: ${kifContent.length}")
    targetPlayerName.foreach(name => logger.info(s"[LOADER] Target player: $name"))

    KifParser.full(kifContent) match {
      case Invalid(parseErrors) =>
        val errorMsg = s"Failed to parse KIF content: $parseErrors"
        logger.error(s"[LOADER ERROR] $errorMsg")
        throw new Exception(errorMsg)

      case Valid(parsedGameNotation) =>
        logger.info("[LOADER] KIF parsing successful")

        val gamePositions = extractPositions(parsedGameNotation)
        logger.info(s"[LOADER] Extracted ${gamePositions.size} positions from game")

        val detectedPlayerColor = targetPlayerName.flatMap(name => detectPlayerColor(parsedGameNotation, name))
          .getOrElse {
            if (targetPlayerName.isDefined) {
              val errorMsg = s"Player '${targetPlayerName.get}' not found in game tags"
              logger.error(s"[LOADER ERROR] $errorMsg")
              throw new Exception(errorMsg)
            } else {
              Color.sente // Default to sente if no target player specified
            }
          }

        logger.info(s"[LOADER] Player color: $detectedPlayerColor")

        // Extract game metadata from tags
        val sentePlayer = parsedGameNotation.tags.value.find(_.name == Tag.Sente).map(t => cleanPlayerName(t.value))
        val gotePlayer = parsedGameNotation.tags.value.find(_.name == Tag.Gote).map(t => cleanPlayerName(t.value))
        val timeControl = parsedGameNotation.tags.value.find(_.name == Tag.TimeControl).map(_.value)
        val site = parsedGameNotation.tags.value.find(_.name == Tag.Site).map(_.value)
        val startDate = parsedGameNotation.tags.value.find(_.name == Tag.Start).map(_.value)

        logger.info(s"[LOADER] Game metadata: Sente=$sentePlayer, Gote=$gotePlayer, Site=$site")

        ParsedGame(
          gameIdentifier = identifier.getOrElse("unknown"),
          playerColorInGame = detectedPlayerColor,
          allPositions = gamePositions,
          sentePlayerName = sentePlayer,
          gotePlayerName = gotePlayer,
          timeControlSetting = timeControl,
          gameSite = site,
          gameDate = startDate
        )
    }
  }

  /**
   * Extract all positions from a parsed game.
   *
   * Business Logic:
   * - Replays the game move by move
   * - Captures SFEN notation at each step
   * - Validates that all moves are legal
   */
  private def extractPositions(parsedGameNotation: ParsedNotation): Seq[(Vector[Usi], Sfen)] = {
    logger.info("[LOADER] Extracting positions from parsed notation")

    Reader.fromParsedNotation(parsedGameNotation, identity) match {
      case Complete(gameReplay) =>
        val positions = Replay
          .gamesWhileValid(gameReplay.state.usis, Some(gameReplay.setup.toSfen), shogi.variant.Standard)
          ._1
          .map(gameState => gameState.usis -> gameState.toSfen)
          .toList

        logger.info(s"[LOADER] Successfully extracted ${positions.size} valid positions")
        positions

      case Incomplete(gameReplay, replayFailures) =>
        logger.warn(s"[LOADER] Incomplete game replay with failures: $replayFailures. Returning valid part of the game.")
        val positions = Replay
          .gamesWhileValid(gameReplay.state.usis, Some(gameReplay.setup.toSfen), shogi.variant.Standard)
          ._1
          .map(gameState => gameState.usis -> gameState.toSfen)
          .toList

        logger.info(s"[LOADER] Successfully extracted ${positions.size} valid positions before failure")
        positions
    }
  }

  /**
   * Detect which color the target player was playing.
   *
   * Business Logic:
   * - Case-insensitive name matching
   * - Checks both sente and gote player tags
   * - Returns None if player not found
   */
  private def detectPlayerColor(parsedGameNotation: ParsedNotation, targetPlayerName: String): Option[Color] = {
    val sentePlayer = parsedGameNotation.tags.value.find(_.name == Tag.Sente).map(_.value)
    val gotePlayer = parsedGameNotation.tags.value.find(_.name == Tag.Gote).map(_.value)
    val normalizedTargetName = targetPlayerName.toLowerCase.trim

    logger.info(s"[LOADER] Matching '$normalizedTargetName' against Sente='$sentePlayer', Gote='$gotePlayer'")

    (sentePlayer, gotePlayer) match {
      case (Some(senteName), _) if senteName.toLowerCase.contains(normalizedTargetName) =>
        logger.info(s"[LOADER] Player matched as Sente")
        Some(Color.sente)
      case (_, Some(goteName)) if goteName.toLowerCase.contains(normalizedTargetName) =>
        logger.info(s"[LOADER] Player matched as Gote")
        Some(Color.gote)
      case _ =>
        logger.info(s"[LOADER] Player not found in either Sente or Gote")
        None
    }
  }

  /**
   * Parse a KIF file with variations into a flat list of repertoire moves.
   *
   * Walks the variation tree produced by KifParser (変化 blocks) and collects
   * each move as (parentSfen, usi, nextSfen, comment).
   *
   * @return (rootSfen, moves) where moves includes all mainline and variation moves
   */
  def parseKifTree(kifContent: String): (String, Seq[(String, String, String, Option[String])]) = {
    logger.info(s"[LOADER] Parsing KIF tree. Content length: ${kifContent.length}")

    KifParser.full(kifContent) match {
      case Invalid(parseErrors) =>
        val errorMsg = s"Failed to parse KIF content: $parseErrors"
        logger.error(s"[LOADER ERROR] $errorMsg")
        throw new Exception(errorMsg)

      case Valid(parsed) =>
        val initialGame = Game(parsed.initialSfen, parsed.variant)
        val rootSfen = initialGame.toSfen.value

        val moves = scala.collection.mutable.Buffer[(String, String, String, Option[String])]()
        walkMoves(initialGame, parsed.parsedSteps.value, moves)

        logger.info(s"[LOADER] Parsed KIF tree: rootSfen=$rootSfen, ${moves.size} total moves")
        (rootSfen, moves.toSeq)
    }
  }

  private def walkMoves(
    game: Game,
    parsedSteps: List[ParsedStep],
    result: scala.collection.mutable.Buffer[(String, String, String, Option[String])]
  ): Unit = {
    parsedSteps match {
      case Nil =>
      case step :: rest =>
        step.toUsi(game.situation) match {
          case Valid(usi) =>
            game(usi) match {
              case Valid(newGame) =>
                val parentSfen = game.toSfen.value
                val nextSfen = newGame.toSfen.value
                val comment = if (step.metas.comments.nonEmpty) Some(step.metas.comments.mkString("\n")) else None
                result += ((parentSfen, usi.usi, nextSfen, comment))

                // Process variations (alternative moves from the same position)
                step.metas.variations.foreach { variation =>
                  walkMoves(game, variation.value, result)
                }

                // Continue mainline
                walkMoves(newGame, rest, result)

              case Invalid(err) =>
                logger.warn(s"[LOADER] Could not apply move: $err")
            }
          case Invalid(err) =>
            logger.warn(s"[LOADER] Could not convert move to USI: $err")
        }
    }
  }
}