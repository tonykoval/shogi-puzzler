package shogi.puzzler.maintenance.routes

import cask._
import shogi.puzzler.i18n.I18n

/** Route for switching the UI language. Sets a lang cookie and redirects back. */
object LangRoutes extends BaseRoutes {

  @cask.get("/set-lang/:lang")
  def setLang(lang: String, request: cask.Request): cask.Response[String] = {
    val validLang = I18n.validateLang(lang)
    val referer = request.headers.get("referer").flatMap(_.headOption).getOrElse("/")
    cask.Response(
      "",
      statusCode = 302,
      headers = Seq(
        "Location" -> referer,
        "Set-Cookie" -> s"lang=$validLang; Path=/; SameSite=Strict",
        "Cache-Control" -> "no-store"
      )
    )
  }

  initialize()
}
