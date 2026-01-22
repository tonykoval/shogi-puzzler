package shogi.puzzler.maintenance.routes

import cask._
import java.net.URLEncoder

object LoginRoutes extends BaseRoutes {
  private def urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

  @cask.get("/login")
  def login(request: cask.Request) = {
    redirectToConfiguredHostIfNeeded(request).getOrElse {
      val host = request.headers.get("host").flatMap(_.headOption).getOrElse("unknown")
      logger.info(s"Login requested (Host: $host). Current user: ${getSessionUserEmail(request)}")
      if (oauthEnabled && getSessionUserEmail(request).isDefined) {
        logger.info("User already logged in, redirecting to /my-games")
        noCacheRedirect("/my-games")
      } else {
        logger.info(s"Redirecting to Google OAuth from $host")

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

  @cask.get("/callback")
  def callback(
                code: String,
                scope: Option[String] = None,
                authuser: Option[String] = None,
                prompt: Option[String] = None,
                hd: Option[String] = None,
                state: Option[String] = None,
                request: cask.Request = null
              ): cask.Response[String] = {

    Option(request).flatMap(redirectToConfiguredHostIfNeeded).getOrElse {
      logger.info(s"OAuth callback: scope=$scope authuser=$authuser prompt=$prompt hd=$hd state=$state")
      if (request != null) {
        logger.info(s"Full callback URL: ${request.exchange.getRequestURI}")
        logger.info(s"Query Params: ${request.queryParams}")
      }

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

      if (!allowedEmails.contains(email)) {
        logger.info(s"❌ Access denied for $email")

        return cask.Response(
          s"""
          <h1>403 - Forbidden</h1>
          <p>User <b>$email</b> is not authorized.</p>
          <a href="/">Back to Home</a>
          """,
          statusCode = 403,
          headers = Seq("Content-Type" -> "text/html; charset=utf-8")
        )
      }

      logger.info(s"✅ Access granted for $email")
      val sessionToken = encodeSession(userJson)
      val host = Option(request).flatMap(_.headers.get("host")).flatMap(_.headOption).getOrElse("localhost")
      logger.info(s"Setting session cookie for host $host: $sessionToken")

      cask.Response(
        "",
        statusCode = 302,
        headers = Seq("Location" -> "/my-games") ++ noCacheHeaders,
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
      request.cookies
        .get("session")
        .map(_.value)
        .foreach { sid =>
          logger.info(s"Logout session=$sid")
        }

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
