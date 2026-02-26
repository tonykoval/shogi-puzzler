package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{StudyRepository, SettingsRepository, AppSettings}
import shogi.puzzler.i18n.I18n
import shogi.puzzler.ui.Components
import scala.concurrent.Await
import scala.concurrent.duration._

object StudyViewerRoutes extends BaseRoutes {

  @cask.get("/study-viewer")
  def index(lang: Option[String] = None, request: cask.Request) = {
    val userEmail = getSessionUserEmail(request)
    val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
    val pageLang = getLang(request)
    val studies = Await.result(StudyRepository.getStudiesForViewer(userEmail), 10.seconds)

    cask.Response(
      renderListingPage(userEmail, settings, studies, pageLang).render,
      headers = Seq("Content-Type" -> "text/html; charset=utf-8") ++ langCookieHeaders(request)
    )
  }

  def renderListingPage(userEmail: Option[String], settings: AppSettings, studies: Seq[org.mongodb.scala.Document], pageLang: String = I18n.defaultLang) = {
    val ownerEmail = userEmail.getOrElse("")

    // Split into Lishogi-imported groups vs standalone
    val (lishogiStudies, standaloneStudies) = studies.partition(
      _.get("studyUrl").exists(v => v.isString && v.asString().getValue.nonEmpty)
    )
    val studyGroups = lishogiStudies
      .groupBy(_.get("studyUrl").map(_.asString().getValue).getOrElse(""))
      .toSeq.sortBy(_._1)

    def ownerBadge(s: org.mongodb.scala.Document) = {
      val isOwner  = s.get("ownerEmail").map(_.asString().getValue).contains(ownerEmail) && ownerEmail.nonEmpty
      val isPublic = s.get("is_public").exists(_.asBoolean().getValue)
      if (isOwner && !isPublic)
        frag(scalatags.Text.all.span(cls := "badge bg-warning text-dark ms-1")(i(cls := "bi bi-lock me-1"), "Private"))
      else if (isOwner)
        frag(scalatags.Text.all.span(cls := "badge bg-success ms-1")(i(cls := "bi bi-globe me-1"), "Mine"))
      else
        frag()
    }

    val filterScript = script(raw("""
function svFilter() {
  const q = (document.getElementById('svSearch')?.value || '').toLowerCase();
  const src = document.querySelector('[data-sv-filter].active')?.dataset?.svFilter || 'all';
  document.querySelectorAll('.sv-item').forEach(el => {
    const nameMatch = !q || (el.dataset.name || '').includes(q);
    const srcMatch  = src === 'all' || el.dataset.source === src;
    el.style.display = (nameMatch && srcMatch) ? '' : 'none';
  });
  document.querySelectorAll('.sv-group').forEach(grp => {
    const visible = [...grp.querySelectorAll('.sv-item')].some(el => el.style.display !== 'none');
    grp.style.display = visible ? '' : 'none';
  });
}
function svSetFilter(btn) {
  document.querySelectorAll('[data-sv-filter]').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  svFilter();
}
"""))

    Components.layout(
      "Studies",
      userEmail,
      settings,
      appVersion,
      lang = pageLang
    )(
      filterScript,
      div(cls := "d-flex justify-content-between align-items-center mb-4")(
        h1(i(cls := "bi bi-book-half me-2"), "Studies")
      ),

      // Filter bar
      div(cls := "card bg-dark border-secondary mb-4")(
        div(cls := "card-body py-2")(
          div(cls := "d-flex align-items-center gap-3 flex-wrap")(
            div(cls := "flex-grow-1", style := "max-width: 300px;")(
              input(`type` := "text", id := "svSearch", cls := "form-control form-control-sm bg-dark text-light border-secondary",
                placeholder := "Search studies...", attr("oninput") := "svFilter()")
            ),
            div(cls := "btn-group btn-group-sm")(
              button(cls := "btn btn-outline-light active", attr("data-sv-filter") := "all",   onclick := "svSetFilter(this)")("All"),
              button(cls := "btn btn-outline-light",        attr("data-sv-filter") := "lishogi", onclick := "svSetFilter(this)")("Lishogi Study"),
              button(cls := "btn btn-outline-light",        attr("data-sv-filter") := "manual",  onclick := "svSetFilter(this)")("Manual / KIF")
            )
          )
        )
      ),

      if (studies.isEmpty) {
        div(cls := "text-muted mt-3")(p("No studies available yet."))
      } else frag(
        // Lishogi study groups
        studyGroups.map { case (studyUrl, reps) =>
          val firstRep  = reps.head
          val studyName = firstRep.get("studyName").map(_.asString().getValue).getOrElse(firstRep.getString("name"))
          val author    = firstRep.get("sourceAuthor").map(_.asString().getValue).getOrElse("")
          val groupId   = math.abs(studyUrl.hashCode).toHexString

          div(cls := "card bg-dark text-light border-secondary mb-3 sv-group", attr("data-source") := "lishogi")(
            div(cls := "card-header d-flex align-items-center py-2",
              attr("data-bs-toggle") := "collapse", attr("data-bs-target") := s"#svg-$groupId",
              role := "button", style := "cursor: pointer;")(
              i(cls := "bi bi-book me-2 text-info"),
              tag("span")(cls := "fw-semibold me-2")(studyName),
              tag("span")(cls := "badge bg-secondary me-2")(s"${reps.size} chapters"),
              if (author.nonEmpty) tag("span")(cls := "text-muted small me-2")(
                "by ",
                a(href := s"https://lishogi.org/@/$author", target := "_blank", cls := "text-muted text-decoration-none")(author)
              ) else frag(),
              if (studyUrl.nonEmpty) a(href := studyUrl, target := "_blank",
                cls := "text-muted small ms-auto me-2", onclick := "event.stopPropagation();")(
                i(cls := "bi bi-box-arrow-up-right me-1"), "Lishogi"
              ) else frag()
            ),
            div(cls := "collapse show", id := s"svg-$groupId")(
              div(cls := "list-group list-group-flush")(
                reps.map { rep =>
                  val id      = rep.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
                  val repName = rep.getString("name")
                  div(cls := "list-group-item bg-dark text-light border-secondary d-flex align-items-center py-2 sv-item",
                    attr("data-source") := "lishogi", attr("data-name") := repName.toLowerCase)(
                    tag("span")(cls := "flex-grow-1")(repName),
                    ownerBadge(rep),
                    a(href := s"/study-viewer/$id", cls := "btn btn-sm btn-outline-info ms-2")("Open")
                  )
                }
              )
            )
          )
        },

        // Standalone studies grid
        div(cls := "row")(
          standaloneStudies.map { s =>
            val id       = s.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
            val name     = s.getString("name")
            val author   = s.get("sourceAuthor").map(_.asString().getValue).getOrElse("")
            val hasLishogi = s.get("sourceUrl").exists(v => v.isString && v.asString().getValue.nonEmpty)
            val sourceType = if (hasLishogi) "lishogi" else "manual"
            div(cls := "col-md-4 mb-3 sv-item", attr("data-source") := sourceType, attr("data-name") := name.toLowerCase)(
              div(cls := "card bg-dark text-light border-secondary h-100")(
                div(cls := "card-body d-flex flex-column")(
                  div(cls := "d-flex align-items-start gap-1 mb-2 flex-wrap")(
                    h6(cls := "card-title mb-0 me-auto")(name),
                    ownerBadge(s)
                  ),
                  if (author.nonEmpty) p(cls := "text-muted small mb-2")(
                    i(cls := "bi bi-person me-1"),
                    a(href := s"https://lishogi.org/@/$author", target := "_blank", cls := "text-muted")(author)
                  ) else frag(),
                  a(href := s"/study-viewer/$id", cls := "btn btn-sm btn-outline-info mt-auto align-self-start")("Open")
                )
              )
            )
          }
        )
      )
    )
  }

  @cask.get("/study-viewer/:id")
  def view(id: String, lang: Option[String] = None, request: cask.Request) = {
    val userEmail = getSessionUserEmail(request)
    val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
    val pageLang = getLang(request)
    val study = Await.result(StudyRepository.getStudyForViewer(id, userEmail), 10.seconds)

    study match {
      case Some(rep) =>
        cask.Response(
          renderViewer(userEmail, settings, rep, pageLang).render,
          headers = Seq("Content-Type" -> "text/html; charset=utf-8") ++ langCookieHeaders(request)
        )
      case None => cask.Response("Not Found", statusCode = 404)
    }
  }

  def renderViewer(userEmail: Option[String], settings: AppSettings, study: org.mongodb.scala.Document, pageLang: String = I18n.defaultLang) = {
    val id = study.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
    val name = study.getString("name")
    val sourceAuthor = study.get("sourceAuthor").map(_.asString().getValue).getOrElse("")
    val studyUrl = study.get("studyUrl").map(_.asString().getValue).getOrElse("")

    html(lang := "en", cls := "dark", style := "--zoom:90;")(
      head(
        meta(charset := "utf-8"),
        meta(attr("name") := "viewport", attr("content") := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(attr("name") := "theme-color", attr("content") := "#2e2a24"),
        tag("title")(s"$name — Study"),
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
        Components.renderHeader(userEmail, settings, appVersion, pageLang),
        div(attr("id") := "main-wrap")(
          div(cls := "rp-main-col")(
            div(cls := "puzzle__board-header")(
              h2(cls := "puzzle__board-header__title")(name),
              if (sourceAuthor.nonEmpty || studyUrl.nonEmpty) {
                div(cls := "puzzle__board-header__source")(
                  if (sourceAuthor.nonEmpty) frag(
                    i(cls := "bi bi-person me-1"),
                    a(href := s"https://lishogi.org/@/$sourceAuthor", attr("target") := "_blank")(sourceAuthor)
                  ) else frag(),
                  if (sourceAuthor.nonEmpty && studyUrl.nonEmpty) tag("span")(cls := "mx-1")(" · ") else frag(),
                  if (studyUrl.nonEmpty) frag(
                    i(cls := "bi bi-book me-1"),
                    a(href := studyUrl, attr("target") := "_blank")("Lishogi Study")
                  ) else frag()
                )
              } else frag()
            ),
          tag("main")(cls := "puzzle puzzle-play")(
            div(cls := "puzzle__board main-board")(
              div(cls := "sg-wrap d-9x9")(
                tag("sg-hand-wrap")(attr("id") := "hand-top"),
                div(attr("id") := "dirty", cls := "dirty"),
                tag("sg-hand-wrap")(attr("id") := "hand-bottom")
              )
            ),
            div(cls := "puzzle__comment", attr("id") := "comment-card")(
              div(cls := "puzzle__controls mb-2")(
                div(cls := "analyse__tools")(
                  div(cls := "analyse__tools__menu")(
                    button(cls := "btn btn-outline-light", onclick := "toRoot()", title := "Back to Start")(i(cls := "bi bi-chevron-double-left")),
                    button(cls := "btn btn-outline-light", onclick := "revertMove()", title := "Previous Move")(i(cls := "bi bi-chevron-left")),
                    button(cls := "btn btn-outline-light", onclick := "advanceMove()", title := "Next Move")(i(cls := "bi bi-chevron-right"))
                  )
                )
              ),
              div(attr("id") := "comment-display", style := "display:none;")
            ),

            div(cls := "puzzle__side")(
              div(cls := "puzzle__side__box")(
                div(cls := "puzzle__tools")(
                  div(cls := "analyse__tools")(
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
          )  // end main / rp-main-col
        ),
        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        input(`type` := "hidden", attr("id") := "studyId", attr("value") := id),
        script(src := "/js/study-viewer.js", `type` := "module")
      )
    )
  }

  @cask.get("/study-viewer/:id/json")
  def getStudyJson(id: String, request: cask.Request): cask.Response[ujson.Value] = {
    val userEmail = getSessionUserEmail(request)
    val rep = Await.result(StudyRepository.getStudyForViewer(id, userEmail), 10.seconds)
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
