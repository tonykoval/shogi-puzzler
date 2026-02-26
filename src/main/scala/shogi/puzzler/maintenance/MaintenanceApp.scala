package shogi.puzzler.maintenance

import cask._
import shogi.puzzler.db.GameRepository
import shogi.puzzler.maintenance.routes._
import scala.concurrent.Await
import scala.concurrent.duration._

object MaintenanceApp extends cask.Main {

  override def main(args: Array[String]): Unit = {
    val n = Await.result(GameRepository.normalizeDates(), 30.seconds)
    if (n > 0) println(s"[DB] Normalized date format in $n game(s)")
    super.main(args)
  }

  override def allRoutes = Seq(
    LoginRoutes,
    AboutRoutes,
    MaintenanceRoutes,
    ViewerRoutes,
    TrainingRoutes,
    PuzzleCreatorRoutes,
    StudyRoutes,
    StudyViewerRoutes,
    ConfigRoutes,
    UserManagementRoutes,
    LangRoutes,
    TranslateRoutes,
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

