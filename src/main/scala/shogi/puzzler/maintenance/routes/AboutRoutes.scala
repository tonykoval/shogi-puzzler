package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{SettingsRepository, AppSettings}
import shogi.puzzler.i18n.I18n
import shogi.puzzler.ui.Components
import scala.concurrent.Await
import scala.concurrent.duration._

object AboutRoutes extends BaseRoutes {

  private val githubUrl: String =
    if (config.hasPath("app.github.url")) config.getString("app.github.url") else ""

  private val contactEmail: String =
    if (config.hasPath("app.contact.email")) config.getString("app.contact.email") else ""

  @cask.get("/about")
  def about(lang: Option[String] = None, request: cask.Request) = {
    val userEmail = getSessionUserEmail(request)
    val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
    implicit val pageLang: String = getLang(request)
    cask.Response(
      renderAboutPage(userEmail, settings).render,
      headers = Seq("Content-Type" -> "text/html; charset=utf-8") ++ langCookieHeaders(request)
    )
  }

  def renderAboutPage(userEmail: Option[String], settings: AppSettings)(implicit lang: String = I18n.defaultLang) = {
    // (icon, titleKey, descKey, borderClass, isPublic)
    val features = Seq(
      ("bi-database",        "about.database",     "about.databaseDesc",     "border-info",      true),
      ("bi-puzzle",          "about.puzzleViewer", "about.puzzleViewerDesc", "border-success",   true),
      ("bi-book-half",       "about.studyViewer",  "about.studyViewerDesc",  "border-primary",   true),
      ("bi-book",            "about.study",        "about.studyDesc",        "border-primary",   false),
      ("bi-cloud-download",  "about.fetching",     "about.fetchingDesc",     "border-secondary", false),
      ("bi-cpu",             "about.analysis",     "about.analysisDesc",     "border-warning",   false),
      ("bi-mortarboard",     "about.training",     "about.trainingDesc",     "border-danger",    false),
      ("bi-plus-circle",     "about.creator",      "about.creatorDesc",      "border-secondary", false),
      ("bi-camera",          "about.ocr",          "about.ocrDesc",          "border-light",     false),
      ("bi-translate",       "about.i18n",         "about.i18nDesc",         "border-info",      true)
    )

    // (name, description, githubUrl)
    val techStack = Seq(
      ("Scala 2.13",   "Application language",                     "https://github.com/scala/scala"),
      ("Cask 0.11",    "Lightweight HTTP framework",                "https://github.com/com-lihaoyi/cask"),
      ("Scalatags",    "Server-side HTML rendering",                "https://github.com/com-lihaoyi/scalatags"),
      ("MongoDB",      "Game and puzzle storage",                   "https://github.com/mongodb/mongo-scala-driver"),
      ("YaneuraOu",    "Shogi engine (USI protocol)",               "https://github.com/yaneurao/YaneuraOu"),
      ("Scalashogi",   "Game logic, move validation, SFEN parsing", "https://github.com/WandererXII/scalashogi"),
      ("Shogiground",  "Interactive board rendering",               "https://github.com/WandererXII/shogiground"),
      ("Playwright",   "Browser automation for 81Dojo fetching",   "https://github.com/microsoft/playwright"),
      ("Tess4j",       "OCR — board image to SFEN",                "https://github.com/nguyenq/tess4j"),
      ("Bootstrap 5",  "UI framework (dark theme)",                 "https://github.com/twbs/bootstrap")
    )

    val acknowledgments = Seq(
      ("bi-github", "WandererXII", "https://github.com/WandererXII", "about.thanksWanderer"),
      ("bi-github", "yaneurao",    "https://github.com/yaneurao",    "about.thanksYane"),
      ("bi-github", "Li Haoyi",   "https://github.com/lihaoyi",     "about.thanksLiHaoyi"),
      ("bi-people", "",            "",                                "about.thanksCommunity")
    )

    Components.layout(I18n.t("about.pageTitle"), userEmail, settings, appVersion, lang = lang)(
      // Hero
      div(cls := "py-5 mb-4 text-center")(
        h1(cls := "display-5 fw-bold")(
          i(cls := "bi bi-grid-3x3-gap-fill me-3", style := "color: #e8a317;"),
          "Shogi Puzzler"
        ),
        scalatags.Text.all.span(cls := "badge bg-secondary ms-2 mb-3", style := "font-size:0.9rem;")("v" + appVersion),
        p(cls := "lead text-light-50 mb-4")(I18n.t("about.tagline")),
        p(cls := "mx-auto", style := "max-width:680px; color:#adb5bd;")(I18n.t("about.description"))
      ),

      // Beta notice
      div(cls := "alert alert-warning border-warning mb-5 d-flex align-items-start gap-3", role := "alert")(
        i(cls := "bi bi-exclamation-triangle-fill flex-shrink-0 mt-1", style := "font-size:1.2rem;"),
        div(cls := "flex-grow-1")(
          div(cls := "mb-1")(
            scalatags.Text.all.span(cls := "badge bg-warning text-dark me-2")(I18n.t("about.betaBadge")),
            I18n.t("about.betaDesc")
          ),
          if (contactEmail.nonEmpty)
            a(href := s"mailto:$contactEmail", cls := "btn btn-sm btn-warning mt-2")(
              i(cls := "bi bi-envelope me-1"),
              I18n.t("about.contactUs")
            )
          else ()
        )
      ),

      // Features
      h2(cls := "mb-4")(
        i(cls := "bi bi-lightning-charge-fill me-2", style := "color:#e8a317;"),
        I18n.t("about.features")
      ),
      div(cls := "row g-3 mb-5")(
        features.map { case (icon, titleKey, descKey, border, isPublic) =>
          div(cls := "col-md-6 col-lg-4")(
            div(cls := s"card bg-dark text-light h-100 border-start border-3 $border", style := "border-top:none;border-right:none;border-bottom:none;")(
              div(cls := "card-body")(
                div(cls := "d-flex justify-content-between align-items-start mb-2")(
                  h6(cls := "card-title mb-0")(
                    i(cls := s"bi $icon me-2"),
                    I18n.t(titleKey)
                  ),
                  if (isPublic)
                    scalatags.Text.all.span(cls := "badge bg-success flex-shrink-0 ms-1", style := "font-size:0.65rem;")(I18n.t("about.publicBadge"))
                  else
                    scalatags.Text.all.span(cls := "badge bg-warning text-dark flex-shrink-0 ms-1", style := "font-size:0.65rem;")(I18n.t("about.authRequired"))
                ),
                p(cls := "card-text small text-light-50 mb-0")(I18n.t(descKey))
              )
            )
          )
        }
      ),

      // Tech stack + Open source side by side
      div(cls := "row g-4 mb-4")(
        div(cls := "col-lg-7")(
          div(cls := "card bg-dark text-light border-secondary h-100")(
            div(cls := "card-body")(
              h5(cls := "card-title mb-3")(
                i(cls := "bi bi-stack me-2"),
                I18n.t("about.stack")
              ),
              div(cls := "row g-2")(
                techStack.map { case (name, desc, url) =>
                  div(cls := "col-sm-6")(
                    div(cls := "d-flex align-items-start gap-2")(
                      i(cls := "bi bi-check-circle-fill mt-1", style := "color:#28a745; flex-shrink:0;"),
                      div(
                        div(cls := "fw-bold small")(
                          a(href := url, target := "_blank", rel := "noopener noreferrer",
                            style := "color:inherit; text-decoration:none;")(name),
                          i(cls := "bi bi-box-arrow-up-right ms-1", style := "font-size:0.6rem; opacity:0.5;")
                        ),
                        div(style := "font-size:0.78rem; color:#a3a3a3;")(desc)
                      )
                    )
                  )
                }
              )
            )
          )
        ),
        div(cls := "col-lg-5")(
          div(cls := "card bg-dark text-light border-secondary h-100")(
            div(cls := "card-body d-flex flex-column")(
              h5(cls := "card-title mb-3")(
                i(cls := "bi bi-github me-2"),
                I18n.t("about.openSource")
              ),
              p(cls := "text-light-50")(I18n.t("about.openSourceDesc")),
              if (githubUrl.nonEmpty) {
                a(href := githubUrl, target := "_blank", rel := "noopener noreferrer",
                  cls := "btn btn-outline-light mt-auto align-self-start")(
                  i(cls := "bi bi-github me-2"),
                  I18n.t("about.viewOnGithub")
                )
              } else {
                p(cls := "text-light-50 fst-italic mt-auto small")(
                  i(cls := "bi bi-info-circle me-1"),
                  "Set ", code("app.github.url"), " in application.conf to enable the GitHub link."
                )
              }
            )
          )
        )
      ),

      // Acknowledgments
      div(cls := "card bg-dark text-light border-secondary mb-5")(
        div(cls := "card-body")(
          h5(cls := "card-title mb-1")(
            i(cls := "bi bi-heart-fill me-2", style := "color:#e8a317;"),
            I18n.t("about.acknowledgments")
          ),
          p(style := "color:#a3a3a3; font-size:0.9rem;")(I18n.t("about.acknowledgementsDesc")),
          div(cls := "row g-3 mt-1")(
            acknowledgments.map { case (icon, handle, url, textKey) =>
              div(cls := "col-md-6")(
                div(cls := "d-flex align-items-start gap-3")(
                  i(cls := s"bi $icon mt-1", style := "font-size:1.2rem; color:#a3a3a3; flex-shrink:0;"),
                  div(
                    if (url.nonEmpty)
                      div(cls := "fw-bold small mb-1")(
                        a(href := url, target := "_blank", rel := "noopener noreferrer",
                          style := "color:#e8a317; text-decoration:none;")(handle),
                        i(cls := "bi bi-box-arrow-up-right ms-1", style := "font-size:0.6rem; opacity:0.5;")
                      )
                    else (),
                    p(cls := "mb-0", style := "font-size:0.85rem; color:#a3a3a3;")(I18n.t(textKey))
                  )
                )
              )
            }
          )
        )
      )
    )
  }

  initialize()
}
