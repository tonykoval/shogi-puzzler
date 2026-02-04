package shogi.puzzler.maintenance

import cask._
import shogi.puzzler.maintenance.routes._

object MaintenanceApp extends cask.Main {
  
  override def allRoutes = Seq(
    LoginRoutes,
    MaintenanceRoutes,
    ViewerRoutes,
    PublicViewerRoutes,
    PuzzleCreatorRoutes,
    RepertoireRoutes,
    ConfigRoutes,
    UserManagementRoutes,
    StaticRoutes,
    RootRoutes
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

