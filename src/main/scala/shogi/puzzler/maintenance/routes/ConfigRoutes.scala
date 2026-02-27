package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{SettingsRepository, AppSettings}
import shogi.puzzler.i18n.I18n
import shogi.puzzler.ui.Components
import shogi.puzzler.util.CryptoUtil
import scala.concurrent.Await
import scala.concurrent.duration._

object ConfigRoutes extends BaseRoutes {

  private def listEngines(): Seq[String] = {
    val engineDir = new java.io.File("engine")
    if (engineDir.exists() && engineDir.isDirectory) {
      engineDir.listFiles().filter(f => f.isFile && (f.getName.endsWith(".exe") || f.canExecute)).map(f => f.getName).toSeq
    } else {
      Nil
    }
  }

  @cask.get("/config")
  def configPage(request: cask.Request) = {
    withAuth(request, "config") { email =>
      val userEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      implicit val lang: String = getLang(request)
      cask.Response(
        renderConfigPage(userEmail, settings).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  def renderConfigPage(userEmail: Option[String], settings: AppSettings)(implicit lang: String = I18n.defaultLang) = {
    Components.layout(I18n.t("config.pageTitle"), userEmail, settings, appVersion, lang = lang)(
      h1(cls := "mb-4")(I18n.t("config.pageTitle")),
      div(cls := "card bg-dark text-light border-secondary")(
        div(cls := "card-body")(
          form(action := "/config", method := "post")(
            div(cls := "row")(
              div(cls := "col-md-4")(
                Components.configField(I18n.t("config.lishogiNick"), "lishogi_nickname", settings.lishogiNickname)
              ),
              div(cls := "col-md-4")(
                Components.configField(I18n.t("config.shogiwarsNick"), "shogiwars_nickname", settings.shogiwarsNickname)
              ),
              div(cls := "col-md-4")(
                Components.configField(I18n.t("config.dojo81Nick"), "dojo81_nickname", settings.dojo81Nickname)
              )
            ),
            div(cls := "mb-3")(
              label(cls := "form-label")(I18n.t("config.language")),
              select(name := "lang", cls := "form-select bg-dark text-light border-secondary")(
                option(value := "en", if (lang == "en") selected := "selected" else "")(I18n.t("config.languageEn")),
                option(value := "sk", if (lang == "sk") selected := "selected" else "")(I18n.t("config.languageSk"))
              )
            ),
            div(cls := "mb-3")(
              label(cls := "form-label")(I18n.t("config.engine")),
              select(name := "engine_path", cls := "form-select bg-dark text-light border-secondary")(
                option(value := "", if (settings.enginePath.isEmpty) selected := "selected" else "")(I18n.t("config.selectEngine")),
                listEngines().map { eng =>
                  option(value := eng, if (eng == settings.enginePath) selected := "selected" else "")(eng)
                }
              )
            ),
            div(cls := "row")(
              div(cls := "col-md-4 mb-3")(
                Components.configField(I18n.t("config.shallowLimit"), "shallow_limit", settings.shallowLimit.toString, "number")
              ),
              div(cls := "col-md-4 mb-3")(
                Components.configField(I18n.t("config.deepLimit"), "deep_limit", settings.deepLimit.toString, "number")
              ),
              div(cls := "col-md-4 mb-3")(
                Components.configField(I18n.t("config.winChanceThreshold"), "win_chance_threshold", settings.winChanceDropThreshold.toString, "number", Some("any"))
              )
            ),
            p(cls := "form-label fw-semibold mb-2 mt-2")(I18n.t("config.analyzeMoveSection")),
            div(cls := "row")(
              div(cls := "col-md-3 mb-3")(
                Components.configField(I18n.t("viewgame.posCandidates"), "pos_analysis_candidates", settings.posAnalysisCandidates.toString, "number")
              ),
              div(cls := "col-md-3 mb-3")(
                Components.configField(I18n.t("viewgame.posDepth"), "pos_analysis_depth", settings.posAnalysisDepth.toString, "number")
              ),
              div(cls := "col-md-3 mb-3")(
                Components.configField(I18n.t("viewgame.posSeconds"), "pos_analysis_seconds", settings.posAnalysisSeconds.toString, "number")
              ),
              div(cls := "col-md-3 mb-3")(
                Components.configField(I18n.t("viewgame.posSequences"), "pos_analysis_sequences", settings.posAnalysisSequences.toString, "number")
              )
            ),
            button(`type` := "submit", cls := "btn btn-primary w-100 w-md-auto")(I18n.t("config.save")),
            if (settings.shogiwarsNickname == "Tonyko") {
              div(cls := "alert alert-warning mt-3")(
                i(cls := "bi bi-exclamation-triangle-fill me-2"),
                I18n.t("config.defaultNicknameWarning")
              )
            } else ""
          )
        )
      )
    )
  }

  @cask.postForm("/config")
  def saveConfig(
      lishogi_nickname: String,
      shogiwars_nickname: String,
      dojo81_nickname: String,
      engine_path: String,
      shallow_limit: Int,
      deep_limit: Int,
      win_chance_threshold: Double,
      lang: String,
      pos_analysis_candidates: Int,
      pos_analysis_depth: Int,
      pos_analysis_seconds: Int,
      pos_analysis_sequences: Int,
      request: cask.Request
  ) = {
    val userEmail = getSessionUserEmail(request)
    if (userEmail.isEmpty) {
      noCacheRedirect("/login")
    } else {
      val targetUser = userEmail.get
      val validLang = I18n.validateLang(lang)
      logger.info(
        s"Saving config for $targetUser: $lishogi_nickname, $shogiwars_nickname, $dojo81_nickname, $engine_path, $shallow_limit, $deep_limit, $win_chance_threshold, lang=$validLang"
      )

      val currentSettings = Await.result(SettingsRepository.getAppSettings(userEmail), 5.seconds)
      val settings = currentSettings.copy(
        lishogiNickname = lishogi_nickname,
        shogiwarsNickname = shogiwars_nickname,
        dojo81Nickname = dojo81_nickname,
        enginePath = engine_path,
        shallowLimit = shallow_limit,
        deepLimit = deep_limit,
        winChanceDropThreshold = win_chance_threshold,
        isConfigured = true,
        posAnalysisCandidates = pos_analysis_candidates,
        posAnalysisDepth = pos_analysis_depth,
        posAnalysisSeconds = pos_analysis_seconds,
        posAnalysisSequences = pos_analysis_sequences
      )

      Await.result(SettingsRepository.saveAppSettings(targetUser, settings), 5.seconds)

      cask.Response(
        "",
        statusCode = 302,
        headers = Seq(
          "Location" -> "/database",
          "Set-Cookie" -> s"lang=$validLang; Path=/; SameSite=Strict"
        ) ++ noCacheHeaders
      )
    }
  }

  initialize()
}
