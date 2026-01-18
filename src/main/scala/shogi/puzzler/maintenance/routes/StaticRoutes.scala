package shogi.puzzler.maintenance.routes

import cask._

object StaticRoutes extends BaseRoutes {
  @cask.staticResources("/assets")
  def assets() = "assets"

  @cask.staticResources("/js")
  def js() = "js"

  initialize()
}
