package shogi.puzzler.maintenance.routes

import cask._
import java.net.URLEncoder

object LoginRoutes extends BaseRoutes {
  private def urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

  @cask.get("/login")
  def login(request: cask.Request) = {
    redirectToConfiguredHostIfNeeded(request).getOrElse {
      if (getSessionUserEmail(request).isDefined) {
        noCacheRedirect("/database")
      } else if (!oauthEnabled) {
        val adminEmail = config.getString("app.security.admin-email")
        cask.Response(
          s"""
             |<html>
             |<head><title>Login (No OAuth)</title></head>
             |<body>
             |  <h1>Login (Development Mode)</h1>
             |  <ul>
             |    <li><a href="/login-as?email=${urlEncode(adminEmail)}">Login as Admin ($adminEmail)</a></li>
             |    <li><a href="/login-as?email=test@example.com">Login as Test User (test@example.com)</a></li>
             |  </ul>
             |</body>
             |</html>
             |""".stripMargin,
          headers = Seq("Content-Type" -> "text/html; charset=utf-8") ++ noCacheHeaders
        )
      } else {
        val authUrl =
          s"https://accounts.google.com/o/oauth2/v2/auth?" +
            s"client_id=${urlEncode(oauthClientId)}" +
            s"&redirect_uri=${urlEncode(oauthRedirectUri)}" +
            "&response_type=code" +
            s"&scope=${urlEncode("openid email profile")}" +
            "&prompt=consent"

        noCacheRedirect(authUrl)
      }
    }
  }

  @cask.get("/login-as")
  def loginAs(email: String, request: cask.Request) = {
    if (oauthEnabled) {
      noCacheRedirect("/")
    } else {
      logger.info(s"Logging in as: $email (OAuth disabled)")
      val mockUserJson = ujson.Obj(
        "email" -> email,
        "name" -> email.split("@").head.capitalize,
        "picture" -> ""
      )
      val sessionToken = encodeSession(mockUserJson)
      cask.Response(
        "",
        statusCode = 302,
        headers = Seq("Location" -> "/database") ++ noCacheHeaders,
        cookies = Seq(
          cask.Cookie(
            name = "session",
            value = sessionToken,
            httpOnly = true,
            maxAge = 60 * 60 * 24 * 30,
            path = "/",
            sameSite = "Lax"
          )
        )
      )
    }
  }

  @cask.get("/callback")
  def callback(
                code: String,
                scope: Option[String] = None,
                authuser: Option[String] = None,
                prompt: Option[String] = None,
                hd: Option[String] = None,
                state: Option[String] = None,
                iss: Option[String] = None,
                request: cask.Request = null
              ): cask.Response[String] = {

    Option(request).flatMap(redirectToConfiguredHostIfNeeded).getOrElse {
      val tokenResp = requests.post(
        "https://oauth2.googleapis.com/token",
        data = ujson.Obj(
          "code" -> code,
          "client_id" -> oauthClientId,
          "client_secret" -> oauthClientSecret,
          "redirect_uri" -> oauthRedirectUri,
          "grant_type" -> "authorization_code"
        )
      )

      val accessToken =
        ujson.read(tokenResp.text())("access_token").str

      val userInfoResp = requests.get(
        "https://openidconnect.googleapis.com/v1/userinfo",
        headers = Map("Authorization" -> s"Bearer $accessToken")
      )

      val userJson = ujson.read(userInfoResp.text())
      val email    = userJson.obj.get("email").map(_.str).getOrElse("")

      logger.info(s"User authenticated: email=$email")

      if (!isEmailAllowed(email)) {
        logger.warn(s"Access denied for $email")
        return noCacheRedirect("/")
      }

      val sessionToken = encodeSession(userJson)

      cask.Response(
        "",
        statusCode = 302,
        headers = Seq("Location" -> "/database") ++ noCacheHeaders,
        cookies = Seq(
          cask.Cookie(
            name = "session",
            value = sessionToken,
            httpOnly = true,
            maxAge = 60 * 60 * 24 * 30,
            path = "/",
            sameSite = "Lax"
          )
        )
      )
    }
  }

  @cask.get("/logout")
  def logout(request: cask.Request): cask.Response[String] = {
    redirectToConfiguredHostIfNeeded(request).getOrElse {
      cask.Response(
        "",
        statusCode = 302,
        headers = Seq("Location" -> "/") ++ noCacheHeaders,
        cookies = Seq(cask.Cookie("session", "", maxAge = 0, path = "/"))
      )
    }
  }

  initialize()
}
