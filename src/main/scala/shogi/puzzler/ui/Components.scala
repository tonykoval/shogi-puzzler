package shogi.puzzler.ui

import scalatags.Text.all._
import shogi.puzzler.db.AppSettings
import scala.concurrent.Await
import scala.concurrent.duration._

object Components {
  def layout(title: String, userEmail: Option[String], settings: AppSettings, version: String = "", scripts: Seq[Modifier] = Seq.empty)(content: Modifier*) = {
    html(lang := "en", cls := "dark")(
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", attr("content") := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(name := "theme-color", attr("content") := "#2e2a24"),
        tag("title")(title),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/select2@4.1.0-rc.0/dist/css/select2.min.css"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/select2-bootstrap-5-theme@1.3.0/dist/select2-bootstrap-5-theme.min.css"),
        link(rel := "stylesheet", href := "/assets/css/select2-dark.css"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.10.3/font/bootstrap-icons.css"),
        link(rel := "stylesheet", href := "/assets/css/common.css"),
        link(rel := "stylesheet", href := "/assets/css/site.css"),
        script(src := "https://code.jquery.com/jquery-3.6.0.min.js"),
        script(src := "https://cdn.jsdelivr.net/npm/select2@4.1.0-rc.0/dist/js/select2.min.js"),
        script(src := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js"),
        scripts
      ),
      body(cls := "mt-0")(
        renderHeader(userEmail, settings, version),
        div(cls := "container-fluid", style := "max-width: 1400px;")(
          content
        )
      )
    )
  }

  def gameFetcherCard(title: String, idPrefix: String, nickname: String, placeholder: String) = {
    div(cls := "row mb-4")(
      div(cls := "col-md-12")(
        div(cls := "card bg-dark text-light border-secondary")(
          div(cls := "card-body")(
            div(cls := "d-flex flex-wrap justify-content-between align-items-center gap-3 mb-3")(
              h2(cls := "mb-0")(title),
              div(cls := "d-flex flex-wrap gap-2")(
                div(cls := "input-group input-group-sm", style := "width: 130px")(
                  tag("span")(cls := "input-group-text bg-dark text-light border-secondary")("Hits"),
                  input(`type` := "number", id := s"${idPrefix}MaxGames", cls := "form-control bg-dark text-light border-secondary", value := "10", onchange := " $('.reload-data').first().click(); ")
                ),
                button(cls := "btn btn-sm btn-outline-primary", onclick := s"window.maintenance.doFetch('$idPrefix', '$nickname', true)")(s"Fetch $title")
              )
            ),
            div(id := s"$idPrefix-results", cls := "results-container")(
              p(placeholder)
            )
          )
        )
      )
    )
  }

  def configField(labelName: String, fieldName: String, valueStr: String, inputType: String = "text", stepValue: Option[String] = None) = {
    div(cls := "mb-3")(
      label(cls := "form-label")(labelName),
      input(`type` := inputType, name := fieldName, cls := "form-control bg-dark text-light border-secondary", value := valueStr, stepValue.map(s => step := s).getOrElse(()))
    )
  }

  def renderHeader(userEmail: Option[String], settings: AppSettings, version: String) = {
    val userDoc = userEmail.flatMap(email => scala.util.Try(Await.result(shogi.puzzler.db.UserRepository.getUser(email), 1.second)).toOption.flatten)
    val userRole = userDoc.map(_.role)
    val allowedPages = userDoc.map(_.allowedPages).getOrElse(Set.empty)
    val isAdmin = userRole.contains("ADMIN")
    
    def canAccess(page: String): Boolean = isAdmin || allowedPages.contains("*") || allowedPages.contains(page)

    tag("nav")(cls := "navbar navbar-expand-lg navbar-dark bg-dark mb-4")(
      div(cls := "container-fluid")(
        a(cls := "navbar-brand", href := "/")("Shogi Puzzler"),
        if (version.nonEmpty) scalatags.Text.all.span(cls := "badge bg-dark border border-secondary text-secondary ms-1", style := "font-size: 0.7rem;")("v" + version) else (),
        button(cls := "navbar-toggler", `type` := "button", 
          attr("data-bs-toggle") := "collapse", 
          attr("data-bs-target") := "#navbarNav") (
          scalatags.Text.all.span(cls := "navbar-toggler-icon")
        ),
        div(cls := "collapse navbar-collapse", id := "navbarNav")(
          ul(cls := "navbar-nav me-auto")(
            if (canAccess("my-games")) {
              li(cls := "nav-item")(
                a(cls := "nav-link", href := "/my-games")(i(cls := "bi bi-controller me-1"), scalatags.Text.all.span(cls := "d-lg-inline d-none")("My Games"), scalatags.Text.all.span(cls := "d-lg-none")("Games"))
              )
            } else (),
            if (canAccess("repertoire")) {
              li(cls := "nav-item")(
                a(cls := "nav-link", href := "/repertoire")(i(cls := "bi bi-book me-1"), scalatags.Text.all.span(cls := "d-lg-inline d-none")("Rep. Editor"), scalatags.Text.all.span(cls := "d-lg-none")("Rep"))
              )
            } else (),
            if (canAccess("puzzle-creator")) {
              li(cls := "nav-item")(
                a(cls := "nav-link", href := "/puzzle-creator")(i(cls := "bi bi-plus-circle me-1"), scalatags.Text.all.span(cls := "d-lg-inline d-none")("Puzzle Editor"), scalatags.Text.all.span(cls := "d-lg-none")("Editor"))
              )
            } else (),
            li(cls := "nav-item")(
              a(cls := "nav-link", href := "/viewer")(i(cls := "bi bi-puzzle me-1"), scalatags.Text.all.span(cls := "d-lg-inline d-none")("Puzzles"), scalatags.Text.all.span(cls := "d-lg-none")("Puzzles"))
            ),
            li(cls := "nav-item")(
              a(cls := "nav-link", href := "/repertoire-viewer")(i(cls := "bi bi-book-half me-1"), scalatags.Text.all.span(cls := "d-lg-inline d-none")("Repertoires"), scalatags.Text.all.span(cls := "d-lg-none")("Reps"))
            ),
            if (canAccess("training")) {
              li(cls := "nav-item")(
                a(cls := "nav-link", href := "/training")(i(cls := "bi bi-mortarboard me-1"), scalatags.Text.all.span(cls := "d-lg-inline d-none")("Training"), scalatags.Text.all.span(cls := "d-lg-none")("Train"))
              )
            } else (),
            if (canAccess("config")) {
              li(cls := "nav-item")(
                a(cls := "nav-link", href := "/config")(i(cls := "bi bi-gear me-1"), scalatags.Text.all.span(cls := "d-lg-inline d-none")("Config"), scalatags.Text.all.span(cls := "d-lg-none")("Cfg"))
              )
            } else (),
            if (canAccess("ocr")) {
              li(cls := "nav-item")(
                a(cls := "nav-link", href := "/ocr")(i(cls := "bi bi-camera me-1"), scalatags.Text.all.span(cls := "d-lg-inline d-none")("OCR Library"), scalatags.Text.all.span(cls := "d-lg-none")("OCR"))
              )
            } else (),
            if (canAccess("admin/users")) {
              li(cls := "nav-item")(
                a(cls := "nav-link", href := "/admin/users")(i(cls := "bi bi-people me-1"), scalatags.Text.all.span(cls := "d-lg-inline d-none")("Users"), scalatags.Text.all.span(cls := "d-lg-none")("Users"))
              )
            } else ()
          ),
          div(cls := "navbar-text d-flex align-items-center flex-wrap")(
            if (userEmail.isDefined) {
              div(cls := "me-lg-3 text-light-50 my-1", style := "font-size: 0.85rem;")(
                div(cls := "d-inline-flex flex-wrap gap-2")(
                  scalatags.Text.all.span(scalatags.Text.all.span(cls := "badge bg-secondary me-1")("Lishogi"), scalatags.Text.all.span(settings.lishogiNickname)),
                  scalatags.Text.all.span(scalatags.Text.all.span(cls := "badge bg-secondary me-1")("ShogiWars"), scalatags.Text.all.span(settings.shogiwarsNickname)),
                  scalatags.Text.all.span(scalatags.Text.all.span(cls := "badge bg-secondary me-1")("81Dojo"), scalatags.Text.all.span(settings.dojo81Nickname))
                )
              )
            } else (),
            scalatags.Text.all.span(cls := "ms-lg-2 my-1")(
              userEmail.map(email => 
                scalatags.Text.all.span(cls := "d-inline-flex flex-wrap align-items-center gap-2")(
                  scalatags.Text.all.span(cls := "text-light", style := "font-size: 0.85rem;")("Logged in as:"),
                  scalatags.Text.all.span(cls := "badge bg-primary")(i(cls := "bi bi-person-fill me-1"), email),
                  a(href := "/logout", cls := "btn btn-sm btn-outline-light")("Logout")
                )
              ).getOrElse(
                a(href := "/login", cls := "btn btn-sm btn-outline-primary")("Login")
              )
            )
          )
        )
      )
    )
  }
}
