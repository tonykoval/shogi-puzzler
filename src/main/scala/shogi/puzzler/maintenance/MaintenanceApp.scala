package shogi.puzzler.maintenance

import cask._
import shogi.puzzler.maintenance.routes._

object MaintenanceApp extends cask.Main {
  
  override def allRoutes = Seq(
    RootRoutes,
    LoginRoutes,
    MaintenanceRoutes,
    ViewerRoutes,
    ConfigRoutes,
    DemoRoutes,
    StaticRoutes
  )

  override def host: String = "0.0.0.0"
  override def port: Int = 8080

  @cask.get("/favicon.ico")
  def favicon() = {
    cask.Response(
      "",
      statusCode = 404
    )
  }
}

object StaticRoutes extends BaseRoutes {
  @cask.staticResources("/assets")
  def assets() = "assets"

  @cask.staticResources("/js")
  def js() = "js"

  initialize()
}

object LoginRoutes extends BaseRoutes {
  initialize()
  @cask.get("/login")
  def login() = {
    logger.info("Redirecting to Google OAuth")

    val authUrl =
      s"https://accounts.google.com/o/oauth2/v2/auth?" +
        s"client_id=$oauthClientId" +
        s"&redirect_uri=$oauthRedirectUri" +
        "&response_type=code" +
        "&scope=openid email profile" +
        "&prompt=consent"

    cask.Redirect(authUrl)
  }

  @cask.get("/callback")
  def callback(
                code: String,
                scope: Option[String] = None,
                authuser: Option[String] = None,
                prompt: Option[String] = None
              ): cask.Response[String] = {

    logger.info(s"OAuth callback: scope=$scope authuser=$authuser prompt=$prompt")

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

    val sessionId = java.util.UUID.randomUUID().toString
    sessions += sessionId -> userJson

    cask.Response(
      "",
      statusCode = 302,
      headers = Seq("Location" -> "/"),
      cookies = Seq(
        cask.Cookie(
          name = "session",
          value = sessionId,
          httpOnly = true
        )
      )
    )
  }

  @cask.get("/logout")
  def logout(request: cask.Request): cask.Response[String] = {
    request.cookies
      .get("session")
      .map(_.value)
      .foreach { sid =>
        logger.info(s"Logout session=$sid")
        sessions -= sid
      }

    cask.Response(
      "",
      statusCode = 302,
      headers = Seq("Location" -> "/"),
      cookies = Seq(cask.Cookie("session", "", maxAge = 0))
    )
  }
}

