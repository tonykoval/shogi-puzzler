package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{SettingsRepository, AppSettings}
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
      cask.Response(
        renderConfigPage(userEmail, settings).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  def renderConfigPage(userEmail: Option[String], settings: AppSettings) = {
    Components.layout("Configuration", userEmail, settings, appVersion)(
      h1(cls := "mb-4")("Configuration"),
      div(cls := "card bg-dark text-light border-secondary")(
        div(cls := "card-body")(
          form(action := "/config", method := "post")(
            div(cls := "row")(
              div(cls := "col-md-4")(
                Components.configField("Lishogi Nickname", "lishogi_nickname", settings.lishogiNickname)
              ),
              div(cls := "col-md-4")(
                Components.configField("ShogiWars Nickname", "shogiwars_nickname", settings.shogiwarsNickname)
              ),
              div(cls := "col-md-4")(
                Components.configField("81Dojo Nickname", "dojo81_nickname", settings.dojo81Nickname)
              )
            ),
            div(cls := "mb-3")(
              label(cls := "form-label")("Engine"),
              select(name := "engine_path", cls := "form-select bg-dark text-light border-secondary")(
                option(value := "", if (settings.enginePath.isEmpty) selected := "selected" else "")("Select an engine..."),
                listEngines().map { eng =>
                  option(value := eng, if (eng == settings.enginePath) selected := "selected" else "")(eng)
                }
              )
            ),
            div(cls := "row")(
              div(cls := "col-md-4 mb-3")(
                Components.configField("Shallow Analysis Limit", "shallow_limit", settings.shallowLimit.toString, "number")
              ),
              div(cls := "col-md-4 mb-3")(
                Components.configField("Deep Analysis Limit", "deep_limit", settings.deepLimit.toString, "number")
              ),
              div(cls := "col-md-4 mb-3")(
                Components.configField("Win Chance Drop Threshold", "win_chance_threshold", settings.winChanceDropThreshold.toString, "number", Some("any"))
              )
            ),
            button(`type` := "submit", cls := "btn btn-primary w-100 w-md-auto")("Save Configuration"),
            if (settings.shogiwarsNickname == "Tonyko") {
              div(cls := "alert alert-warning mt-3")(
                i(cls := "bi bi-exclamation-triangle-fill me-2"),
                "Note: You are currently using default nicknames. Change them to see your own games."
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
      request: cask.Request
  ) = {
    val userEmail = getSessionUserEmail(request)
    if (userEmail.isEmpty) {
      noCacheRedirect("/login")
    } else {
      val targetUser = userEmail.get
      logger.info(
        s"Saving config for $targetUser: $lishogi_nickname, $shogiwars_nickname, $dojo81_nickname, $engine_path, $shallow_limit, $deep_limit, $win_chance_threshold"
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
        isConfigured = true
      )
      
      Await.result(SettingsRepository.saveAppSettings(targetUser, settings), 5.seconds)
      
      noCacheRedirect("/my-games")
    }
  }

  initialize()
}
