package shogi.puzzler.maintenance.routes

import cask._

/**
  * Root endpoints for the maintenance app.
  *
  * Note: `cask.main.Main` only mounts instances of `cask.main.Routes` provided
  * via `allRoutes`, so we keep these endpoints in a dedicated `Routes` object
  * rather than on the `cask.Main` itself.
  */
object RootRoutes extends BaseRoutes {
  @cask.get("/")
  def index(lang: Option[String] = None, request: cask.Request) = {
    redirectToConfiguredHostIfNeeded(request).getOrElse {
      logger.info(s"Root accessed, redirecting to /viewer. Cookies: ${request.cookies.keys.mkString(", ")}")
      val target = lang.map(l => s"/viewer?lang=${java.net.URLEncoder.encode(l, "UTF-8")}").getOrElse("/viewer")
      noCacheRedirect(target)
    }
  }

  @cask.get("/my-games-fallback", subpath = true)
  def catchAll(request: cask.Request): cask.Response[String] = {
    val path = request.remainingPathSegments.mkString("/")
    logger.info(s"Catch-all route: path=$path params=${request.queryParams}")
    cask.Response(s"Not Found: $path", statusCode = 404)
  }

  initialize()
}
