package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{GameRepository, SettingsRepository, AppSettings}
import shogi.puzzler.ui.Components
import scala.concurrent.Await
import scala.concurrent.duration._

object ViewerRoutes extends BaseRoutes {

  @cask.get("/viewer")
  def viewer(hash: Option[String] = None, request: cask.Request) = {
    redirectToConfiguredHostIfNeeded(request).getOrElse {
      val userEmail = getSessionUserEmail(request)
      if (oauthEnabled && userEmail.isEmpty) {
        logger.info(s"[VIEWER] Redirecting to /login because userEmail is empty")
        noCacheRedirect("/login")
      } else {
        val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
        cask.Response(
          renderViewer(userEmail, settings).render,
          headers = Seq("Content-Type" -> "text/html; charset=utf-8")
        )
      }
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
        link(href := "https://cdn.jsdelivr.net/npm/select2@4.1.0-rc.0/dist/css/select2.min.css", rel := "stylesheet"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.10.3/font/bootstrap-icons.css"),
        script(src := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js")
      ),
      body(cls := "wood coords-out playing online")(
        Components.renderHeader(userEmail, settings),
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
                        i(cls := "bi bi-chevron-left")
                      ),
                      select(cls := "games form-control", style := "width: auto; flex-grow: 1; margin: 0;")(
                        option(value := "")("Select a puzzle...")
                      ),
                      button(cls := "btn btn-outline-secondary next-puzzle", title := "Next Puzzle") (
                        i(cls := "bi bi-chevron-right")
                      )
                    )
                  )
                ),
                div(cls := "row g-2 align-items-center")(
                  div(cls := "col-4")(
                    button(cls := "btn btn-sm btn-secondary random w-100", title := "Random Puzzle") (
                      i(cls := "bi bi-shuffle me-1"), "Random"
                    )
                  ),
                  div(cls := "col-4")(
                    button(cls := "btn btn-sm btn-info lishogi-game w-100", title := "View on Lishogi") (
                      i(cls := "bi bi-box-arrow-up-right me-1"), "Game"
                    )
                  ),
                  div(cls := "col-4")(
                    button(cls := "btn btn-sm btn-outline-info lishogi-position w-100", title := "Analyze on Lishogi") (
                      i(cls := "bi bi-search me-1"), "Pos"
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
  def puzzles(hash: Option[String] = None) = {
    val puzzles = hash match {
      case Some(h) => Await.result(GameRepository.getPuzzlesForGame(h), 10.seconds)
      case None => Await.result(GameRepository.getAllPuzzles(), 10.seconds)
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
    cask.Response(
      ujson.write(jsonArray),
      headers = Seq("Content-Type" -> "application/json")
    )
  }

  initialize()
}
