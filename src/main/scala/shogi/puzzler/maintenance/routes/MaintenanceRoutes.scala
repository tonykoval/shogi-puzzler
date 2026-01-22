package shogi.puzzler.maintenance.routes

import shogi.Color
import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{GameRepository, SettingsRepository, AppSettings}
import shogi.puzzler.ui.Components
import shogi.puzzler.game.{GameLoader, KifAnnotator}
import shogi.puzzler.analysis.{GameAnalyzer, PuzzleExtractor}
import shogi.puzzler.engine.EngineManager
import shogi.puzzler.serialization.PuzzleJsonSerializer
import shogi.puzzler.domain.SearchGame
import shogi.puzzler.maintenance.{ShogiWarsSource, LishogiSource, Dojo81Source, TaskManager}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration._
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

object MaintenanceRoutes extends BaseRoutes {

  private val gamesCache = mutable.Map[String, Seq[SearchGame]]()
  private val engineManagers = mutable.Map[String, EngineManager]()

  private val sources = Map(
    "shogiwars" -> ShogiWarsSource,
    "lishogi" -> LishogiSource,
    "dojo81" -> Dojo81Source
  )

  private def getEngineManager(name: String): EngineManager = {
    logger.info(s"[MAINTENANCE] Requesting engine manager for: $name")
    engineManagers.getOrElseUpdate(name, {
      logger.info(s"[MAINTENANCE] Creating new engine manager for: $name")
      val manager = new EngineManager(Seq(name))
      manager.initialize()
      manager
    })
  }

  @cask.get("/my-games")
  def index(player: Option[String] = None, search_text: Option[String] = None, request: cask.Request): cask.Response[String] = {
    val redirectOpt = if (oauthEnabled) redirectToConfiguredHostIfNeeded(request) else None
    redirectOpt.getOrElse {
      val initialPlayer = player.getOrElse(search_text.getOrElse(""))
      
      val userEmail = getSessionUserEmail(request)
      if (oauthEnabled && userEmail.isEmpty) {
        logger.info(s"[MY-GAMES] Redirecting to /login because userEmail is empty (oauthEnabled=$oauthEnabled)")
        noCacheRedirect("/login")
      } else {
        val targetEmail = if (oauthEnabled) userEmail else None
        val settings = Await.result(SettingsRepository.getAppSettings(targetEmail), 10.seconds)
        
        if (!settings.isConfigured) {
          logger.info(s"[MY-GAMES] Redirecting to /config because settings are not configured for $targetEmail")
          noCacheRedirect("/config")
        } else {
          cask.Response(
            renderMaintenancePage(initialPlayer, userEmail, settings).render,
            headers = Seq("Content-Type" -> "text/html; charset=utf-8")
          )
        }
      }
    }
  }

  def renderMaintenancePage(initialPlayer: String = "", userEmail: Option[String] = None, settings: AppSettings) = {
    Components.layout(
      "My Games Dashboard", 
      userEmail, 
      settings,
      appVersion,
      scripts = Seq(
        script(src := "https://cdn.jsdelivr.net/npm/chart.js"),
        script(src := "/js/maintenance.js")
      )
    )(
      div(cls := "d-flex justify-content-between align-items-center mb-4")(
        h1("Shogi Game Fetcher"),
        div(cls := "d-flex gap-2")(
          button(cls := "btn btn-outline-warning reload-data", title := "Refresh data") (
            i(cls := "bi bi-arrow-clockwise me-1"), "Refresh Data"
          ),
          a(href := "/viewer", cls := "btn btn-info")("Open Puzzle Viewer")
        )
      ),
      Components.gameFetcherCard("Lishogi Games", "lishogi", settings.lishogiNickname, "Click 'Fetch' to load Lishogi games."),
      Components.gameFetcherCard("ShogiWars Games", "shogiwars", settings.shogiwarsNickname, "Click 'Fetch' to load ShogiWars games."),
      Components.gameFetcherCard("81Dojo Games", "dojo81", settings.dojo81Nickname, "Click 'Fetch' to load 81Dojo games."),
      
      input(`type` := "hidden", id := "lishogiNickname", value := settings.lishogiNickname),
      input(`type` := "hidden", id := "shogiwarsNickname", value := settings.shogiwarsNickname),
      input(`type` := "hidden", id := "dojo81Nickname", value := settings.dojo81Nickname),
      
      // Graph Modal
      div(cls := "modal fade", id := "graphModal", tabindex := "-1")(
        div(cls := "modal-dialog modal-fullscreen")(
          div(cls := "modal-content bg-dark text-light border-secondary")(
            div(cls := "modal-header border-secondary")(
              h5(cls := "modal-title", id := "graphTitle")("Game Analysis Graph"),
              button(`type` := "button", cls := "btn-close btn-close-white", attr("data-bs-dismiss") := "modal")()
            ),
            div(cls := "modal-body d-flex flex-column")(
              div(cls := "flex-grow-1", style := "min-height: 0;")(
                canvas(id := "analysisChart")
              )
            )
          )
        )
      )
    )
  }

  @cask.get("/maintenance-tasks")
  def listTasks() = {
    val tasks = TaskManager.getAllTasks.map { t =>
      ujson.Obj(
        "id" -> t.id,
        "status" -> t.status,
        "message" -> t.message,
        "kifHash" -> t.kifHash.map(ujson.Str).getOrElse(ujson.Null)
      )
    }
    corsResponse(ujson.write(tasks))
  }

  @cask.post("/maintenance-analyze")
  def analyze(request: cask.Request): cask.Response[String] = {
    val body = request.text()
    logger.info(s"[MAINTENANCE] Received analysis request")
    val jsonResult = try {
      Right(ujson.read(body))
    } catch {
      case e: Exception =>
        logger.error(s"[MAINTENANCE] Failed to parse JSON body: $body", e)
        Left(cask.Response(s"Invalid JSON: ${e.getMessage}", statusCode = 400, headers = Seq("Access-Control-Allow-Origin" -> "*")))
    }
    
    jsonResult match {
      case Left(errorResponse) => errorResponse
      case Right(json) =>
        val source = json.obj.get("source").map(_.str).getOrElse("unknown")
        val player = try { json("player").str } catch { case _: Exception => "unknown" }
        val kif = try { json("kif").str } catch { case _: Exception => "" }

        if (kif.isEmpty) {
          logger.error(s"[MAINTENANCE] KIF is missing in the request")
          cask.Response("KIF is required", statusCode = 400, headers = Seq("Access-Control-Allow-Origin" -> "*"))
        } else {
          val userEmail = getSessionUserEmail(request)
          logger.info(s"[MAINTENANCE] Analysis request received for player: $player, source: $source, user: $userEmail")
          
          val kifHash = GameRepository.md5Hash(kif)
          val taskId = TaskManager.createTask(Some(kifHash))
          
          Future {
            try {
              val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
              val engineManager = getEngineManager(settings.enginePath)
              
              logger.info(s"[MAINTENANCE] Using engine: ${settings.enginePath}")
              val analyzer = new GameAnalyzer(engineManager)
              val extractor = new PuzzleExtractor(analyzer)
              
              TaskManager.updateProgress(taskId, "Parsing KIF...")
              logger.info(s"[MAINTENANCE] Parsing KIF...")
              val parsedGame = GameLoader.parseKif(kif, Some(player))
              logger.info(s"[MAINTENANCE] KIF parsed. Starting shallow analysis (limit: ${settings.shallowLimit})...")
              val shallowResults = analyzer.analyzeShallow(parsedGame, settings.shallowLimit, msg => TaskManager.updateProgress(taskId, msg))
              
              TaskManager.updateProgress(taskId, "Extracting puzzles...")
              logger.info(s"[MAINTENANCE] Shallow analysis complete. Extracting puzzles (limit: ${settings.deepLimit}, threshold: ${settings.winChanceDropThreshold})...")
              val puzzles = extractor.extract(
                parsedGame, 
                shallowResults, 
                settings.winChanceDropThreshold, 
                settings.deepLimit
              )
              
              val kifHashFromRepo = GameRepository.md5Hash(kif)
              val exists = Await.result(GameRepository.exists(kif), 10.seconds)
              
              if (!exists) {
                val gameDetails = Map(
                  "sente" -> parsedGame.sentePlayerName.getOrElse("Unknown"),
                  "gote" -> parsedGame.gotePlayerName.getOrElse("Unknown"),
                  "date" -> parsedGame.gameDate.getOrElse(""),
                  "site" -> parsedGame.gameSite.getOrElse(source)
                )
                logger.info(s"[MAINTENANCE] Saving new game to DB...")
                try {
                  Await.result(GameRepository.saveGame(kif, gameDetails), 10.seconds)
                } catch {
                  case e: com.mongodb.MongoWriteException if e.getError.getCategory == com.mongodb.ErrorCategory.DUPLICATE_KEY =>
                    logger.info(s"[MAINTENANCE] Game $kifHashFromRepo was just inserted by another request. Cleaning up old analysis...")
                    Await.result(GameRepository.deleteAnalysis(kifHashFromRepo), 10.seconds)
                }
              } else {
                logger.info(s"[MAINTENANCE] Game $kifHashFromRepo already exists in DB. Cleaning up old analysis...")
                Await.result(GameRepository.deleteAnalysis(kifHashFromRepo), 10.seconds)
              }
              
              logger.info(s"[MAINTENANCE] Found ${puzzles.size} puzzles for game $kifHashFromRepo")
              Await.result(GameRepository.markAsAnalyzed(kifHashFromRepo), 10.seconds)
              
              val scores = shallowResults.map(_.evaluationScore.forPlayer(Color.Sente).toNumeric)
              Await.result(GameRepository.saveScores(kifHashFromRepo, scores), 10.seconds)
              
              puzzles.foreach { p =>
                val json = ujson.write(PuzzleJsonSerializer.puzzleToJson(p))
                Await.result(GameRepository.savePuzzle(json, kifHashFromRepo), 10.seconds)
              }
              
              logger.info(s"[MAINTENANCE] Analysis complete for $kifHashFromRepo. ${puzzles.size} puzzles found and saved.")
              TaskManager.complete(taskId, s"Analysis complete. ${puzzles.size} puzzles found.")
            } catch {
              case e: Exception =>
                logger.error(s"!!! [MAINTENANCE] Analysis failed", e)
                TaskManager.fail(taskId, e.getMessage)
            }
          }
          
          corsResponse(ujson.write(ujson.Obj("taskId" -> taskId)))
        }
    }
  }

  @cask.get("/maintenance-task-status")
  def taskStatus(id: String) = {
    TaskManager.getTask(id) match {
      case Some(task) =>
        ujson.Obj(
          "id" -> task.id,
          "status" -> task.status,
          "message" -> task.message,
          "resultHtml" -> ujson.Str(task.resultHtml.getOrElse("")),
          "error" -> ujson.Str(task.error.getOrElse(""))
        )
      case None =>
        ujson.Obj("error" -> "Task not found")
    }
  }

  @cask.get("/maintenance-fetch")
  def fetch(player: String = "", source: String = "shogiwars", search_text: String = "", force: Boolean = false, limit: Int = 10, request: cask.Request): cask.Response[String] = {
    val userEmail = getSessionUserEmail(request)
    val targetPlayer = if (player.nonEmpty) player else search_text
    val sourceId = source
    val cacheKey = s"$sourceId:$targetPlayer"
    logger.info(s"[MAINTENANCE] Fetch request for player: '$targetPlayer', source: '$sourceId', force: $force, limit: $limit, user: $userEmail")
    
    if (targetPlayer.trim.isEmpty) {
       corsResponse(ujson.write(ujson.Obj("error" -> s"No nickname configured or provided for source '$sourceId'.")))
    } else {
      val taskId = TaskManager.createTask()
      
      Future {
        try {
          logger.info(s"[MAINTENANCE] Processing fetch for '$targetPlayer' from '$sourceId'")
          val games = if (!force) {
            logger.info(s"[MAINTENANCE] Fetching games from DB for $targetPlayer, source $sourceId with limit $limit")
            val dbGames = Await.result(GameRepository.findByPlayerAndSource(targetPlayer, sourceId, limit), 10.seconds)
            logger.info(s"[MAINTENANCE] DB returned ${dbGames.size} games")
            dbGames.map { doc =>
              SearchGame(
                sente = doc.get("sente").map(v => cleanPlayerName(v.asString().getValue)).getOrElse(""),
                gote = doc.get("gote").map(v => cleanPlayerName(v.asString().getValue)).getOrElse(""),
                date = doc.get("date").map(_.asString().getValue).getOrElse(""),
                kif = doc.get("kif").map(_.asString().getValue),
                existsInDb = true,
                isAnalyzed = doc.get("is_analyzed").exists(_.asBoolean().getValue),
                puzzleCount = 0,
                site = doc.get("site").map(_.asString().getValue)
              )
            }
          } else {
            logger.info(s"[MAINTENANCE] Fetching games from external source $sourceId for $targetPlayer with limit $limit")
            val fetcher = sources.getOrElse(sourceId, ShogiWarsSource)
            val fetched = fetcher.fetchGames(targetPlayer, limit, userEmail, msg => TaskManager.updateProgress(taskId, msg))
            gamesCache(cacheKey) = fetched
            fetched
          }

          val gamesWithDbStatus = games.map { g =>
            val kifHash = g.kif.map(GameRepository.md5Hash)
            
            val (exists, analyzed, pCount) = if (g.existsInDb) {
              val hash = kifHash.get
              val count = Await.result(GameRepository.countPuzzlesForGame(hash), 5.seconds).toInt
              (true, g.isAnalyzed, count)
            } else {
              val dbGame = kifHash.flatMap(hash => Await.result(GameRepository.getGameByHash(hash), 5.seconds))
              
              val ex = dbGame.isDefined
              val an = dbGame.exists(_.get("is_analyzed").exists(_.asBoolean().getValue))
              val pc = if (ex && an) Await.result(GameRepository.countPuzzlesForGame(kifHash.get), 5.seconds).toInt else 0
              (ex, an, pc)
            }
            
            g.copy(existsInDb = exists, isAnalyzed = analyzed, puzzleCount = pCount)
          }

          val resultHtml = div(
            div(cls := "d-flex justify-content-between align-items-center")(
              h3(s"Results for $targetPlayer"),
              tag("span")(cls := s"badge ${if (force) "bg-info" else "bg-secondary"}")(
                if (force) "Fetched from Website" else "Fetched from DB"
              )
            ),
            if (gamesWithDbStatus.isEmpty) p("No games found.")
            else
              div(cls := "table-responsive")(
                table(cls := "table table-dark table-hover")(
                  thead(
                    tr(th(""), th("Sente"), th("Gote"), th("Date"), th("Status"), th("Puzzles"), th("Analysis"), th("Actions"))
                  ),
                  tbody(
                    gamesWithDbStatus.map { g =>
                      val kifHash = g.kif.map(GameRepository.md5Hash).getOrElse("")
                      val kif = g.kif.getOrElse("")
                      val kifHashStr = if (kif.nonEmpty) {
                        var h = 0
                        var i = 0
                        while (i < kif.length) {
                          h = 31 * h + kif.charAt(i)
                          i += 1
                        }
                        java.lang.Math.abs(h).toHexString
                      } else ""
                      tr(
                        td(
                          if (!g.existsInDb && g.kif.isDefined)
                            input(`type` := "checkbox", cls := "game-check", 
                              attr("data-sente") := g.sente,
                              attr("data-gote") := g.gote,
                              attr("data-date") := g.date,
                              attr("data-kif") := kif,
                              attr("data-site") := g.site.getOrElse(""),
                              checked := true
                            )
                          else ""
                        ),
                        td(g.sente),
                        td(g.gote),
                        td(g.date),
                        td(
                          if (g.existsInDb) tag("span")(cls := "badge bg-success")("In DB")
                          else tag("span")(cls := "badge bg-warning text-dark")("New")
                        ),
                        td(if (g.isAnalyzed) tag("span")(g.puzzleCount.toString) else "-"),
                        td(
                          if (g.existsInDb) {
                            if (g.isAnalyzed) {
                              div(
                                tag("span")(cls := "badge bg-info")("Analyzed"),
                                a(href := s"/viewer?hash=$kifHash", target := "_blank", cls := "btn btn-sm btn-primary ms-2")("Puzzles"),
                                button(cls := "btn btn-sm btn-outline-primary ms-2 graph-btn",
                                  attr("data-hash") := kifHash,
                                  attr("data-sente") := g.sente,
                                  attr("data-gote") := g.gote
                                )("Graph"),
                                a(href := s"/lishogi-redirect?hash=$kifHash", target := "_blank", cls := "btn btn-sm btn-outline-success ms-2")("Lishogi")
                              )
                            } else button(cls := s"btn btn-sm btn-warning analyze-btn btn-task-$kifHashStr", 
                                        attr("data-kif") := kif,
                                        attr("data-player") := targetPlayer,
                                        attr("data-site") := g.site.getOrElse(""))("Analyze")
                          } else ""
                        ),
                        td(
                          if (g.isAnalyzed) {
                            button(cls := "btn btn-sm btn-danger delete-analysis-btn",
                              attr("data-hash") := kifHash,
                              attr("data-player") := targetPlayer)("Delete Analysis")
                          } else ""
                        )
                      )
                    }
                  )
                ),
                if (gamesWithDbStatus.exists(g => !g.existsInDb && g.kif.isDefined)) {
                  button(id := "storeBtn", cls := "btn btn-success")("Download Selected to DB")
                } else ""
              )
          )
          TaskManager.complete(taskId, resultHtml.render)
        } catch {
          case e: Exception =>
            logger.error(s"[MAINTENANCE] Fetch failed", e)
            TaskManager.fail(taskId, e.getMessage)
        }
      }
      
      corsResponse(ujson.write(ujson.Obj("taskId" -> taskId)))
    }
  }

  private def cleanPlayerName(name: String): String = {
    name.stripPrefix("先手：").stripPrefix("後手：")
      .replaceAll("\\s+\\d+[級段].*$", "")
      .trim
  }

  @cask.post("/maintenance-store")
  def storeBatch(request: cask.Request) = {
    logger.info(s"[MAINTENANCE] Received store request")
    val games = try {
      val text = request.text()
      logger.debug(s"[MAINTENANCE] Raw store request body: $text")
      ujson.read(text).arr.toSeq
    } catch {
      case e: Exception =>
        logger.error(s"[MAINTENANCE] Failed to parse JSON in storeBatch: ${e.getMessage}")
        throw e
    }
    var stored = 0
    var duplicates = 0

    games.foreach { g =>
      val kif = g("kif").str
      val exists = Await.result(GameRepository.exists(kif), 5.seconds)
      if (!exists) {
        val parsed = try {
          Some(GameLoader.parseKif(kif))
        } catch {
          case e: Exception =>
            logger.error(s"[MAINTENANCE] KIF parsing failed: ${e.getMessage}")
            None
        }

        val details = Map(
          "sente" -> parsed.flatMap(_.sentePlayerName).getOrElse(cleanPlayerName(g("sente").str)),
          "gote" -> parsed.flatMap(_.gotePlayerName).getOrElse(cleanPlayerName(g("gote").str)),
          "date" -> parsed.flatMap(_.gameDate).getOrElse(g("date").str),
          "site" -> parsed.flatMap(_.gameSite).getOrElse(g.obj.get("site").map(_.str).getOrElse(""))
        )
        try {
          Await.result(GameRepository.saveGame(kif, details), 5.seconds)
          stored += 1
        } catch {
          case e: com.mongodb.MongoWriteException if e.getError.getCategory == com.mongodb.ErrorCategory.DUPLICATE_KEY =>
            logger.info(s"[MAINTENANCE] Game already stored during batch (race condition)")
            duplicates += 1
        }
      } else {
        duplicates += 1
      }
    }

    corsResponse(ujson.Obj("stored" -> stored, "duplicates" -> duplicates))
  }

  @cask.post("/maintenance-delete-analysis")
  def deleteAnalysis(request: cask.Request) = {
    val body = request.text()
    logger.debug(s"[MAINTENANCE] Raw delete analysis request body: $body")
    val hash = try {
      ujson.read(body)("hash").str
    } catch {
      case e: Exception =>
        logger.error(s"[MAINTENANCE] Failed to parse delete analysis request: $body", e)
        throw e
    }
    logger.info(s"[MAINTENANCE] Deleting analysis for hash $hash")
    Await.result(GameRepository.deleteAnalysis(hash), 10.seconds)
    corsResponse(ujson.Obj("success" -> true))
  }

  @cask.get("/maintenance-analysis-data")
  def analysisData(hash: String) = {
    val game = Await.result(GameRepository.getGameByHash(hash), 5.seconds)
    val puzzles = Await.result(GameRepository.getPuzzlesForGame(hash), 5.seconds)
    
    val scores = game.flatMap(_.get("scores")).map { s =>
      s.asArray().getValues.asScala.map { v =>
        if (v.isInt32) v.asInt32().getValue
        else if (v.isInt64) v.asInt64().getValue.toInt
        else if (v.isDouble) v.asDouble().getValue.toInt
        else 0
      }.toSeq
    }.getOrElse(Seq.empty)
    
    val puzzleDetails = puzzles.map { p =>
      val moveNumber = p.get("move_number").map { v =>
        if (v.isInt32) v.asInt32().getValue
        else if (v.isInt64) v.asInt64().getValue.toInt
        else if (v.isDouble) v.asDouble().getValue.toInt
        else 1
      }.getOrElse(1)
      val ply = moveNumber - 1
      val id = p.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
      val comment = p.get("comment").map(_.asString().getValue).getOrElse("")
      ujson.Obj(
        "ply" -> ujson.Num(ply.toDouble),
        "id" -> ujson.Str(id),
        "comment" -> ujson.Str(comment)
      )
    }
    
    corsResponse(ujson.Obj(
      "scores" -> ujson.Arr(scores.map(s => ujson.Num(s.toDouble)): _*),
      "puzzles" -> ujson.Arr(puzzleDetails: _*)
    ))
  }

  @cask.route("/maintenance-<path:rest>", methods = Seq("OPTIONS"))
  def maintenanceOptions(rest: String) = {
    logger.info(s"[MAINTENANCE] OPTIONS request for $rest")
    cask.Response(
      "",
      headers = Seq(
        "Access-Control-Allow-Origin" -> "*",
        "Access-Control-Allow-Methods" -> "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers" -> "Content-Type, Access-Control-Allow-Headers, Authorization, X-Requested-With"
      )
    )
  }

  @cask.get("/lishogi-redirect")
  def lishogiRedirect(hash: String = "") = {
    logger.info(s"[MAINTENANCE] lishogiRedirect entered. Hash param: '$hash'")

    try {
      if (hash.isEmpty) {
        logger.error("[MAINTENANCE] Error: hash parameter is missing or empty")
        cask.Response("Missing hash parameter", statusCode = 400)
      } else {
        getAnnotatedKif(hash) match {
          case Right(annotated) =>
            logger.info(s"[MAINTENANCE] Annotated KIF for Lishogi (hash: $hash):")
            val client = HttpClient.newBuilder()
              .followRedirects(HttpClient.Redirect.NEVER)
              .build()

            val formData = s"notation=${URLEncoder.encode(annotated, StandardCharsets.UTF_8)}&analyse=false"

            val request = HttpRequest.newBuilder()
              .uri(URI.create("https://lishogi.org/import"))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
              .POST(HttpRequest.BodyPublishers.ofString(formData))
              .build()

            val response = client.send(request, HttpResponse.BodyHandlers.discarding())

            logger.info(s"[MAINTENANCE] Lishogi import response: ${response.statusCode()}")

            if (response.statusCode() == 303) {
              val location = response.headers().firstValue("Location").orElse("")
              val fullUrl = if (location.startsWith("http")) location else "https://lishogi.org" + location
              logger.info(s"[MAINTENANCE] Redirecting user to: $fullUrl")
              cask.Redirect(fullUrl)
            } else {
              val errorMsg = s"Lishogi returned status ${response.statusCode()}"
              logger.error(s"[MAINTENANCE] Error: $errorMsg")
              cask.Response(errorMsg, statusCode = 500)
            }
          case Left(error) =>
            logger.error(s"[MAINTENANCE] Game not found for hash: $hash")
            cask.Response(error, statusCode = 404)
        }
      }
    } catch {
      case e: Exception =>
        logger.error(s"[MAINTENANCE] CRITICAL ERROR in lishogiRedirect: ${e.getMessage}", e)
        cask.Response(s"Internal error: ${e.getMessage}", statusCode = 500)
    }
  }

  @cask.get("/maintenance-annotated-kif")
  def annotatedKif(hash: String) = {
    val responseObj = getAnnotatedKif(hash) match {
      case Right(annotated) => ujson.Obj("success" -> true, "kif" -> annotated)
      case Left(error) => ujson.Obj("success" -> false, "error" -> error)
    }
    corsResponse(responseObj)
  }

  private def getAnnotatedKif(hash: String): Either[String, String] = {
    val gameDoc = Await.result(GameRepository.getGameByHash(hash), 5.seconds)
    val puzzles = Await.result(GameRepository.getPuzzlesForGame(hash), 5.seconds)

    logger.info(s"[MAINTENANCE] Annotating KIF for hash $hash. Puzzles found: ${puzzles.size}")

    gameDoc match {
      case Some(doc) =>
        val kif = doc.get("kif").map(_.asString().getValue).getOrElse("")
        val comments = puzzles.flatMap { p =>
          val moveNumber = p.get("move_number").map { v =>
            if (v.isInt32) v.asInt32().getValue
            else if (v.isInt64) v.asInt64().getValue.toInt
            else if (v.isDouble) v.asDouble().getValue.toInt
            else 1
          }.getOrElse(1)
          val comment = p.get("comment").map(_.asString().getValue).getOrElse("")
          if (comment.nonEmpty) {
            logger.info(s"[MAINTENANCE]   Adding comment to move $moveNumber: ${comment.take(20)}...")
            Some(moveNumber -> comment)
          } else None
        }.toMap

        val annotated = KifAnnotator.annotate(kif, comments)
        Right(annotated)
      case None =>
        Left("Game not found")
    }
  }

  initialize()
}
