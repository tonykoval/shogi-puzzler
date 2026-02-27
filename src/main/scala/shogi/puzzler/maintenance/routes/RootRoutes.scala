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
      noCacheRedirect("/database")
    }
  }

  @cask.get("/my-games-fallback", subpath = true)
  def catchAll(request: cask.Request): cask.Response[String] = {
    val path = request.remainingPathSegments.mkString("/")
    cask.Response(s"Not Found: $path", statusCode = 404)
  }

  initialize()
}
