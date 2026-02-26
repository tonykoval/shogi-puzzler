package shogi.puzzler.maintenance

import org.slf4j.LoggerFactory
import shogi.Color
import shogi.puzzler.analysis.{GameAnalyzer, PuzzleExtractor, SfenUtils}
import shogi.puzzler.db.{AppSettings, GameRepository, PuzzleRepository, SettingsRepository}
import shogi.puzzler.domain.ParsedGame
import shogi.puzzler.game.GameLoader
import shogi.puzzler.maintenance.routes.MaintenanceRoutes.getEngineManager
import shogi.puzzler.domain.TacticalPuzzle

import java.util.concurrent.{LinkedBlockingQueue, Semaphore}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.{Failure, Success, Try}

object AnalysisQueue {
  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val ec: ExecutionContext = ExecutionContext.global

  private case class AnalysisTask(
      taskId: String,
      kif: String,
      player: String,
      source: String,
      userEmail: Option[String],
      shallowLimitOverride: Option[Int] = None,
      deepLimitOverride: Option[Int] = None,
      winChanceDropOverride: Option[Double] = None
  )

  private val queue = new LinkedBlockingQueue[AnalysisTask]()
  private var currentWorkers = 0
  private val workersLock = new Object()

  def enqueue(
      taskId: String,
      kif: String,
      player: String,
      source: String,
      userEmail: Option[String],
      shallowLimitOverride: Option[Int] = None,
      deepLimitOverride: Option[Int] = None,
      winChanceDropOverride: Option[Double] = None
  ): Unit = {
    logger.info(s"[AnalysisQueue] Enqueuing task $taskId for player $player")
    queue.put(AnalysisTask(taskId, kif, player, source, userEmail, shallowLimitOverride, deepLimitOverride, winChanceDropOverride))
    TaskManager.updateProgress(taskId, s"In queue (position: ${queue.size()})")
    ensureWorkers()
  }

  private def ensureWorkers(): Unit = {
    workersLock.synchronized {
      val settings = Await.result(SettingsRepository.getAppSettings(Some("global")), 5.seconds)
      val targetWorkers = settings.analysisWorkers
      
      while (currentWorkers < targetWorkers) {
        startWorker()
        currentWorkers += 1
      }
    }
  }

  private def startWorker(): Unit = {
    Future {
      while (true) {
        val task = queue.take()
        // Update positions of remaining tasks
        updateQueuePositions()
        
        processTask(task)
      }
    }
  }

  private def updateQueuePositions(): Unit = {
    import scala.jdk.CollectionConverters._
    queue.asScala.zipWithIndex.foreach { case (t, index) =>
      TaskManager.updateProgress(t.taskId, s"In queue (position: ${index + 1})")
    }
  }

  private def processTask(task: AnalysisTask): Unit = {
    val taskId = task.taskId
    TaskManager.updateProgress(taskId, "Initializing analysis...")
    val kif = task.kif
    val player = task.player
    val source = task.source
    val userEmail = task.userEmail

    try {
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      val engineManager = getEngineManager(settings.enginePath)

      logger.info(s"[AnalysisQueue] Starting task $taskId for player $player using engine: ${settings.enginePath}")
      val analyzer = new GameAnalyzer(engineManager)
      val extractor = new PuzzleExtractor(analyzer)

      val shallowLimit   = task.shallowLimitOverride.getOrElse(settings.shallowLimit)
      val deepLimit      = task.deepLimitOverride.getOrElse(settings.deepLimit)
      val winChanceDrop  = task.winChanceDropOverride.getOrElse(settings.winChanceDropThreshold)

      TaskManager.updateProgress(taskId, "Parsing KIF...")
      val parsedGame = GameLoader.parseKif(kif, Some(player))

      logger.info(s"[AnalysisQueue] Task $taskId: Starting shallow analysis (shallowLimit=${shallowLimit}s, deepLimit=${deepLimit}s, threshold=$winChanceDrop)...")
      val shallowResults = analyzer.analyzeShallow(parsedGame, shallowLimit, msg => TaskManager.updateProgress(taskId, msg))

      TaskManager.updateProgress(taskId, "Extracting puzzles...")
      logger.info(s"[AnalysisQueue] Task $taskId: Shallow analysis complete. Extracting puzzles...")
      val puzzles = extractor.extract(
        parsedGame,
        shallowResults,
        winChanceDrop,
        deepLimit
      )

      val kifHash = GameRepository.md5Hash(kif)
      val exists = Await.result(GameRepository.exists(kif), 10.seconds)

      if (!exists) {
        val gameDetails = Map(
          "sente" -> parsedGame.sentePlayerName.getOrElse("Unknown"),
          "gote" -> parsedGame.gotePlayerName.getOrElse("Unknown"),
          "date" -> parsedGame.gameDate.getOrElse(""),
          "site" -> parsedGame.gameSite.getOrElse(source)
        )
        try {
          Await.result(GameRepository.saveGame(kif, gameDetails), 10.seconds)
        } catch {
          case e: com.mongodb.MongoWriteException if e.getError.getCategory == com.mongodb.ErrorCategory.DUPLICATE_KEY =>
            Await.result(GameRepository.deleteAnalysisKeepAccepted(kifHash), 10.seconds)
        }
      } else {
        Await.result(GameRepository.deleteAnalysisKeepAccepted(kifHash), 10.seconds)
      }

      Await.result(GameRepository.markAsAnalyzed(kifHash), 10.seconds)
      val scores = shallowResults.map(_.evaluationScore.forPlayer(Color.Sente).toNumeric)
      Await.result(GameRepository.saveScores(kifHash, scores), 10.seconds)

      puzzles.foreach { p =>
        saveAsPuzzle(p, kifHash, userEmail, parsedGame)
      }

      logger.info(s"[AnalysisQueue] Task $taskId complete. ${puzzles.size} puzzles found.")
      val humanMsg  = s"Analysis complete. ${puzzles.size} puzzles found."
      val jsonResult = ujson.write(ujson.Obj("puzzleCount" -> puzzles.size, "kifHash" -> kifHash))
      TaskManager.completeWithJson(taskId, humanMsg, jsonResult)
    } catch {
      case e: Exception =>
        logger.error(s"[AnalysisQueue] Task $taskId failed", e)
        TaskManager.fail(taskId, e.getMessage)
    }
  }

  private def scoreToJson(score: shogi.puzzler.domain.PovScore): ujson.Obj =
    SfenUtils.scoreToUjson(score)

  private def saveAsPuzzle(puzzle: TacticalPuzzle, kifHash: String, userEmail: Option[String], parsedGame: ParsedGame): Unit = {
    val sente = parsedGame.sentePlayerName.getOrElse("Unknown")
    val gote = parsedGame.gotePlayerName.getOrElse("Unknown")
    val name = s"Move ${puzzle.moveNumber}: $sente vs $gote"

    // Build analysis_data in the format puzzle-creator expects:
    // [[{moveNum, usi, score, depth, sfenBefore, pv}], ...]
    val candidates = Seq(
      Some(puzzle.bestEngineLine),
      puzzle.secondBestLine,
      puzzle.thirdBestLine
    ).flatten

    val analysisData = candidates.map { engineMove =>
      val usiMoves = engineMove.usiNotation.split(" ")
      val firstUsi = usiMoves.headOption.getOrElse("")
      val scoreJson = scoreToJson(engineMove.evaluationScore)
      // Each candidate is a sequence of moves; we store the first move with full PV
      ujson.Arr(
        ujson.Obj(
          "moveNum" -> 1,
          "usi" -> firstUsi,
          "score" -> scoreJson,
          "depth" -> 0,
          "sfenBefore" -> puzzle.positionSfen,
          "pv" -> engineMove.usiNotation
        )
      )
    }

    val analysisDataStr = ujson.write(ujson.Arr(analysisData: _*))
    val selectedCandidates = candidates.indices.toSeq
    val selectedSequence = candidates.headOption.map(_.usiNotation.split(" ").toSeq).getOrElse(Seq.empty)

    val blunderMoves = if (puzzle.playerBlunderMove.nonEmpty) Some(Seq(puzzle.playerBlunderMove)) else None

    Await.result(
      PuzzleRepository.savePuzzle(
        name = name,
        sfen = puzzle.positionSfen,
        userEmail = userEmail.getOrElse(""),
        isPublic = false,
        comments = puzzle.explanationComment,
        selectedSequence = Some(selectedSequence),
        moveComments = None,
        analysisData = Some(analysisDataStr),
        selectedCandidates = Some(selectedCandidates),
        gameKifHash = Some(kifHash),
        blunderMoves = blunderMoves,
        status = "review",
        moveNumber = Some(puzzle.moveNumber),
        source = "game"
      ),
      10.seconds
    )
  }
}
