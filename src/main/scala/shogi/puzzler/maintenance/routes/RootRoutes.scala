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
  def homeRedirect(): cask.Response[String] = {
    cask.Redirect("/maintenance")
  }

  initialize()
}
