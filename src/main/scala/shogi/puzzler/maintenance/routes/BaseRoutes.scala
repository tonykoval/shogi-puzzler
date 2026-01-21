package shogi.puzzler.maintenance.routes

import cask._
import org.slf4j.LoggerFactory
import ujson.Value
import com.typesafe.config.ConfigFactory
import scala.jdk.CollectionConverters._
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.nio.charset.StandardCharsets
import java.net.URI

object BaseRoutes {
  private val logger = LoggerFactory.getLogger(getClass)
  private val config = ConfigFactory.load()

  private val sessionSecret: String = {
    val s = if (config.hasPath("app.security.session-secret")) config.getString("app.security.session-secret")
            else config.getString("app.oauth.google.client-secret")
    logger.info(s"Global Session secret initialized (hash: ${s.hashCode})")
    s
  }

  private val hmacKey = new SecretKeySpec(sessionSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")

  def sign(data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(hmacKey)
    Base64.getUrlEncoder.withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)))
  }
}

abstract class BaseRoutes extends Routes {
  protected val logger = LoggerFactory.getLogger(getClass)
  protected val config = ConfigFactory.load()

  protected val oauthEnabled = config.getBoolean("app.oauth.enabled")
  protected val oauthClientId = config.getString("app.oauth.google.client-id")
  protected val oauthClientSecret = config.getString("app.oauth.google.client-secret")
  protected val oauthRedirectUri = config.getString("app.oauth.google.redirect-uri")
  protected val allowedEmails: Set[String] = {
    val raw = config.getString("app.security.allowed-emails")
    val emails = raw.split(",").map(_.trim).filter(_.nonEmpty).toSet
    logger.info(s"[AUTH] Allowed emails: ${emails.mkString(", ")}")
    emails
  }

  protected val noCacheHeaders = Seq(
    "Cache-Control" -> "no-store, no-cache, must-revalidate, max-age=0",
    "Pragma" -> "no-cache"
  )

  private def defaultPortForScheme(scheme: String): Int =
    if (scheme.equalsIgnoreCase("https")) 443 else 80

  protected def noCacheRedirect(location: String, statusCode: Int = 302): cask.Response[String] = {
    cask.Response(
      "",
      statusCode = statusCode,
      headers = Seq("Location" -> location) ++ noCacheHeaders
    )
  }

  protected def redirectToConfiguredHostIfNeeded(request: cask.Request): Option[cask.Response[String]] = {
    if (!oauthEnabled) return None
    val incomingHostHeader = request.headers.get("host").flatMap(_.headOption)
    val targetUri = new URI(oauthRedirectUri)
    val targetHost = Option(targetUri.getHost).getOrElse("")
    val targetScheme = Option(targetUri.getScheme).getOrElse("http")
    val targetPort = if (targetUri.getPort == -1) defaultPortForScheme(targetScheme) else targetUri.getPort

    val incomingMatchesTarget = incomingHostHeader.exists { h =>
      val parts = h.split(":", 2)
      val inHost = parts.headOption.getOrElse("")
      val inPort = parts.lift(1).flatMap(p => scala.util.Try(p.toInt).toOption).getOrElse(defaultPortForScheme(targetScheme))
      inHost.equalsIgnoreCase(targetHost) && inPort == targetPort
    }

    if (targetHost.isEmpty || incomingMatchesTarget) None
    else {
      val portPart = if (targetUri.getPort == -1) "" else s":${targetUri.getPort}"
      val path = Option(request.exchange.getRequestURI).getOrElse("")
      val query = Option(request.exchange.getQueryString).filter(_.nonEmpty).map(q => s"?$q").getOrElse("")
      Some(noCacheRedirect(s"$targetScheme://$targetHost$portPart$path$query"))
    }
  }

  protected def encodeSession(userJson: Value): String = {
    val payload = Base64.getUrlEncoder.withoutPadding().encodeToString(ujson.write(userJson).getBytes(StandardCharsets.UTF_8))
    val signature = BaseRoutes.sign(payload)
    logger.info(s"Encoding session: payload=$payload, signature=$signature")
    s"$payload.$signature"
  }

  protected def decodeSession(token: String): Option[Value] = {
    token.split("\\.", 2) match {
      case Array(payload, signature) =>
        val expectedSignature = BaseRoutes.sign(payload)
        if (expectedSignature == signature) {
          val jsonStr = new String(Base64.getUrlDecoder.decode(payload), StandardCharsets.UTF_8)
          Some(ujson.read(jsonStr))
        } else {
          logger.warn(s"Session signature mismatch! Expected: $expectedSignature, Got: $signature")
          None
        }
      case _ => 
        logger.warn(s"Invalid session token format: $token")
        None
    }
  }

  protected def getSessionUser(request: cask.Request): Option[Value] = {
    val sessionCookie = request.cookies.get("session")
    if (sessionCookie.isEmpty) {
      val allCookies = request.exchange.getRequestHeaders.get("Cookie")
      val host = request.headers.get("host").flatMap(_.headOption).getOrElse("unknown")
      logger.info(s"No session cookie found in request to ${request.exchange.getRequestPath} (Host: $host). Raw Cookie headers: $allCookies")
    }
    sessionCookie.map(_.value).flatMap(decodeSession)
  }

  protected def getSessionUserEmail(request: cask.Request): Option[String] = {
    getSessionUser(request).map(_("email").str)
  }

  protected def corsResponse[T](data: T): cask.Response[T] = {
    cask.Response(data, headers = Seq("Access-Control-Allow-Origin" -> "*"))
  }
}
