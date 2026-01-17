package shogi.puzzler.ui

import scalatags.Text.all._
import shogi.puzzler.db.AppSettings

object Components {
  def layout(title: String, userEmail: Option[String], settings: AppSettings, scripts: Seq[Modifier] = Seq.empty)(content: Modifier*) = {
    html(lang := "en", cls := "dark")(
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", attr("content") := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(name := "theme-color", attr("content") := "#2e2a24"),
        tag("title")(title),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"),
        link(rel := "stylesheet", href := "/assets/css/common.css"),
        link(rel := "stylesheet", href := "/assets/css/site.css"),
        script(src := "https://code.jquery.com/jquery-3.6.0.min.js"),
        script(src := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js"),
        scripts
      ),
      body(cls := "container mt-0")(
        renderHeader(userEmail, settings),
        content
      )
    )
  }

  def gameFetcherCard(title: String, idPrefix: String, nickname: String, placeholder: String) = {
    div(cls := "row mb-4")(
      div(cls := "col-md-12")(
        div(cls := "card bg-dark text-light border-secondary")(
          div(cls := "card-body")(
            div(cls := "d-flex justify-content-between align-items-center mb-3")(
              h2(cls := "mb-0")(title),
              div(cls := "d-flex gap-2")(
                div(cls := "input-group input-group-sm", style := "width: 150px")(
                  tag("span")(cls := "input-group-text bg-dark text-light border-secondary")("Hits"),
                  input(`type` := "number", id := s"${idPrefix}MaxGames", cls := "form-control bg-dark text-light border-secondary", value := "10")
                ),
                input(`type` := "text", id := s"${idPrefix}NicknameInput", cls := "form-control form-control-sm bg-dark text-light border-secondary", value := nickname, style := "width: 150px"),
                button(cls := "btn btn-sm btn-outline-primary", onclick := s"window.maintenance.doFetch('$idPrefix', $$('#${idPrefix}NicknameInput').val(), true)")(s"Fetch $title")
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

  def renderHeader(userEmail: Option[String], settings: AppSettings) = {
    val engineName = settings.enginePath.split("[\\\\/]").last
    
    tag("nav")(cls := "navbar navbar-expand-lg navbar-dark bg-dark mb-4")(
      div(cls := "container-fluid")(
        a(cls := "navbar-brand", href := "/maintenance")("Shogi Puzzler"),
        button(cls := "navbar-toggler", `type` := "button", 
          attr("data-bs-toggle") := "collapse", 
          attr("data-bs-target") := "#navbarNav") (
          span(cls := "navbar-toggler-icon")
        ),
        div(cls := "collapse navbar-collapse", id := "navbarNav")(
          ul(cls := "navbar-nav me-auto")(
            li(cls := "nav-item")(
              a(cls := "nav-link", href := "/maintenance")("Maintenance")
            ),
            li(cls := "nav-item")(
              a(cls := "nav-link", href := "/viewer")("Puzzle viewer")
            ),
            li(cls := "nav-item")(
              a(cls := "nav-link", href := "/config")("Config")
            )
          ),
          div(cls := "navbar-text d-flex align-items-center")(
            div(cls := "me-3 text-light-50", style := "font-size: 0.85rem;")(
              div(cls := "d-inline-block me-3")(
                span(cls := "badge bg-secondary me-1")("Lishogi"), span(settings.lishogiNickname),
                span(cls := "badge bg-secondary ms-2 me-1")("ShogiWars"), span(settings.shogiwarsNickname)
              )
            ),
            span(cls := "ms-2")(
              userEmail.map(email => 
                span(
                  span(cls := "badge bg-primary")(i(cls := "bi bi-person-fill me-1"), email),
                  a(href := "/logout", cls := "btn btn-sm btn-outline-light ms-2")("Logout")
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
