package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{SRSRepository, CustomPuzzleRepository, SettingsRepository, AppSettings}
import shogi.puzzler.ui.Components
import scala.concurrent.Await
import scala.concurrent.duration._

object TrainingRoutes extends BaseRoutes {

  @cask.get("/training")
  def training(request: cask.Request) = {
    withAuth(request, "training") { email =>
      val settings = Await.result(SettingsRepository.getAppSettings(Some(email)), 10.seconds)
      cask.Response(
        renderTrainingPage(Some(email), settings).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  @cask.get("/training/next")
  def nextCard(request: cask.Request) = {
    withAuthJson(request, "training") { email =>
      val cardOpt = Await.result(SRSRepository.getNextDueCard(email), 10.seconds)
      cardOpt match {
        case Some(card) =>
          val puzzleOid = card.get("puzzle_object_id").map(_.asObjectId().getValue.toHexString).getOrElse("")
          val source = card.getString("puzzle_source")
          val puzzleOpt = Await.result(SRSRepository.getPuzzleData(puzzleOid, source), 10.seconds)
          puzzleOpt match {
            case Some(puzzleDoc) =>
              val puzzleJson = ujson.read(puzzleDoc.toJson())
              // For custom puzzles, convert to viewer format
              val viewerJson = if (source == "custom_puzzles") {
                val converted = CustomPuzzleRepository.convertCustomPuzzleToViewerFormat(puzzleDoc)
                ujson.read(converted.toJson())
              } else puzzleJson

              val cardJson = ujson.Obj(
                "card_id" -> card.getObjectId("_id").toHexString,
                "puzzle_object_id" -> puzzleOid,
                "puzzle_source" -> source,
                "ease_factor" -> ujson.Num(card.getDouble("ease_factor")),
                "interval" -> ujson.Num(card.getInteger("interval").toDouble),
                "repetitions" -> ujson.Num(card.getInteger("repetitions").toDouble),
                "total_attempts" -> ujson.Num(card.getInteger("total_attempts").toDouble),
                "correct_attempts" -> ujson.Num(card.getInteger("correct_attempts").toDouble)
              )

              cask.Response(
                ujson.Obj("card" -> cardJson, "puzzle" -> viewerJson),
                headers = Seq("Content-Type" -> "application/json")
              )
            case None =>
              cask.Response(
                ujson.Obj("error" -> "Puzzle not found"),
                statusCode = 404,
                headers = Seq("Content-Type" -> "application/json")
              )
          }
        case None =>
          cask.Response(
            ujson.Obj("empty" -> true),
            headers = Seq("Content-Type" -> "application/json")
          )
      }
    }
  }

  @cask.post("/training/result")
  def recordResult(request: cask.Request) = {
    withAuthJson(request, "training") { email =>
      val json = ujson.read(request.text())
      val cardId = json("card_id").str
      val result = json("result").str
      val quality = json("quality").num.toInt
      val timeMs = json("time_spent_ms").num.toLong

      Await.result(SRSRepository.recordReview(email, cardId, result, quality, timeMs), 10.seconds)
      cask.Response(ujson.Obj("success" -> true), headers = Seq("Content-Type" -> "application/json"))
    }
  }

  @cask.get("/training/stats")
  def stats(request: cask.Request) = {
    withAuthJson(request, "training") { email =>
      val stats = Await.result(SRSRepository.getStats(email), 10.seconds)
      val nextReviewTime = Await.result(SRSRepository.getNextReviewTime(email), 10.seconds)
      val json = ujson.Obj(
        "total" -> ujson.Num(stats("total").asInstanceOf[Long].toDouble),
        "due" -> ujson.Num(stats("due").asInstanceOf[Long].toDouble),
        "learned" -> ujson.Num(stats("learned").asInstanceOf[Int].toDouble),
        "success_rate" -> ujson.Num(stats("success_rate").asInstanceOf[Long].toDouble),
        "streak" -> ujson.Num(stats("streak").asInstanceOf[Int].toDouble),
        "total_attempts" -> ujson.Num(stats("total_attempts").asInstanceOf[Long].toDouble),
        "correct_attempts" -> ujson.Num(stats("correct_attempts").asInstanceOf[Long].toDouble),
        "next_review_time" -> nextReviewTime.map(ujson.Num(_)).getOrElse(ujson.Null)
      )
      cask.Response(json, headers = Seq("Content-Type" -> "application/json"))
    }
  }

  @cask.post("/training/add")
  def addToDeck(request: cask.Request) = {
    withAuthJson(request, "training") { email =>
      val json = ujson.read(request.text())
      val puzzleOid = json("puzzle_object_id").str
      val source = json.obj.get("puzzle_source").map(_.str).getOrElse("puzzles")

      try {
        Await.result(SRSRepository.addCard(email, puzzleOid, source), 10.seconds)
        cask.Response(ujson.Obj("success" -> true), headers = Seq("Content-Type" -> "application/json"))
      } catch {
        case _: com.mongodb.MongoWriteException =>
          cask.Response(ujson.Obj("error" -> "Already in deck"), statusCode = 409, headers = Seq("Content-Type" -> "application/json"))
      }
    }
  }

  @cask.post("/training/remove")
  def removeFromDeck(request: cask.Request) = {
    withAuthJson(request, "training") { email =>
      val json = ujson.read(request.text())
      val puzzleOid = json("puzzle_object_id").str

      Await.result(SRSRepository.removeCard(email, puzzleOid), 10.seconds)
      cask.Response(ujson.Obj("success" -> true), headers = Seq("Content-Type" -> "application/json"))
    }
  }

  @cask.get("/training/check")
  def checkInDeck(puzzle_object_id: String, request: cask.Request) = {
    withAuthJson(request, "training") { email =>
      val inDeck = Await.result(SRSRepository.isInDeck(email, puzzle_object_id), 10.seconds)
      cask.Response(ujson.Obj("in_deck" -> inDeck), headers = Seq("Content-Type" -> "application/json"))
    }
  }

  def renderTrainingPage(userEmail: Option[String], settings: AppSettings) = {
    html(lang := "en", cls := "dark", style := "--zoom:90;")(
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", content := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(name := "theme-color", content := "#2e2a24"),
        tag("title")("SRS Training"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"),
        link(rel := "stylesheet", href := "/assets/css/shogiground.css"),
        link(rel := "stylesheet", href := "/assets/css/portella.css"),
        link(rel := "stylesheet", href := "/assets/css/site.css"),
        link(rel := "stylesheet", href := "/assets/css/puzzle.css"),
        link(rel := "stylesheet", href := "/assets/css/common.css"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.10.3/font/bootstrap-icons.css"),
        script(src := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js"),
        tag("style")(
          """|/* Training page: stats bar compact on mobile */
             |.training-stats .card-body { padding: 0.35rem !important; }
             |.training-stats .fs-4 { font-size: 1.1rem !important; }
             |.training-stats .small { font-size: 0.65rem !important; }
             |@media (min-width: 576px) {
             |  .training-stats .fs-4 { font-size: 1.5rem !important; }
             |  .training-stats .small { font-size: 0.8rem !important; }
             |  .training-stats .card-body { padding: 0.5rem !important; }
             |}
             |/* Empty state: fill the puzzle grid area so it doesn't overlap */
             |#empty-state {
             |  grid-column: 1 / -1;
             |  grid-row: 1 / -1;
             |  align-self: center;
             |  justify-self: center;
             |  z-index: 5;
             |}
             |""".stripMargin
        )
      ),
      body(cls := "wood coords-out playing online")(
        Components.renderHeader(userEmail, settings, appVersion),
        div(id := "main-wrap")(
          // Stats bar
          div(cls := "container-fluid mb-2 training-stats", style := "max-width: 900px;")(
            div(cls := "row g-1 g-sm-2 text-center")(
              div(cls := "col-3")(
                div(cls := "card bg-dark border-secondary")(
                  div(cls := "card-body")(
                    div(id := "stat-due", cls := "fs-4 fw-bold text-warning")("0"),
                    div(cls := "small", style := "color: #ccc;")("Due Today")
                  )
                )
              ),
              div(cls := "col-3")(
                div(cls := "card bg-dark border-secondary")(
                  div(cls := "card-body")(
                    div(id := "stat-total", cls := "fs-4 fw-bold text-info")("0"),
                    div(cls := "small", style := "color: #ccc;")("Total Cards")
                  )
                )
              ),
              div(cls := "col-3")(
                div(cls := "card bg-dark border-secondary")(
                  div(cls := "card-body")(
                    div(id := "stat-success", cls := "fs-4 fw-bold text-success")("0%"),
                    div(cls := "small", style := "color: #ccc;")("Success Rate")
                  )
                )
              ),
              div(cls := "col-3")(
                div(cls := "card bg-dark border-secondary")(
                  div(cls := "card-body")(
                    div(id := "stat-streak", cls := "fs-4 fw-bold text-primary")("0"),
                    div(cls := "small", style := "color: #ccc;")("Streak")
                  )
                )
              )
            )
          ),
          // Board + controls  (the puzzle grid)
          tag("main")(cls := "puzzle puzzle-play")(
            // Empty state lives *inside* the grid so it occupies the same space
            div(id := "empty-state", cls := "text-center py-4", style := "display:none; max-width:500px; color: #ddd;")(
              i(cls := "bi bi-check-circle-fill text-success", style := "font-size: 2.5rem;"),
              h4(cls := "mt-3", style := "color: #fff;")("All done!"),
              p(style := "color: #ccc;")("No puzzles are due for review right now. Add puzzles from the ", a(href := "/viewer", style := "color: #6ea8fe;")("Puzzle Viewer"), " using the deck button.")
            ),
            div(cls := "puzzle__board main-board")(
              div(cls := "sg-wrap d-9x9")(
                tag("sg-hand-wrap")(id := "hand-top"),
                div(id := "dirty"),
                tag("sg-hand-wrap")(id := "hand-bottom")
              )
            ),
            div(cls := "puzzle__controls")(
              div(cls := "container-fluid p-0")(
                div(cls := "row g-2 align-items-center")(
                  div(cls := "col-12")(
                    div(id := "session-progress", cls := "small mb-2", style := "color: #ddd;")("Session: 0 solved, 0 correct"),
                    button(id := "next-training-btn", cls := "btn btn-sm btn-success w-100", style := "display:none")(
                      i(cls := "bi bi-arrow-right me-1"), "Next Puzzle"
                    )
                  )
                )
              )
            ),
            div(cls := "puzzle__side")(
              div(cls := "puzzle__side__box")(
                div(cls := "puzzle__feedback")(
                  div(id := "training-message", cls := "content", style := "color: #eee;")("Loading..."),
                  div(cls := "d-flex justify-content-between align-items-center mb-1")(
                    div(id := "turn-text", cls := "badge bg-secondary")("-")
                  ),
                  div(id := "players-text", cls := "mb-3", style := "font-size: 0.8rem; color: #ccc;")("-"),
                  div(id := "card-info", cls := "mb-2", style := "font-size: 0.8rem; color: #ccc; display:none;")(
                    tag("span")(cls := "badge bg-dark border border-secondary me-1")("EF: ", tag("span")(id := "card-ef")("2.5")),
                    tag("span")(cls := "badge bg-dark border border-secondary me-1")("Int: ", tag("span")(id := "card-interval")("1"), "d"),
                    tag("span")(cls := "badge bg-dark border border-secondary")("Rep: ", tag("span")(id := "card-reps")("0"))
                  )
                )
              )
            )
          )
        ),
        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        script(src := "https://cdn.jsdelivr.net/npm/sweetalert2@11"),
        script(src := "https://cdnjs.cloudflare.com/ajax/libs/jquery/3.6.3/jquery.min.js", attr("integrity") := "sha512-STof4xm1wgkfm7heWqFJVn58Hm3EtS31XFaagaa8VMReCXAkQnJZ+jEy8PCC/iT18dFy95WcExNHFTqLyp72eQ==", attr("crossorigin") := "anonymous", attr("referrerpolicy") := "no-referrer"),
        script(src := "/js/training.js", `type` := "module")
      )
    )
  }

  initialize()
}
