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
    withAuthJson(request, "viewer") { _ =>
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
        ujson.Arr(jsonArray: _*),
        headers = Seq("Content-Type" -> "application/json")
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

  initialize()
}
