package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.Color
import shogi.puzzler.db.{GameRepository, PuzzleRepository, CustomPuzzleRepository, SettingsRepository, AppSettings}
import shogi.puzzler.ui.Components
import shogi.puzzler.engine.{EngineManager, Limit}
import shogi.puzzler.analysis.{AnalysisService, SfenUtils}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.collection.mutable

object ViewerRoutes extends BaseRoutes {

  @cask.get("/viewer")
  def viewer(hash: Option[String] = None, request: cask.Request) = {
    redirectToConfiguredHostIfNeeded(request).getOrElse {
      val userEmail = getSessionUserEmail(request)
      val isAuthenticated = userEmail.exists(email => canAccessPage(email, "viewer"))
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      cask.Response(
        renderViewer(userEmail, settings, isAuthenticated).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  def renderViewer(userEmail: Option[String] = None, settings: AppSettings, isAuthenticated: Boolean = false) = {
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
      body(cls := "wood coords-out playing online", attr("data-authenticated") := isAuthenticated.toString)(
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
                    if (isAuthenticated) frag(
                      button(cls := "btn btn-sm btn-outline-warning reload-data w-100 mt-2", title := "Reload data from DB") (
                        i(cls := "bi bi-arrow-clockwise me-1"), tag("span")(cls := "d-none d-lg-inline")("Reload Data"), tag("span")(cls := "d-inline d-lg-none")("Reload")
                      )
                    ) else ()
                  )
                ),
                div(cls := "row g-2 align-items-center mb-2")(
                  div(cls := "col-8")(
                    select(id := "tag-filter", cls := "form-select form-select-sm")(
                      option(value := "")("All tags")
                    )
                  ),
                  div(cls := "col-4")(
                    select(id := "sort-order", cls := "form-select form-select-sm")(
                      option(value := "move")("Sort: Move#"),
                      option(value := "rating")("Sort: Rating"),
                      option(value := "played")("Sort: Played")
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
                  div(cls := "d-flex justify-content-between align-items-center mb-1")(
                    div(id := "turn-text", cls := "badge bg-secondary")("-"),
                    div(id := "play-count-badge", cls := "text-muted", style := "display:none; font-size:0.8rem;")(
                      i(cls := "bi bi-play-circle me-1"), tag("span")(cls := "play-count-value")("0"), " plays"
                    )
                  ),
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
                  div(id := "puzzle-stats", cls := "mt-2 pt-2", style := "display:none; border-top: 1px solid rgba(255,255,255,0.1);")(
                    div(cls := "d-flex justify-content-between align-items-center mb-1")(
                      tag("span")(id := "play-count-display", cls := "text-muted", style := "font-size:0.8rem;")(
                        i(cls := "bi bi-play-circle me-1"), tag("span")(cls := "play-count-value")("0"), " plays"
                      ),
                      tag("span")(id := "avg-rating-display", cls := "text-muted", style := "font-size:0.8rem;")(
                        i(cls := "bi bi-star-fill me-1", style := "color:#ffc107;"), tag("span")(cls := "avg-rating-value")("0"), " ", tag("span")(cls := "rating-count-value")("(0)")
                      )
                    ),
                    div(id := "star-rating", cls := "text-center mb-1")(
                      for (v <- 1 to 5) yield {
                        tag("span")(cls := "star-btn", attr("data-value") := v.toString, style := "cursor:pointer; font-size:1.5rem; color:#666; transition: transform 0.15s, color 0.15s;")(
                          i(cls := "bi bi-star")
                        )
                      },
                      div(id := "rating-label", cls := "text-muted", style := "font-size:0.75rem;")(
                        if (isAuthenticated) "Rate this puzzle" else "Login to rate"
                      )
                    )
                  ),
                  if (isAuthenticated) frag(
                    button(id := "training-btn", cls := "btn btn-sm btn-outline-warning w-100 mt-2", style := "display:none")(
                      i(cls := "bi bi-mortarboard me-1"), tag("span")(cls := "training-btn-text")("Add to Deck")
                    ),
                    textarea(cls := "content mt-2 form-control", style := "display:none")(),
                    button(cls := "btn btn-primary save-comment mt-2", style := "display:none")("Save Comment")
                  ) else ()
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
    val userEmail = getSessionUserEmail(request)
    val isAuthenticated = userEmail.exists(email => canAccessPage(email, "viewer"))

    logger.info(s"[VIEWER] Fetching puzzles with hash: ${hash.getOrElse("none")}, authenticated: $isAuthenticated")

    val puzzles = hash match {
      case Some(h) =>
        val puzzlesByHash = Await.result(PuzzleRepository.getPuzzlesForGame(h), 10.seconds)
        logger.info(s"[VIEWER] Found ${puzzlesByHash.size} puzzles by game_kif_hash")

        if (puzzlesByHash.isEmpty) {
          logger.info(s"[VIEWER] No puzzles found by game_kif_hash, trying ID prefix match")
          val allPuzzles = Await.result(PuzzleRepository.getAllPuzzles(), 10.seconds)
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
      case None if isAuthenticated =>
        val email = userEmail.get
        val userPuzzles = Await.result(PuzzleRepository.getPuzzlesForUser(email), 10.seconds)
        logger.info(s"[VIEWER] Found ${userPuzzles.size} puzzles for user $email")

        val customPuzzles = Await.result(CustomPuzzleRepository.getAcceptedCustomPuzzles(email), 10.seconds)
        logger.info(s"[VIEWER] Found ${customPuzzles.size} custom puzzles for user $email")

        val convertedCustomPuzzles = customPuzzles.map { doc =>
          CustomPuzzleRepository.convertCustomPuzzleToViewerFormat(doc)
        }

        userPuzzles ++ convertedCustomPuzzles
      case None =>
        val publicPuzzles = Await.result(CustomPuzzleRepository.getAllPublicPuzzles(), 10.seconds)
        logger.info(s"[VIEWER] Found ${publicPuzzles.size} public puzzles")
        publicPuzzles
    }

    val sortedPuzzles = puzzles.sortBy { doc =>
      doc.get("move_number").map { v =>
        if (v.isInt32) v.asInt32().getValue
        else if (v.isInt64) v.asInt64().getValue.toInt
        else if (v.isDouble) v.asDouble().getValue.toInt
        else 0
      }.getOrElse(0)
    }

    val sanitizedEmail = userEmail.map(e => e.replace(".", "_").replace("@", "_at_"))

    val jsonArray = sortedPuzzles.map { doc =>
      val obj = ujson.read(doc.toJson())

      // Inject play_count
      val playCount = obj.obj.get("play_count").flatMap(v => scala.util.Try(v.num.toInt).toOption).getOrElse(0)
      obj("play_count") = playCount

      // Compute avg_rating and rating_count from ratings map
      val ratingsMap = obj.obj.get("ratings").flatMap(v => scala.util.Try(v.obj).toOption).getOrElse(collection.mutable.LinkedHashMap.empty[String, ujson.Value])
      val ratingValues = ratingsMap.values.flatMap(v => scala.util.Try(v.num).toOption).toSeq
      val avgRating = if (ratingValues.nonEmpty) ratingValues.sum / ratingValues.size else 0.0
      val ratingCount = ratingValues.size

      obj("avg_rating") = math.round(avgRating * 10.0) / 10.0
      obj("rating_count") = ratingCount

      // Current user's rating
      val myRating = sanitizedEmail.flatMap(se => ratingsMap.get(se).flatMap(v => scala.util.Try(v.num.toInt).toOption)).getOrElse(0)
      obj("my_rating") = myRating

      // Remove raw ratings map (don't expose emails)
      obj.obj.remove("ratings")

      obj
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

  @cask.post("/viewer/rate")
  def ratePuzzle(request: cask.Request) = {
    withAuthJson(request, "viewer") { email =>
      val json = ujson.read(request.text())
      val id = json("id").str
      val stars = json("stars").num.toInt

      if (stars < 1 || stars > 5) {
        cask.Response(ujson.Obj("error" -> "Stars must be between 1 and 5"), statusCode = 400, headers = Seq("Content-Type" -> "application/json"))
      } else {
        Await.result(PuzzleRepository.ratePuzzle(id, email, stars), 10.seconds)
        cask.Response(ujson.Obj("success" -> true), headers = Seq("Content-Type" -> "application/json"))
      }
    }
  }

  @cask.post("/viewer/play")
  def incrementPlayCount(request: cask.Request) = {
    try {
      val json = ujson.read(request.text())
      val id = json("id").str
      Await.result(PuzzleRepository.incrementPlayCount(id), 10.seconds)
      cask.Response(ujson.Obj("success" -> true), headers = Seq("Content-Type" -> "application/json"))
    } catch {
      case e: Exception =>
        cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500, headers = Seq("Content-Type" -> "application/json"))
    }
  }

  @cask.post("/viewer/toggle-public")
  def togglePublic(request: cask.Request) = {
    withAuthJson(request, "viewer") { _ =>
      val json = ujson.read(request.text())
      val id = json("id").str
      val isPublic = json("isPublic").bool

      Await.result(PuzzleRepository.togglePuzzlePublic(id, isPublic), 10.seconds)
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

      val depth = json.obj.get("depth").map(_.num.toInt).getOrElse(10)
      val multiPv = json.obj.get("multiPv").map(_.num.toInt).getOrElse(1)

      try {
        logger.info(s"[VIEWER] Creating fresh engine for analysis")
        val engineManager = new EngineManager(Seq(settings.enginePath))
        engineManager.initialize()

        val sfenParts = sfen.split(" ")
        if (sfenParts.length < 3) {
          throw new IllegalArgumentException("Invalid SFEN format: " + sfen)
        }

        val playerColorEnum = if (playerColor == "sente") Color.Sente else Color.Gote
        val movesArray = AnalysisService.analyzePosition(engineManager, sfen, depth, multiPv, playerColor = playerColorEnum)

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

  initialize()
}
