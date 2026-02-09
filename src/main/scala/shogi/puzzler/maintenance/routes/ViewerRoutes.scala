package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.Color
import shogi.puzzler.db.{GameRepository, SettingsRepository, AppSettings}
import shogi.puzzler.ui.Components
import shogi.puzzler.engine.{EngineManager, Limit}
import shogi.puzzler.domain.{Score, CpScore, MateScore}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.mutable

object ViewerRoutes extends BaseRoutes {

  private val engineManagers = mutable.Map[String, EngineManager]()

  private def getEngineManager(name: String): EngineManager = {
    logger.info(s"[VIEWER] Requesting engine manager for: $name")
    engineManagers.getOrElseUpdate(name, {
      logger.info(s"[VIEWER] Creating new engine manager for: $name")
      try {
        val manager = new EngineManager(Seq(name))
        manager.initialize()
        logger.info(s"[VIEWER] Engine manager created successfully for: $name")
        manager
      } catch {
        case e: Exception =>
          logger.error(s"[VIEWER] Failed to create engine manager for: $name", e)
          throw e
        }
    })
  }

  @cask.get("/viewer")
  def viewer(hash: Option[String] = None, request: cask.Request) = {
    withAuth(request, "viewer") { email =>
      val userEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      cask.Response(
        renderViewer(userEmail, settings).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  def renderViewer(userEmail: Option[String] = None, settings: AppSettings) = {
    html(lang := "en", cls := "dark", style := "--zoom:90;")(
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", content := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(name := "theme-color", content := "#2e2a24"),
        tag("title")("Shogi puzzle"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"),
        link(rel := "stylesheet", href := "/assets/css/shogiground.css"),
        link(rel := "stylesheet", href := "/assets/css/portella.css"),
        link(rel := "stylesheet", href := "/assets/css/site.css"),
        link(rel := "stylesheet", href := "/assets/css/puzzle.css"),
        link(rel := "stylesheet", href := "/assets/css/common.css"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/select2@4.1.0-rc.0/dist/css/select2.min.css"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/select2-bootstrap-5-theme@1.3.0/dist/select2-bootstrap-5-theme.min.css"),
        link(rel := "stylesheet", href := "/assets/css/select2-dark.css"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.10.3/font/bootstrap-icons.css"),
        script(src := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js")
      ),
      body(cls := "wood coords-out playing online")(
        Components.renderHeader(userEmail, settings, appVersion),
        div(id := "main-wrap")(
          tag("main")(cls := "puzzle puzzle-play")(
            div(cls := "puzzle__board main-board")(
              div(cls := "sg-wrap d-9x9")(
                tag("sg-hand-wrap")(id := "hand-top"),
                div(id := "dirty"),
                tag("sg-hand-wrap")(id := "hand-bottom")
              )
            ),
            div(cls := "puzzle__controls")(
              div(cls := "container-fluid p-0")(
                div(cls := "row g-2 align-items-center mb-2")(
                  div(cls := "col-md-12")(
                    div(cls := "input-group input-group-sm flex-nowrap")(
                      button(cls := "btn btn-outline-secondary prev-puzzle", title := "Previous Puzzle") (
                        i(cls := "bi bi-chevron-left"), tag("span")(cls := "d-none d-lg-inline ms-1")("Prev")
                      ),
                      select(cls := "games form-control", style := "width: auto; flex-grow: 1; margin: 0;")(
                        option(value := "")("Select a puzzle...")
                      ),
                      button(cls := "btn btn-outline-secondary next-puzzle", title := "Next Puzzle") (
                        tag("span")(cls := "d-none d-lg-inline me-1")("Next"), i(cls := "bi bi-chevron-right")
                      )
                    ),
                    div(cls := "mt-2")(
                      div(cls := "form-check form-switch")(
                        input(cls := "form-check-input", `type` := "checkbox", id := "isPublicCheckbox"),
                        label(cls := "form-check-label text-light", `for` := "isPublicCheckbox")("Public Puzzle")
                      )
                    ),
                    button(cls := "btn btn-sm btn-outline-warning reload-data w-100 mt-2", title := "Reload data from DB") (
                      i(cls := "bi bi-arrow-clockwise me-1"), tag("span")(cls := "d-none d-lg-inline")("Reload Data"), tag("span")(cls := "d-inline d-lg-none")("Reload")
                    )
                  )
                ),
                div(cls := "row g-2 align-items-center")(
                  div(cls := "col-4")(
                    button(cls := "btn btn-sm btn-secondary random w-100", title := "Random Puzzle") (
                      i(cls := "bi bi-shuffle me-1"), tag("span")(cls := "d-none d-lg-inline")("Random")
                    )
                  ),
                  div(cls := "col-4")(
                    button(cls := "btn btn-sm btn-info lishogi-game w-100", title := "View on Lishogi") (
                      i(cls := "bi bi-box-arrow-up-right me-1"), tag("span")(cls := "d-none d-lg-inline")("Game")
                    )
                  ),
                  div(cls := "col-4")(
                    button(cls := "btn btn-sm btn-outline-info lishogi-position w-100", title := "Analyze on Lishogi") (
                      i(cls := "bi bi-search me-1"), tag("span")(cls := "d-none d-lg-inline")("Pos")
                    )
                  )
                )
              )
            ),
            div(cls := "puzzle__side")(
              div(cls := "puzzle__side__box")(
                div(cls := "puzzle__feedback")(
                  div(cls := "content")("Play the correct move!"),
                  div(id := "turn-text", cls := "badge bg-secondary mb-1")("-"),
                  div(id := "players-text", cls := "text-muted mb-3", style := "font-size: 0.8rem;")("-"),
                  div(id := "material-text", style := "display:none")("-"),
                  button(id := "play-continuation", cls := "btn btn-sm btn-outline-success w-100 mb-2", style := "display:none") (
                    i(cls := "bi bi-play-fill me-1"), "Play continuation"
                  ),
                  div(id := "continuation-options", cls := "mb-2", style := "display:none")(),
                  div(id := "continuation-controls", cls := "btn-group btn-group-sm w-100 mb-2", style := "display:none") (
                    button(id := "continuation-back", cls := "btn btn-outline-secondary", title := "Back") (
                      i(cls := "bi bi-skip-start-fill")
                    ),
                    button(id := "continuation-autoplay", cls := "btn btn-outline-success", title := "Autoplay") (
                      i(cls := "bi bi-play-fill")
                    ),
                    button(id := "continuation-next", cls := "btn btn-outline-secondary", title := "Next") (
                      i(cls := "bi bi-skip-end-fill")
                    )
                  ),
                  button(id := "show-hints", cls := "btn btn-sm btn-outline-info w-100 mb-2") (
                    i(cls := "bi bi-lightbulb-fill me-1"), "Show Hints"
                  ),
                  textarea(cls := "content mt-2 form-control", style := "display:none")(),
                  button(cls := "btn btn-primary save-comment mt-2", style := "display:none")("Save Comment")
                )
              )
            )
          )
        ),
        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        script(src := "https://cdn.jsdelivr.net/npm/sweetalert2@11"),
        script(src := "https://cdnjs.cloudflare.com/ajax/libs/jquery/3.6.3/jquery.min.js", attr("integrity") := "sha512-STof4xm1wgkfm7heWqFJVn58Hm3EtS31XFaagaa8VMReCXAkQnJZ+jEy8PCC/iT18dFy95WcExNHFTqLyp72eQ==", attr("crossorigin") := "anonymous", attr("referrerpolicy") := "no-referrer"),
        script(src := "https://cdn.jsdelivr.net/npm/select2@4.1.0-rc.0/dist/js/select2.min.js"),
        script(src := "/js/puzzle.js", `type` := "module")
      )
    )
  }

  @cask.get("/data")
  def puzzles(hash: Option[String] = None, request: cask.Request) = {
    withAuthJson(request, "viewer") { email =>
      logger.info(s"[VIEWER] Fetching puzzles with hash: ${hash.getOrElse("none")}")
      
      val puzzles = hash match {
        case Some(h) =>
          // First try to get puzzles by game_kif_hash field
          val puzzlesByHash = Await.result(GameRepository.getPuzzlesForGame(h), 10.seconds)
          logger.info(s"[VIEWER] Found ${puzzlesByHash.size} puzzles by game_kif_hash")
          
          // If no puzzles found, try to find by puzzle ID prefix (for backward compatibility)
          if (puzzlesByHash.isEmpty) {
            logger.info(s"[VIEWER] No puzzles found by game_kif_hash, trying ID prefix match")
            val allPuzzles = Await.result(GameRepository.getAllPuzzles(), 10.seconds)
            val filtered = allPuzzles.filter { doc =>
              doc.get("id").exists { idValue =>
                val id = idValue match {
                  case s: org.bson.BsonString => s.getValue
                  case _ => ""
                }
                id.startsWith(h + "#")
              }
            }
            logger.info(s"[VIEWER] Found ${filtered.size} puzzles by ID prefix")
            filtered
          } else {
            puzzlesByHash
          }
        case None =>
          // For authenticated viewer without hash, show ALL puzzles (not just public)
          // This allows users to manage puzzle visibility
          val allPuzzles = Await.result(GameRepository.getAllPuzzles(), 10.seconds)
          logger.info(s"[VIEWER] Found ${allPuzzles.size} total puzzles")
          
          // Also include custom puzzles from puzzle-creator for this user
          val customPuzzles = Await.result(GameRepository.getCustomPuzzles(email), 10.seconds)
          logger.info(s"[VIEWER] Found ${customPuzzles.size} custom puzzles for user $email")
          
          // Convert custom puzzles to viewer format and combine
          val convertedCustomPuzzles = customPuzzles.map { doc =>
            GameRepository.convertCustomPuzzleToViewerFormat(doc)
          }
          
          allPuzzles ++ convertedCustomPuzzles
      }
      
      val sortedPuzzles = puzzles.sortBy { doc =>
        doc.get("move_number").map { v =>
          if (v.isInt32) v.asInt32().getValue
          else if (v.isInt64) v.asInt64().getValue.toInt
          else if (v.isDouble) v.asDouble().getValue.toInt
          else 0
        }.getOrElse(0)
      }

      val jsonArray = sortedPuzzles.map { doc =>
        ujson.read(doc.toJson())
      }
      
      logger.info(s"[VIEWER] Returning ${jsonArray.size} puzzles")
      cask.Response(
        ujson.Arr(jsonArray: _*),
        headers = Seq(
          "Content-Type" -> "application/json",
          "Cache-Control" -> "no-cache, no-store, must-revalidate"
        )
      )
    }
  }

  @cask.post("/viewer/toggle-public")
  def togglePublic(request: cask.Request) = {
    withAuthJson(request, "viewer") { _ =>
      val json = ujson.read(request.text())
      val id = json("id").str
      val isPublic = json("isPublic").bool
      
      Await.result(GameRepository.togglePuzzlePublic(id, isPublic), 10.seconds)
      cask.Response(ujson.Obj("success" -> true), headers = Seq("Content-Type" -> "application/json"))
    }
  }

  @cask.post("/viewer/analyze")
  def analyzePosition(request: cask.Request) = {
    withAuthJson(request, "viewer") { email =>
      val settings = Await.result(SettingsRepository.getAppSettings(Some(email)), 10.seconds)
      val json = ujson.read(request.text())
      val sfen = json("sfen").str
      val playerColor = json.obj.get("playerColor").map(_.str).getOrElse("sente")
      
      // Analysis settings
      val depth = json.obj.get("depth").map(_.num.toInt).getOrElse(10)
      val multiPv = json.obj.get("multiPv").map(_.num.toInt).getOrElse(1)
      
      try {
        // Create a fresh engine for each request to avoid concurrency issues
        logger.info(s"[VIEWER] Creating fresh engine for analysis")
        val engineManager = new EngineManager(Seq(settings.enginePath))
        engineManager.initialize()
        
        // Validate SFEN format before sending to engine
        val sfenParts = sfen.split(" ")
        if (sfenParts.length < 3) {
          throw new IllegalArgumentException("Invalid SFEN format: " + sfen)
        }
        
        // Use depth only for analysis
        val limit = Limit(depth = Some(depth))
        val results = engineManager.analyze(sfen, limit, multiPv)
        
        // Note: Engine process will be cleaned up when EngineManager is garbage collected
        // Determine whose turn it is from the SFEN
        val colorToMove = sfenTurnToColor(sfen)
        
        // Convert playerColor string to Color
        val playerColorEnum = if (playerColor == "sente") Color.Sente else Color.Gote
        
        val movesArray = results.map { result =>
          // Extract the first move from the PV (Principal Variation)
          val usiMove = result.get("pv") match {
            case Some(pvList: List[_]) if pvList.nonEmpty => pvList.head.toString
            case Some(pvStr: String) if pvStr.nonEmpty => pvStr.split(" ").head
            case _ => result.get("usi").orElse(result.get("move")).getOrElse("").toString
          }
          
          // Get score from player's perspective
          val rawScore = result.get("score")
          val povScore = Score.fromEngine(rawScore, colorToMove)
          val playerScore = povScore.forPlayer(playerColorEnum)
          
          val (scoreKind, scoreValue) = playerScore match {
            case CpScore(cp) => ("cp", cp)
            case MateScore(moves) => ("mate", moves)
            case _ => ("cp", 0)
          }
          
          ujson.Obj(
            "usi" -> usiMove,
            "score" -> ujson.Obj("kind" -> scoreKind, "value" -> scoreValue),
            "depth" -> ujson.Num(result.getOrElse("depth", depth).toString.toInt),
            "pv" -> (result.get("pv") match {
              case Some(pvList: List[_]) => pvList.mkString(" ")
              case Some(pvStr: String) => pvStr
              case _ => ""
            })
          )
        }
        
        cask.Response(
          ujson.Obj("success" -> true, "moves" -> ujson.Arr(movesArray: _*)),
          headers = Seq("Content-Type" -> "application/json")
        )
      } catch {
        case e: java.io.EOFException =>
          logger.error(s"[VIEWER] Engine crashed (EOFException). SFEN: $sfen", e)
          cask.Response(
            ujson.Obj("success" -> false, "error" -> "Engine crashed. Please try again."),
            headers = Seq("Content-Type" -> "application/json"),
            statusCode = 500
          )
        case e: Exception =>
          val errorMsg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
          logger.error(s"[VIEWER] Analysis error: $errorMsg", e)
          cask.Response(
            ujson.Obj("success" -> false, "error" -> errorMsg),
            headers = Seq("Content-Type" -> "application/json"),
            statusCode = 500
          )
      }
    }
  }

  /**
   * Determine which color has the move from SFEN.
   * SFEN format: board turn hands moves
   * turn is 'b' for sente (Black) and 'w' for gote (White)
   */
  private def sfenTurnToColor(sfen: String): Color = {
    val parts = sfen.split(" ")
    if (parts.length >= 2) {
      if (parts(1) == "b") Color.Sente else Color.Gote
    } else {
      Color.Sente // Default to sente if SFEN is malformed
    }
  }

  initialize()
}
