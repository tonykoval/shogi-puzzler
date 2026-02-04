package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{GameRepository, SettingsRepository, AppSettings}
import shogi.puzzler.ui.Components
import shogi.puzzler.engine.{EngineManager, Limit}
import scala.concurrent.Await
import scala.concurrent.duration._
import ujson.Value

object PuzzleCreatorRoutes extends BaseRoutes {

  @cask.get("/puzzle-creator")
  def creatorPage(request: cask.Request) = {
    withAuth(request, "puzzle-creator") { email =>
      logger.info(s"[CREATOR] Serving creator page for $email")
      val settings = Await.result(SettingsRepository.getAppSettings(Some(email)), 10.seconds)
      cask.Response(
        renderCreator(Some(email), settings).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  @cask.get("/puzzle-creator-v1")
  def creatorPageV1(request: cask.Request) = {
    withAuth(request, "puzzle-creator") { email =>
      logger.info(s"[CREATOR] Serving creator page V1 for $email")
      val settings = Await.result(SettingsRepository.getAppSettings(Some(email)), 10.seconds)
      cask.Response(
        renderCreatorPage(Some(email), settings).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  @cask.get("/puzzle-creator-v2")
  def creatorPageV2(request: cask.Request) = {
    withAuth(request, "puzzle-creator") { email =>
      logger.info(s"[CREATOR] Serving creator page V2 for $email")
      val settings = Await.result(SettingsRepository.getAppSettings(Some(email)), 10.seconds)
      cask.Response(
        renderCreatorV2(Some(email), settings).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  @cask.get("/puzzle-creator-v3")
  def creatorPageV3(request: cask.Request) = {
    creatorPageV2(request)
  }

  def renderCreator(userEmail: Option[String], settings: AppSettings) = {
    html(lang := "en", cls := "dark", style := "--zoom:90;")(
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", content := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(name := "theme-color", content := "#2e2a24"),
        tag("title")("Puzzle Creator - Shogi Puzzler"),
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
        link(rel := "stylesheet", href := "/assets/css/d9x9.css"),
        link(rel := "stylesheet", href := "/assets/css/hands.css"),
        script(src := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js")
      ),
      body(cls := "wood coords-out playing online")(
        Components.renderHeader(userEmail, settings, appVersion),
        div(id := "main-wrap")(
          tag("main")(cls := "puzzle puzzle-play puzzle-creator-mode")(
            div(cls := "puzzle__board main-board")(
              div(cls := "sg-wrap d-9x9", style := "display: flex; aspect-ratio: 9/11;")(
                tag("sg-hand-wrap")(id := "hand-top", style := "width: 12%; height: 100%;"),
                div(id := "dirty", style := "flex: 1;"),
                tag("sg-hand-wrap")(id := "hand-bottom", style := "width: 12%; height: 100%;")
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
                    ),
                    div(cls := "mt-3")(
                      label(cls := "form-label text-light", `for` := "sfen-input")("SFEN Position"),
                      div(cls := "input-group input-group-sm")(
                        input(`type` := "text", id := "sfen-input", cls := "form-control bg-dark text-light border-secondary", placeholder := "Position SFEN..."),
                        button(cls := "btn btn-outline-primary", id := "load-sfen")("Load")
                      ),
                      div(id := "analysis-info", cls := "mt-2 p-2 border border-secondary rounded d-none", style := "background: #222; font-size: 0.8rem;")()
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
                  ),
                  div(cls := "col-12 mt-2")(
                    button(cls := "btn btn-sm btn-info w-100", id := "run-engine") (
                      i(cls := "bi bi-cpu me-1"), "Run Engine Analysis"
                    ),
                    select(id := "engine-select", cls := "form-select form-select-sm bg-dark text-light border-secondary mt-2", style := "display:none")(
                      option(value := "engine/YaneuraOu_NNUE-tournament-clang++-avx2.exe", attr("selected") := "selected")("YaneuraOu (AVX2)")
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
                  div(cls := "mb-2")(
                    input(`type` := "text", id := "puzzle-title", cls := "form-control form-control-sm bg-dark text-light border-secondary", placeholder := "Puzzle Title")
                  ),
                  div(cls := "mb-2")(
                    textarea(id := "puzzle-question", cls := "form-control form-control-sm bg-dark text-light border-secondary", placeholder := "Puzzle Question")
                  ),
                  div(cls := "mb-2")(
                    textarea(id := "puzzle-solution", cls := "form-control form-control-sm bg-dark text-light border-secondary", placeholder := "Puzzle Solution (USI)")
                  ),
                  div(cls := "form-check form-switch mb-2")(
                    input(cls := "form-check-input", `type` := "checkbox", id := "puzzle-public"),
                    label(cls := "form-check-label text-light", `for` := "puzzle-public")("Public")
                  ),
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
                  textarea(cls := "content mt-2 form-control", id := "puzzle-hint", style := "display:none")(),
                  button(id := "save-puzzle", cls := "btn btn-primary mt-2 w-100")("Save Puzzle"),
                  button(id := "delete-puzzle", cls := "btn btn-danger mt-2 w-100", style := "display:none")("Delete"),
                  button(id := "new-puzzle", cls := "btn btn-secondary mt-2 w-100", style := "display:none")("New"),
                  hr(cls := "border-secondary"),
                  h6(cls := "text-light mb-2")("My Puzzles"),
                  button(id := "refresh-my-puzzles", cls := "btn btn-sm btn-outline-secondary mb-2 w-100")("Refresh"),
                  ul(id := "my-puzzles-list", cls := "list-group list-group-flush bg-dark border-secondary overflow-auto", style := "max-height: 300px;")()
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
        script(src := "/js/puzzle-creator.js", `type` := "module")
      )
    )
  }

  def renderCreatorV2(userEmail: Option[String], settings: AppSettings) = {
    html(lang := "en", cls := "dark", style := "--zoom:90;")(
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", content := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(name := "theme-color", content := "#2e2a24"),
        tag("title")("Puzzle Creator - Shogi Puzzler"),
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
        link(rel := "stylesheet", href := "/assets/css/d9x9.css"),
        link(rel := "stylesheet", href := "/assets/css/hands.css"),
        script(src := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js")
      ),
      body(cls := "wood coords-out playing online mt-0")(
        Components.renderHeader(userEmail, settings, appVersion),
        div(id := "main-wrap", cls := "full-screen-force")(
          tag("main")(cls := "puzzle puzzle-play puzzle-creator-mode")(
            div(cls := "puzzle__side creator-controls")(
              div(cls := "creator-side-scroll")(
                div(cls := "row g-3")(
                  div(cls := "col-xl-8")(
                    div(cls := "card h-100 bg-dark text-light border-secondary")(
                      div(cls := "card-header border-secondary")(h5(cls := "mb-0")("Board")),
                      div(cls := "card-body board-container", style := "min-height: 400px;")(
                         div(cls := "sg-wrap d-9x9", style := "display: flex; aspect-ratio: 9/11;")(
                           tag("sg-hand-wrap")(id := "hand-top", style := "width: 12%; height: 100%;"),
                           div(id := "dirty", style := "flex: 1;"),
                           tag("sg-hand-wrap")(id := "hand-bottom", style := "width: 12%; height: 100%;")
                         )
                       )
                    )
                  ),
                  div(cls := "col-xl-4")(
                    div(cls := "card h-100 bg-dark text-light border-secondary")(
                      div(cls := "card-header border-secondary")(h5(cls := "mb-0")("Puzzle Details")),
                      div(cls := "card-body")(
                        div(cls := "mb-3")(
                          label(cls := "form-label")("SFEN Position"),
                          div(cls := "input-group input-group-sm")(
                            input(`type` := "text", id := "sfen-input", cls := "form-control bg-dark text-light border-secondary", placeholder := "Position SFEN..."),
                            button(cls := "btn btn-outline-primary", id := "load-sfen")("Load")
                          )
                        ),
                        div(cls := "mb-3")(
                          label(cls := "form-label")("Engine"),
                          select(id := "engine-select", cls := "form-select form-select-sm bg-dark text-light border-secondary")(
                            option(value := "yaneuraou")("YaneuraOu (System)"),
                            option(value := "engine/YaneuraOu_NNUE-tournament-clang++-avx2.exe", attr("selected") := "selected")("YaneuraOu (AVX2)")
                          )
                        ),
                        div(cls := "mb-3")(
                          button(cls := "btn btn-sm btn-info w-100", id := "run-engine") (
                            i(cls := "bi bi-cpu me-1"), "Run Engine Analysis"
                          ),
                          div(id := "analysis-info", cls := "mt-2 p-2 border border-secondary rounded d-none", style := "background: #222; font-size: 0.8rem;")()
                        ),
                        hr(cls := "border-secondary"),
                        div(cls := "mb-3")(
                          label(cls := "form-label")("Title"),
                          input(`type` := "text", id := "puzzle-title", cls := "form-control form-control-sm bg-dark text-light border-secondary", placeholder := "Enter title...")
                        ),
                        div(cls := "row g-2 mb-3")(
                          div(cls := "col-md-6")(
                            label(cls := "form-label")("Question"),
                            input(`type` := "text", id := "puzzle-question", cls := "form-control form-control-sm bg-dark text-light border-secondary")
                          ),
                          div(cls := "col-md-6")(
                            label(cls := "form-label")("Hint"),
                            input(`type` := "text", id := "puzzle-hint", cls := "form-control form-control-sm bg-dark text-light border-secondary")
                          )
                        ),
                        div(cls := "mb-3")(
                          label(cls := "form-label")("Solution (USI)"),
                          input(`type` := "text", id := "puzzle-solution", cls := "form-control form-control-sm bg-dark text-light border-secondary")
                        ),
                        div(cls := "row align-items-center mb-3")(
                          div(cls := "col-6")(
                            div(cls := "form-check")(
                              input(`type` := "checkbox", id := "puzzle-public", cls := "form-check-input"),
                              label(cls := "form-check-label", `for` := "puzzle-public")("Make Public")
                            )
                          ),
                          div(cls := "col-6 text-end")(
                            button(cls := "btn btn-sm btn-outline-danger w-100", id := "delete-puzzle", style := "display: none;") (
                              i(cls := "bi bi-trash me-1"), "Delete"
                            ),
                            button(cls := "btn btn-sm btn-outline-secondary w-100", id := "new-puzzle", style := "display: none;") (
                              i(cls := "bi bi-plus-circle me-1"), "New"
                            )
                          )
                        ),
                        button(cls := "btn btn-success w-100", id := "save-puzzle") (
                          i(cls := "bi bi-save me-1"), "Save Puzzle"
                        )
                      )
                    )
                  )
                ),
                div(cls := "card bg-dark text-light border-secondary mt-3")(
                  div(cls := "card-header border-secondary d-flex justify-content-between align-items-center")(
                    h5(cls := "mb-0")("My Puzzles"),
                    button(cls := "btn btn-sm btn-outline-secondary", id := "refresh-my-puzzles")(i(cls := "bi bi-arrow-clockwise"))
                  ),
                  div(cls := "card-body p-0")(
                    ul(id := "my-puzzles-list", cls := "list-group list-group-flush bg-dark")
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
        script(src := "https://cdn.jsdelivr.net/npm/select2@4.1.0-rc.0/dist/js/select2.min.js"),
        script(src := "/js/puzzle-creator-v2.js", `type` := "module")
      )
    )
  }

  def renderCreatorPage(userEmail: Option[String], settings: AppSettings) = {
    logger.info(s"[CREATOR] Rendering layout for $userEmail")
    html(lang := "en", cls := "dark", style := "--zoom:90;")(
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", content := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(name := "theme-color", content := "#2e2a24"),
        tag("title")("Puzzle Creator - Shogi Puzzler"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"),
        link(rel := "stylesheet", href := "/assets/css/shogiground.css"),
        link(rel := "stylesheet", href := "/assets/css/portella.css"),
        link(rel := "stylesheet", href := "/assets/css/site.css"),
        link(rel := "stylesheet", href := "/assets/css/puzzle.css"),
        link(rel := "stylesheet", href := "/assets/css/common.css"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.10.3/font/bootstrap-icons.css"),
        tag("style")("""
          /* Creator specific overrides to fit the viewer grid */
          .puzzle.puzzle-creator-mode {
            grid-template-areas: "board gauge side" "session . side" "kb-move . side" !important;
            grid-template-columns: var(--col2-uniboard-main-size) 2vmin 1fr !important;
          }
          
          @media (max-width: 979.29px) {
            .puzzle.puzzle-creator-mode {
              grid-template-areas: "board" "side" !important;
              grid-template-columns: 1fr !important;
              display: flex;
              flex-direction: column;
            }
            .puzzle__board.main-board {
              height: 70vh; /* Give board some height on mobile */
              min-height: 400px;
            }
          }

          .puzzle__side.creator-controls {
            grid-area: side;
            justify-self: stretch;
            align-self: start;
          }

          .creator-side-scroll {
            max-height: calc(100vh - 150px);
            overflow-y: auto;
            padding-right: 5px;
          }
          
          #main-wrap {
             margin-top: 0;
          }

          /* Match Viewer hand heights if needed */
          .puzzle-creator-mode .sg-hand-wrap {
             /* Hand height is usually handled by Shogiground but we can ensure it's not collapsed */
             min-height: 50px;
          }
        """),
        script(src := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js"),
        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        script(src := "https://cdn.jsdelivr.net/npm/sweetalert2@11"),
        script(src := "https://cdnjs.cloudflare.com/ajax/libs/jquery/3.6.3/jquery.min.js", attr("integrity") := "sha512-STof4xm1wgkfm7heWqFJVn58Hm3EtS31XFaagaa8VMReCXAkQnJZ+jEy8PCC/iT18dFy95WcExNHFTqLyp72eQ==", attr("crossorigin") := "anonymous", attr("referrerpolicy") := "no-referrer"),
        script(src := "/js/puzzle-creator.js", `type` := "module")
      ),
      body(cls := "wood coords-out playing online mt-0")(
        Components.renderHeader(userEmail, settings, appVersion),
        div(id := "main-wrap")(
          tag("main")(cls := "puzzle puzzle-play puzzle-creator-mode")(
            div(cls := "puzzle__board main-board")(
              div(cls := "sg-wrap d-9x9")(
                tag("sg-hand-wrap")(id := "hand-top"),
                div(id := "board"),
                tag("sg-hand-wrap")(id := "hand-bottom")
              )
            ),
            div(cls := "puzzle__side creator-controls")(
              div(cls := "creator-side-scroll")(
                div(cls := "card bg-dark text-light border-secondary")(
                  div(cls := "card-header border-secondary")(h5(cls := "mb-0")("Puzzle Details")),
                  div(cls := "card-body")(
                    div(cls := "mb-3")(
                      label(cls := "form-label")("SFEN Position"),
                      div(cls := "input-group input-group-sm")(
                        input(`type` := "text", id := "sfen-input", cls := "form-control bg-dark text-light border-secondary", placeholder := "Position SFEN..."),
                        button(cls := "btn btn-outline-primary", id := "load-sfen")("Load")
                      )
                    ),
                    div(cls := "mb-3")(
                      label(cls := "form-label")("Engine"),
                      select(id := "engine-select", cls := "form-select form-select-sm bg-dark text-light border-secondary")(
                        option(value := "yaneuraou")("YaneuraOu (System)"),
                        option(value := "engine/YaneuraOu_NNUE-tournament-clang++-avx2.exe", attr("selected") := "selected")("YaneuraOu (AVX2)")
                      )
                    ),
                    div(cls := "mb-3")(
                      button(cls := "btn btn-sm btn-info w-100", id := "run-engine") (
                        i(cls := "bi bi-cpu me-1"), "Run Engine Analysis"
                      ),
                      div(id := "analysis-info", cls := "mt-2 p-2 border border-secondary rounded d-none", style := "background: #222; font-size: 0.8rem;")()
                    ),
                    hr(cls := "border-secondary"),
                    div(cls := "mb-3")(
                      label(cls := "form-label")("Title"),
                      input(`type` := "text", id := "puzzle-title", cls := "form-control form-control-sm bg-dark text-light border-secondary", placeholder := "Enter title...")
                    ),
                    div(cls := "mb-3")(
                      label(cls := "form-label")("Question"),
                      input(`type` := "text", id := "puzzle-question", cls := "form-control form-control-sm bg-dark text-light border-secondary")
                    ),
                    div(cls := "mb-3")(
                      label(cls := "form-label")("Hint"),
                      input(`type` := "text", id := "puzzle-hint", cls := "form-control form-control-sm bg-dark text-light border-secondary")
                    ),
                    div(cls := "mb-3")(
                      label(cls := "form-label")("Solution (USI)"),
                      input(`type` := "text", id := "puzzle-solution", cls := "form-control form-control-sm bg-dark text-light border-secondary")
                    ),
                    div(cls := "form-check mb-3")(
                      input(`type` := "checkbox", id := "puzzle-public", cls := "form-check-input"),
                      label(cls := "form-check-label", `for` := "puzzle-public")("Make Public")
                    ),
                    button(cls := "btn btn-success w-100", id := "save-puzzle") (
                      i(cls := "bi bi-save me-1"), "Save Puzzle"
                    ),
                    button(cls := "btn btn-outline-danger w-100 mt-2", id := "delete-puzzle", style := "display: none;") (
                      i(cls := "bi bi-trash me-1"), "Delete Puzzle"
                    ),
                    button(cls := "btn btn-outline-secondary w-100 mt-2", id := "new-puzzle", style := "display: none;") (
                      i(cls := "bi bi-plus-circle me-1"), "Create New"
                    )
                  )
                ),
                div(cls := "card bg-dark text-light border-secondary mt-3")(
                  div(cls := "card-header border-secondary d-flex justify-content-between align-items-center")(
                    h5(cls := "mb-0")("My Puzzles"),
                    button(cls := "btn btn-sm btn-outline-secondary", id := "refresh-my-puzzles")(i(cls := "bi bi-arrow-clockwise"))
                  ),
                  div(cls := "card-body p-0")(
                    ul(id := "my-puzzles-list", cls := "list-group list-group-flush bg-dark")
                  )
                )
              )
            )
          )
        )
      )
    )
  }

  @cask.post("/engine/analyze-position")
  def analyzePosition(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val data = ujson.read(request.readAllBytes())
      val sfen = data("sfen").str
      val engineName = if (data.obj.contains("engine")) data("engine").str else "yaneuraou"
      
      logger.info(s"[CREATOR] Analyzing position for $email using $engineName: $sfen")
      val engineManager = MaintenanceRoutes.getEngineManager(engineName)
      val results = engineManager.analyze(sfen, Limit(depth = Some(15)), multiPvCount = 3)
      logger.info(s"[CREATOR] Analysis results: ${results.size} lines found")
      
      val sfenParts = sfen.trim.split("\\s+")
      val turn = if (sfenParts.length > 1) sfenParts(1) else "b"
      val colorToMove = if (turn == "b") shogi.Sente else shogi.Gote

      cask.Response(ujson.Arr(results.map { res =>
        val pv = res.getOrElse("pv", Seq.empty).asInstanceOf[Seq[String]]
        val scoreRaw = res.getOrElse("score", ("cp", 0)).asInstanceOf[(String, Int)]
        
        val povScore = shogi.puzzler.domain.Score.fromEngine(Some(scoreRaw), colorToMove)
        val scoreForPlayer = povScore.forPlayer(colorToMove)
        
        val finalScore = scoreForPlayer match {
          case shogi.puzzler.domain.CpScore(cp) => ujson.Obj("cp" -> ujson.Num(cp))
          case shogi.puzzler.domain.MateScore(mate) => ujson.Obj("mate" -> ujson.Num(mate))
        }

        ujson.Obj(
          "pv" -> ujson.Arr(pv.map(ujson.Str): _*),
          "score" -> finalScore
        )
      }: _*))
    }
  }

  @cask.post("/puzzle/save-custom")
  def saveCustomPuzzle(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val data = ujson.read(request.readAllBytes())
      val puzzleIdToUpdate = if (data.obj.contains("id") && data("id").str.nonEmpty) Some(data("id").str) else None
      
      logger.info(s"[CREATOR] Saving custom puzzle from $email: ${data("title").str} (Update: ${puzzleIdToUpdate.isDefined})")
      
      val puzzleId = puzzleIdToUpdate.getOrElse(java.util.UUID.randomUUID().toString)
      val sfen = data("sfen").str
      val title = data("title").str
      val isPublic = data("isPublic").bool
      val solution = data("solution").str
      val question = data("question").str
      val hint = data("hint").str
      
      val bestScore = if (data.obj.contains("bestScore")) data("bestScore") else ujson.Obj("cp" -> 0)
      val secondScore = if (data.obj.contains("secondScore") && !data("secondScore").isNull) Some(data("secondScore")) else None
      val secondUsi = if (data.obj.contains("secondUsi") && !data("secondUsi").isNull) Some(data("secondUsi").str) else None
      val thirdScore = if (data.obj.contains("thirdScore") && !data("thirdScore").isNull) Some(data("thirdScore")) else None
      val thirdUsi = if (data.obj.contains("thirdUsi") && !data("thirdUsi").isNull) Some(data("thirdUsi").str) else None

      val sfenParts = sfen.trim.split("\\s+")
      val turn = if (sfenParts.length > 1) sfenParts(1) else "b"
      val hands = if (sfenParts.length > 2) sfenParts(2) else ""
      
      val normalizedHands = if (hands == "-") "" else hands

      // Basic structure that the viewer expects
      val puzzleJson = ujson.Obj(
        "id" -> puzzleId,
        "move_number" -> 1,
        "sfen" -> sfenParts(0), // Save only the board part to 'sfen' field if 'hands' and 'player' are separate
        "hands" -> normalizedHands,
        "your_move_usi" -> "unknown",
        "opponent_last_move_usi" -> "unknown",
        "player" -> (if (turn == "b") "sente" else "gote"),
        "title" -> title,
        "comment" -> question, // Using comment field for question
        "hint_text" -> hint,
        "best" -> ujson.Obj("usi" -> solution, "score" -> bestScore),
        "is_public" -> isPublic,
        "created_by" -> email,
        "timestamp" -> System.currentTimeMillis()
      )

      secondUsi.foreach { usi =>
        puzzleJson("second") = ujson.Obj("usi" -> usi, "score" -> secondScore.getOrElse(ujson.Obj("cp" -> 0)))
      }
      thirdUsi.foreach { usi =>
        puzzleJson("third") = ujson.Obj("usi" -> usi, "score" -> thirdScore.getOrElse(ujson.Obj("cp" -> 0)))
      }

      if (puzzleIdToUpdate.isDefined) {
        // We need to use the MongoDB _id if we want to replace correctly, 
        // but our current GameRepository.updatePuzzle uses the _id (ObjectId).
        // Custom puzzles saved via save-custom might not have a clean way to get the ObjectId back easily 
        // without fetching first, or we can use a unique 'id' field.
        // Let's check how savePuzzle works. It parses the JSON and adds game_kif_hash.
        // It's better to find by our 'id' field if we don't have the ObjectId.
        
        // Let's fetch the puzzle first to get its _id or just add a method to update by 'id' field.
        val existing = Await.result(GameRepository.getPuzzlesCreatedBy(email), 10.seconds)
          .find(_.get("id").exists(_.asString().getValue == puzzleId))
        
        existing match {
          case Some(doc) =>
            val mongoId = doc.getObjectId("_id").toHexString
            Await.result(GameRepository.updatePuzzle(mongoId, ujson.write(puzzleJson)), 10.seconds)
          case None =>
            Await.result(GameRepository.savePuzzle(ujson.write(puzzleJson), "custom"), 10.seconds)
        }
      } else {
        Await.result(GameRepository.savePuzzle(ujson.write(puzzleJson), "custom"), 10.seconds)
      }
      
      logger.info(s"[CREATOR] Puzzle saved successfully with ID: $puzzleId")
      
      cask.Response(ujson.Obj("status" -> "success", "id" -> puzzleId))
    }
  }

  @cask.get("/puzzle/my-custom")
  def getMyCustomPuzzles(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val puzzles = Await.result(GameRepository.getPuzzlesCreatedBy(email), 10.seconds)
      cask.Response(ujson.Arr(puzzles.map(p => ujson.read(p.toJson())): _*))
    }
  }

  @cask.post("/puzzle/delete-custom")
  def deleteCustomPuzzle(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val data = ujson.read(request.readAllBytes())
      val puzzleId = data("id").str
      
      // Security check: ensure the puzzle belongs to the user
      val existing = Await.result(GameRepository.getPuzzlesCreatedBy(email), 10.seconds)
        .find(_.get("id").exists(_.asString().getValue == puzzleId))
        
      existing match {
        case Some(doc) =>
          val mongoId = doc.getObjectId("_id").toHexString
          Await.result(GameRepository.deletePuzzle(mongoId), 10.seconds)
          cask.Response(ujson.Obj("status" -> "success"))
        case None =>
          cask.Response(ujson.Obj("status" -> "error", "message" -> "Puzzle not found or access denied"), statusCode = 404)
      }
    }
  }

  initialize()
}
