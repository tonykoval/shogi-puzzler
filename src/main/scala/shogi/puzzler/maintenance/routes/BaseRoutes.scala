package shogi.puzzler.maintenance.routes

import cask._
import org.slf4j.LoggerFactory
import ujson.Value
import com.typesafe.config.ConfigFactory
import scala.jdk.CollectionConverters._

abstract class BaseRoutes extends Routes {
  protected val logger = LoggerFactory.getLogger(getClass)
  protected val config = ConfigFactory.load()

  protected val oauthEnabled = config.getBoolean("app.oauth.enabled")
  protected val oauthClientId = config.getString("app.oauth.google.client-id")
  protected val oauthClientSecret = config.getString("app.oauth.google.client-secret")
  protected val oauthRedirectUri = config.getString("app.oauth.google.redirect-uri")
  protected val allowedEmails: Set[String] = {
    config.getStringList("app.security.allowed-emails").asScala.toSet
  }

  protected var sessions = Map[String, Value]()

  protected def getSessionUser(request: cask.Request): Option[Value] = {
    if (!oauthEnabled) {
      Some(ujson.Obj("email" -> "mock@example.com", "name" -> "Mock User"))
    } else {
      request.cookies.get("session").map(_.value).flatMap(sessions.get)
    }
  }

  protected def getSessionUserEmail(request: cask.Request): Option[String] = {
    getSessionUser(request).map(_("email").str)
  }

  protected def corsResponse[T](data: T): cask.Response[T] = {
    cask.Response(data, headers = Seq("Access-Control-Allow-Origin" -> "*"))
  }
}
