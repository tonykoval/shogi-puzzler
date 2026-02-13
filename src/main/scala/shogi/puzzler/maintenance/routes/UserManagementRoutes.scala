package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{UserRepository, User, SettingsRepository}
import shogi.puzzler.ui.Components
import scala.concurrent.Await
import scala.concurrent.duration._

object UserManagementRoutes extends BaseRoutes {
  private val allAvailablePages = Seq(
    "*" -> "All Pages",
    "my-games" -> "My Games",
    "repertoire" -> "Repertoire",
    "viewer" -> "Puzzles",
    "puzzle-creator" -> "Puzzle Editor",
    "config" -> "Configuration",
    "ocr" -> "OCR Library",
    "admin/users" -> "User Management"
  )

  @cask.get("/admin/users")
  def usersPage(request: cask.Request) = {
    withAuth(request, "admin/users") { email =>
      val users = Await.result(UserRepository.getAllUsers(), 10.seconds)
      val settings = Await.result(SettingsRepository.getAppSettings(Some(email)), 10.seconds)
      cask.Response(
        renderUsersPage(Some(email), users, settings).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  def renderUsersPage(userEmail: Option[String], users: Seq[User], settings: shogi.puzzler.db.AppSettings) = {
    val adminEmail = config.getString("app.security.admin-email")
    val globalSettings = Await.result(SettingsRepository.getAppSettings(Some("global")), 10.seconds)
    val select2Script = script(raw(
      """
        |$(document).ready(function() {
        |    $('.select2-pages').select2({
        |        theme: 'bootstrap-5',
        |        width: '100%',
        |        placeholder: 'Select pages...',
        |        closeOnSelect: false
        |    });
        |});
        |""".stripMargin))

    Components.layout("User Management", userEmail, settings, appVersion, scripts = Seq(select2Script))(
      h1(cls := "mb-4")("User Management"),
      div(cls := "card bg-dark text-light border-secondary mb-4")(
        div(cls := "card-header border-secondary")("Global Settings"),
        div(cls := "card-body")(
          form(action := "/admin/settings/update", method := "post", cls := "row g-3")(
            div(cls := "col-md-4")(
              label(cls := "form-label")("Analysis Workers"),
              input(`type` := "number", name := "analysis_workers", value := globalSettings.analysisWorkers.toString, cls := "form-control bg-dark text-light border-secondary", required := true)
            ),
            div(cls := "col-md-2 d-flex align-items-end")(
              button(`type` := "submit", cls := "btn btn-primary w-100")("Save Global Settings")
            )
          )
        )
      ),
      div(cls := "card bg-dark text-light border-secondary mb-4")(
        div(cls := "card-header border-secondary")("Add New User"),
        div(cls := "card-body")(
          form(action := "/admin/users/add", method := "post", cls := "row g-3")(
            div(cls := "col-md-4")(
              label(cls := "form-label")("Email"),
              input(`type` := "email", name := "email", cls := "form-control bg-dark text-light border-secondary", required := true)
            ),
            div(cls := "col-md-2")(
              label(cls := "form-label")("Role"),
              select(name := "role", cls := "form-select bg-dark text-light border-secondary")(
                option(value := "USER")("User"),
                option(value := "ADMIN")("Admin")
              )
            ),
            div(cls := "col-md-4")(
              label(cls := "form-label")("Allowed Pages"),
              select(name := "pages", cls := "form-select bg-dark text-light border-secondary select2-pages", attr("multiple") := "multiple")(
                allAvailablePages.map { case (v, l) =>
                  option(value := v, if (v == "*") attr("selected") := "selected" else "")(l)
                }
              )
            ),
            div(cls := "col-md-2 d-flex align-items-end")(
              button(`type` := "submit", cls := "btn btn-primary w-100")("Add User")
            )
          )
        )
      ),
      div(cls := "card bg-dark text-light border-secondary")(
        div(cls := "card-header border-secondary")("Whitelisted Users"),
        div(cls := "table-responsive")(
          table(cls := "table table-dark table-hover mb-0")(
            thead(
              tr(
                th("Email"),
                th("Role"),
                th("Allowed Pages"),
                th("Actions")
              )
            ),
            tbody(
              users.map { u =>
                tr(
                  td(u.email),
                  td(scalatags.Text.all.span(cls := s"badge ${if (u.role == "ADMIN") "bg-danger" else "bg-info"}")(u.role)),
                  td(
                    form(action := "/admin/users/update", method := "post", cls := "row g-2 align-items-center")(
                      input(`type` := "hidden", name := "email", value := u.email),
                      div(cls := "col-8")(
                        select(name := "pages", cls := "form-select form-select-sm bg-dark text-light border-secondary select2-pages", attr("multiple") := "multiple", 
                               if (u.role == "ADMIN" && u.email == adminEmail) attr("disabled") := "disabled" else ""
                        )(
                          allAvailablePages.map { case (v, l) =>
                            option(value := v, if (u.allowedPages.contains(v)) attr("selected") := "selected" else "")(l)
                          }
                        )
                      ),
                      div(cls := "col-4")(
                        if (u.role != "ADMIN" || u.email != adminEmail) {
                          button(`type` := "submit", cls := "btn btn-sm btn-outline-success", title := "Save Changes")(i(cls := "bi bi-check-lg"))
                        } else ""
                      )
                    )
                  ),
                  td(
                    if (u.role != "ADMIN" || u.email != adminEmail) {
                      form(action := "/admin/users/delete", method := "post", style := "display: inline;")(
                        input(`type` := "hidden", name := "email", value := u.email),
                        button(`type` := "submit", cls := "btn btn-sm btn-outline-danger", onclick := "return confirm('Are you sure?')")(i(cls := "bi bi-trash"))
                      )
                    } else "System Admin"
                  )
                )
              }
            )
          )
        )
      )
    )
  }

  @cask.postForm("/admin/users/update")
  def updateUser(email: String, pages: Seq[String], request: cask.Request) = {
    val userEmail = getSessionUserEmail(request)
    if (userEmail.exists(e => getUserRole(e).contains("ADMIN"))) {
      val existingUser = Await.result(UserRepository.getUser(email), 10.seconds)
      existingUser match {
        case Some(u) =>
          val allowedPages = pages.flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty).toSet
          Await.result(UserRepository.saveUser(u.copy(allowedPages = allowedPages)), 10.seconds)
          noCacheRedirect("/admin/users")
        case None =>
          cask.Response("User not found", statusCode = 404)
      }
    } else {
      noCacheRedirect("/")
    }
  }

  @cask.postForm("/admin/settings/update")
  def updateSettings(analysis_workers: Int, request: cask.Request) = {
    val userEmail = getSessionUserEmail(request)
    if (userEmail.exists(e => getUserRole(e).contains("ADMIN"))) {
      val globalSettings = Await.result(SettingsRepository.getAppSettings(Some("global")), 10.seconds)
      val updatedSettings = globalSettings.copy(analysisWorkers = analysis_workers)
      Await.result(SettingsRepository.saveAppSettings("global", updatedSettings), 10.seconds)
      noCacheRedirect("/admin/users")
    } else {
      noCacheRedirect("/")
    }
  }

  @cask.postForm("/admin/users/add")
  def addUser(email: String, role: String, pages: Seq[String], request: cask.Request) = {
    val userEmail = getSessionUserEmail(request)
    if (userEmail.exists(e => getUserRole(e).contains("ADMIN"))) {
      val allowedPages = pages.flatMap(_.split(",")).map(_.trim).filter(_.nonEmpty).toSet
      Await.result(UserRepository.saveUser(User(email, role, allowedPages)), 10.seconds)
      noCacheRedirect("/admin/users")
    } else {
      noCacheRedirect("/")
    }
  }

  @cask.postForm("/admin/users/delete")
  def deleteUser(email: String, request: cask.Request) = {
    val userEmail = getSessionUserEmail(request)
    if (userEmail.exists(e => getUserRole(e).contains("ADMIN"))) {
      Await.result(UserRepository.deleteUser(email), 10.seconds)
      noCacheRedirect("/admin/users")
    } else {
      noCacheRedirect("/")
    }
  }

  initialize()
}
