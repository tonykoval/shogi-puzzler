package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{RepertoireRepository, SettingsRepository, AppSettings}
import shogi.puzzler.ui.Components
import scala.concurrent.Await
import scala.concurrent.duration._

object RepertoireViewerRoutes extends BaseRoutes {

  @cask.get("/repertoire-viewer")
  def index(request: cask.Request) = {
    val userEmail = getSessionUserEmail(request)
    val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
    val repertoires = Await.result(RepertoireRepository.getPublicRepertoires(), 10.seconds)

    cask.Response(
      renderListingPage(userEmail, settings, repertoires).render,
      headers = Seq("Content-Type" -> "text/html; charset=utf-8")
    )
  }

  def renderListingPage(userEmail: Option[String], settings: AppSettings, repertoires: Seq[org.mongodb.scala.Document]) = {
    Components.layout(
      "Public Repertoires",
      userEmail,
      settings,
      appVersion
    )(
      div(cls := "d-flex justify-content-between align-items-center mb-4")(
        h1("Public Repertoires")
      ),

      if (repertoires.isEmpty) {
        div(cls := "text-muted")(
          p("No public repertoires available yet.")
        )
      } else {
        div(cls := "row")(
          repertoires.map { rep =>
            val id = rep.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
            val name = rep.getString("name")

            div(cls := "col-md-4 mb-3")(
              div(cls := "card bg-dark text-light border-secondary")(
                div(cls := "card-body")(
                  h5(cls := "card-title")(i(cls := "bi bi-book-half me-2"), name),
                  a(href := s"/repertoire-viewer/$id", cls := "btn btn-sm btn-outline-info")("Open")
                )
              )
            )
          }
        )
      }
    )
  }

  @cask.get("/repertoire-viewer/:id")
  def view(id: String, request: cask.Request) = {
    val userEmail = getSessionUserEmail(request)
    val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
    val repertoire = Await.result(RepertoireRepository.getPublicRepertoire(id), 10.seconds)

    repertoire match {
      case Some(rep) =>
        cask.Response(
          renderViewer(userEmail, settings, rep).render,
          headers = Seq("Content-Type" -> "text/html; charset=utf-8")
        )
      case None => cask.Response("Not Found", statusCode = 404)
    }
  }

  def renderViewer(userEmail: Option[String], settings: AppSettings, repertoire: org.mongodb.scala.Document) = {
    val id = repertoire.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
    val name = repertoire.getString("name")
    val sourceAuthor = repertoire.get("sourceAuthor").map(_.asString().getValue).getOrElse("")
    val studyUrl = repertoire.get("studyUrl").map(_.asString().getValue).getOrElse("")

    html(lang := "en", cls := "dark", style := "--zoom:90;")(
      head(
        meta(charset := "utf-8"),
        meta(attr("name") := "viewport", attr("content") := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(attr("name") := "theme-color", attr("content") := "#2e2a24"),
        tag("title")(s"$name — Repertoire"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"),
        link(rel := "stylesheet", href := "/assets/css/shogiground.css"),
        link(rel := "stylesheet", href := "/assets/css/portella.css"),
        link(rel := "stylesheet", href := "/assets/css/site.css"),
        link(rel := "stylesheet", href := "/assets/css/puzzle.css"),
        link(rel := "stylesheet", href := "/assets/css/common.css"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.10.3/font/bootstrap-icons.css"),
        script(src := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js")
      ),
      body(cls := "wood coords-out playing online")(
        Components.renderHeader(userEmail, settings, appVersion),
        div(attr("id") := "main-wrap")(
          tag("main")(cls := "puzzle puzzle-play")(
            div(cls := "puzzle__board main-board")(
              div(cls := "sg-wrap d-9x9")(
                tag("sg-hand-wrap")(attr("id") := "hand-top"),
                div(attr("id") := "dirty", cls := "dirty"),
                tag("sg-hand-wrap")(attr("id") := "hand-bottom")
              )
            ),
            div(cls := "puzzle__comment", attr("id") := "comment-card")(
              h2(cls := "puzzle__comment__title")(name),
              if (sourceAuthor.nonEmpty || studyUrl.nonEmpty) {
                div(cls := "puzzle__comment__source small text-muted mb-2")(
                  if (sourceAuthor.nonEmpty) frag(
                    i(cls := "bi bi-person me-1"),
                    a(href := s"https://lishogi.org/@/$sourceAuthor", attr("target") := "_blank", cls := "text-muted")(sourceAuthor)
                  ) else frag(),
                  if (sourceAuthor.nonEmpty && studyUrl.nonEmpty) tag("span")(cls := "mx-1")(" · ") else frag(),
                  if (studyUrl.nonEmpty) frag(
                    i(cls := "bi bi-book me-1"),
                    a(href := studyUrl, attr("target") := "_blank", cls := "text-muted")("Lishogi Study")
                  ) else frag()
                )
              } else frag(),
              div(attr("id") := "comment-display", style := "display:none;")
            ),
            div(cls := "puzzle__side")(
              div(cls := "puzzle__side__box")(
                div(cls := "puzzle__tools")(
                  div(cls := "analyse__tools")(
                    div(cls := "analyse__tools__menu")(
                      button(cls := "btn btn-sm btn-outline-light me-1", onclick := "toRoot()", title := "Back to Start")(i(cls := "bi bi-chevron-double-left")),
                      button(cls := "btn btn-sm btn-outline-light me-1", onclick := "revertMove()", title := "Previous Move")(i(cls := "bi bi-chevron-left")),
                      button(cls := "btn btn-sm btn-outline-light", onclick := "advanceMove()", title := "Next Move")(i(cls := "bi bi-chevron-right"))
                    ),
                    div(cls := "analyse__moves")(
                      div(cls := "analyse__moves__list")(
                        div(attr("id") := "variation-list")(
                          "Loading moves..."
                        )
                      )
                    )
                  )
                ),
                div(cls := "puzzle__feedback")()
              )
            )
          )
        ),
        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        input(`type` := "hidden", attr("id") := "repertoireId", attr("value") := id),
        script(src := "/js/repertoire-viewer.js", `type` := "module")
      )
    )
  }

  @cask.get("/repertoire-viewer/:id/json")
  def getRepertoireJson(id: String, request: cask.Request): cask.Response[ujson.Value] = {
    val rep = Await.result(RepertoireRepository.getPublicRepertoire(id), 10.seconds)
    rep match {
      case Some(doc) =>
        cask.Response(
          ujson.read(doc.toJson()),
          headers = Seq("Content-Type" -> "application/json")
        )
      case None =>
        cask.Response(ujson.Obj("error" -> "Not Found"), statusCode = 404)
    }
  }

  initialize()
}
