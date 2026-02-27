package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{StudyRepository, SettingsRepository, AppSettings}
import shogi.puzzler.game.GameLoader
import shogi.puzzler.i18n.I18n
import shogi.puzzler.ui.Components
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object StudyRoutes extends BaseRoutes {

  // Cache for two-phase Lishogi study import: key -> (chapters, sourceAuthor, studyUrl)
  private val studyCache = new java.util.concurrent.ConcurrentHashMap[String, (Seq[(String, String, Option[String], Option[String])], Option[String], String)]()

  private def withOwnership(id: String, email: String)(f: org.mongodb.scala.Document => cask.Response[ujson.Value]): cask.Response[ujson.Value] = {
    val repertoire = Await.result(StudyRepository.getStudy(id), 10.seconds)
    repertoire match {
      case Some(rep) =>
        val ownerEmail = rep.get("ownerEmail").map(_.asString().getValue).getOrElse("")
        if (ownerEmail != email) {
          cask.Response(ujson.Obj("error" -> "Forbidden"), statusCode = 403)
        } else {
          f(rep)
        }
      case None =>
        cask.Response(ujson.Obj("error" -> "Not Found"), statusCode = 404)
    }
  }

  @cask.get("/study")
  def index(request: cask.Request) = {
    withAuth(request, "repertoire") { email =>
      val userEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      val pageLang = getLang(request)
      val repertoires = Await.result(StudyRepository.getStudies(userEmail), 10.seconds)

      cask.Response(
        renderStudyPage(userEmail, settings, repertoires, pageLang).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  def renderStudyPage(userEmail: Option[String], settings: AppSettings, repertoires: Seq[org.mongodb.scala.Document], pageLang: String = I18n.defaultLang) = {
    // Group studies: those with studyUrl grouped together, standalone ones separate
    val (studyReps, standaloneReps) = repertoires.partition(_.get("studyUrl").exists(v => v.isString && v.asString().getValue.nonEmpty))
    val studyGroups = studyReps.groupBy(_.get("studyUrl").map(_.asString().getValue).getOrElse("")).toSeq.sortBy(_._1)

    Components.layout(
      "Shogi Study",
      userEmail,
      settings,
      appVersion,
      lang = pageLang,
      scripts = Seq(
        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        script(`type` := "module", src := "/js/study.js")
      )
    )(
      div(cls := "d-flex justify-content-between align-items-center mb-4")(
        h1("My Studies"),
        div(
          button(cls := "btn btn-primary", attr("data-bs-toggle") := "modal", attr("data-bs-target") := "#createStudyModal")("Create New"),
          button(cls := "btn btn-success ms-2", attr("data-bs-toggle") := "modal", attr("data-bs-target") := "#importKifModal")("Import KIF"),
          button(cls := "btn btn-info ms-2", attr("data-bs-toggle") := "modal", attr("data-bs-target") := "#importLishogiStudyModal")("Import Lishogi Study")
        )
      ),

      // Filter bar
      div(cls := "card bg-dark border-secondary mb-4")(
        div(cls := "card-body py-2")(
          div(cls := "d-flex align-items-center gap-3 flex-wrap")(
            div(cls := "flex-grow-1", style := "max-width: 300px;")(
              input(`type` := "text", id := "studySearch", cls := "form-control form-control-sm bg-dark text-light border-secondary", placeholder := "Search studies...", attr("oninput") := "filterStudies()")
            ),
            div(cls := "btn-group btn-group-sm")(
              button(cls := "btn btn-outline-light active", attr("data-filter") := "all", onclick := "setSourceFilter(this)")("All"),
              button(cls := "btn btn-outline-light", attr("data-filter") := "lishogi", onclick := "setSourceFilter(this)")("Lishogi Study"),
              button(cls := "btn btn-outline-light", attr("data-filter") := "manual", onclick := "setSourceFilter(this)")("Manual / KIF")
            )
          )
        )
      ),

      div(id := "study-list")(
        // Study groups
        studyGroups.map { case (studyUrl, reps) =>
          val firstRep = reps.head
          val studyName = firstRep.get("studyName").map(_.asString().getValue).getOrElse {
            // Fallback: use first rep's name
            firstRep.getString("name")
          }
          val author = firstRep.get("sourceAuthor").map(_.asString().getValue).getOrElse("")
          val groupId = studyUrl.hashCode.toHexString

          div(cls := "card bg-dark text-light border-secondary mb-3 study-group", attr("data-source") := "lishogi")(
            div(cls := "card-header d-flex align-items-center py-2", attr("data-bs-toggle") := "collapse", attr("data-bs-target") := s"#study-$groupId", role := "button", style := "cursor: pointer;")(
              i(cls := "bi bi-book me-2 text-info"),
              tag("span")(cls := "fw-semibold me-2")(studyName),
              tag("span")(cls := "badge bg-secondary me-2")(s"${reps.size} chapters"),
              if (author.nonEmpty) frag(
                tag("span")(cls := "text-muted small me-2")(
                  "by ",
                  a(href := s"https://lishogi.org/@/$author", target := "_blank", cls := "text-muted text-decoration-none")(author)
                )
              ) else frag(),
              a(href := studyUrl, target := "_blank", cls := "text-muted small ms-auto me-3", onclick := "event.stopPropagation();")(
                i(cls := "bi bi-box-arrow-up-right me-1"), "Lishogi"
              ),
              button(cls := "btn btn-sm btn-outline-warning me-1", onclick := s"reloadStudyGroup('${reps.map(r => r.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")).mkString(",")}'); event.stopPropagation();", title := "Reload all chapters from Lishogi")(
                i(cls := "bi bi-arrow-repeat")
              ),
              button(cls := "btn btn-sm btn-outline-danger", onclick := s"deleteStudyGroup('${reps.map(r => r.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")).mkString(",")}'); event.stopPropagation();", title := "Delete all chapters")(
                i(cls := "bi bi-trash")
              )
            ),
            div(cls := "collapse show", id := s"study-$groupId")(
              div(cls := "list-group list-group-flush")(
                reps.map { rep =>
                  val id = rep.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
                  val repName = rep.getString("name")
                  val isPublic = rep.get("is_public").exists(_.asBoolean().getValue)

                  div(cls := "list-group-item bg-dark text-light border-secondary d-flex align-items-center py-2 study-item", attr("data-name") := repName.toLowerCase)(
                    a(href := s"/study/$id", cls := "text-light text-decoration-none flex-grow-1")(
                      repName
                    ),
                    if (isPublic) tag("span")(cls := "badge bg-success me-2")("Public") else frag(),
                    div(cls := "btn-group btn-group-sm")(
                      a(href := s"/study/$id", cls := "btn btn-outline-info btn-sm", title := "Open")(i(cls := "bi bi-pencil")),
                      if (isPublic) frag(
                        button(cls := "btn btn-outline-success btn-sm", onclick := s"toggleStudyPublic('$id', false)", title := "Make private")(i(cls := "bi bi-globe")),
                        a(href := s"/study-viewer/$id", cls := "btn btn-outline-light btn-sm", target := "_blank", title := "Public viewer")(i(cls := "bi bi-eye"))
                      ) else {
                        button(cls := "btn btn-outline-secondary btn-sm", onclick := s"toggleStudyPublic('$id', true)", title := "Make public")(i(cls := "bi bi-lock"))
                      },
                      button(cls := "btn btn-outline-danger btn-sm", onclick := s"deleteStudy('$id')", title := "Delete")(i(cls := "bi bi-trash"))
                    )
                  )
                }
              )
            )
          )
        },

        // Standalone studies
        div(cls := "row")(
          standaloneReps.map { rep =>
            val id = rep.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
            val name = rep.getString("name")
            val isAutoReload = rep.get("isAutoReload").exists(_.asBoolean().getValue)
            val isPublic = rep.get("is_public").exists(_.asBoolean().getValue)
            val reloadColor = rep.get("reloadColor").map(_.asString().getValue).getOrElse("")
            val hasSourceUrl = rep.get("sourceUrl").exists(v => v.isString && v.asString().getValue.nonEmpty)
            val sourceType = if (hasSourceUrl) "lishogi" else "manual"

            div(cls := "col-md-4 mb-3 study-item", attr("data-source") := sourceType, attr("data-name") := name.toLowerCase)(
              div(cls := "card bg-dark text-light border-secondary")(
                div(cls := "card-body py-2")(
                  div(cls := "d-flex align-items-center mb-2")(
                    h6(cls := "card-title mb-0 flex-grow-1")(
                      name,
                      if (isAutoReload && reloadColor.nonEmpty) tag("span")(cls := "badge bg-secondary ms-2")(reloadColor.capitalize) else frag(),
                      if (isAutoReload) tag("span")(cls := "badge bg-warning text-dark ms-2")("Auto") else frag(),
                      if (isPublic) tag("span")(cls := "badge bg-success ms-2")("Public") else frag()
                    )
                  ),
                  div(cls := "btn-group btn-group-sm")(
                    a(href := s"/study/$id", cls := "btn btn-outline-info", title := "Open")(i(cls := "bi bi-pencil me-1"), "Open"),
                    if (isAutoReload) {
                      button(cls := "btn btn-outline-warning", onclick := s"reloadStudy('$id')", title := "Reload from games")(i(cls := "bi bi-arrow-repeat"))
                    } else frag(),
                    if (isPublic) frag(
                      button(cls := "btn btn-outline-success", onclick := s"toggleStudyPublic('$id', false)", title := "Make private")(i(cls := "bi bi-globe")),
                      a(href := s"/study-viewer/$id", cls := "btn btn-outline-light", target := "_blank", title := "Public viewer")(i(cls := "bi bi-eye"))
                    ) else {
                      button(cls := "btn btn-outline-secondary", onclick := s"toggleStudyPublic('$id', true)", title := "Make public")(i(cls := "bi bi-lock"))
                    },
                    button(cls := "btn btn-outline-danger", onclick := s"deleteStudy('$id')", title := "Delete")(i(cls := "bi bi-trash"))
                  )
                )
              )
            )
          }
        )
      ),

      // Create Modal
      div(cls := "modal fade", id := "createStudyModal", tabindex := "-1")(
        div(cls := "modal-dialog")(
          div(cls := "modal-content bg-dark text-light border-secondary")(
            div(cls := "modal-header")(
              h5(cls := "modal-title")("New Study"),
              button(`type` := "button", cls := "btn-close btn-close-white", attr("data-bs-dismiss") := "modal")()
            ),
            div(cls := "modal-body")(
              div(cls := "mb-3")(
                label(cls := "form-label")("Name"),
                input(`type` := "text", id := "studyName", cls := "form-control bg-dark text-light border-secondary")
              ),
              div(cls := "mb-3 form-check")(
                input(`type` := "checkbox", id := "isAutoReload", cls := "form-check-input"),
                label(cls := "form-check-label", attr("for") := "isAutoReload")("Auto-reload from my games")
              ),
              div(id := "autoReloadSettings", style := "display: none;")(
                div(cls := "mb-3")(
                  label(cls := "form-label")("Player Color"),
                  select(id := "reloadColor", cls := "form-select bg-dark text-light border-secondary")(
                    option(value := "sente")("Sente (Black)"),
                    option(value := "gote")("Gote (White)")
                  )
                ),
                div(cls := "mb-3")(
                  label(cls := "form-label")("Reload Threshold (cp drop)"),
                  input(`type` := "number", id := "reloadThreshold", cls := "form-control bg-dark text-light border-secondary", value := "200")
                )
              )
            ),
            div(cls := "modal-footer")(
              button(`type` := "button", cls := "btn btn-secondary", attr("data-bs-dismiss") := "modal")("Cancel"),
              button(`type` := "button", cls := "btn btn-primary", onclick := "createStudy()")("Create")
            )
          )
        )
      ),

      // Import KIF Modal
      div(cls := "modal fade", id := "importKifModal", tabindex := "-1")(
        div(cls := "modal-dialog")(
          div(cls := "modal-content bg-dark text-light border-secondary")(
            div(cls := "modal-header")(
              h5(cls := "modal-title")("Import Study from KIF"),
              button(`type` := "button", cls := "btn-close btn-close-white", attr("data-bs-dismiss") := "modal")()
            ),
            div(cls := "modal-body")(
              div(cls := "mb-3")(
                label(cls := "form-label")("KIF File"),
                input(`type` := "file", id := "kifFile", cls := "form-control bg-dark text-light border-secondary", attr("accept") := ".kif,.kifu")
              ),
              div(cls := "mb-3")(
                label(cls := "form-label")("Study Name"),
                input(`type` := "text", id := "kifStudyName", cls := "form-control bg-dark text-light border-secondary", placeholder := "Auto-filled from filename")
              )
            ),
            div(cls := "modal-footer")(
              button(`type` := "button", cls := "btn btn-secondary", attr("data-bs-dismiss") := "modal")("Cancel"),
              button(`type` := "button", cls := "btn btn-success", onclick := "importKifStudy()")("Import")
            )
          )
        )
      ),

      // Import Lishogi Study Modal
      div(cls := "modal fade", id := "importLishogiStudyModal", tabindex := "-1")(
        div(cls := "modal-dialog")(
          div(cls := "modal-content bg-dark text-light border-secondary")(
            div(cls := "modal-header")(
              h5(cls := "modal-title")("Import Lishogi Study"),
              button(`type` := "button", cls := "btn-close btn-close-white", attr("data-bs-dismiss") := "modal")()
            ),
            div(cls := "modal-body")(
              div(cls := "mb-3")(
                label(cls := "form-label")("Lishogi Study URL"),
                input(`type` := "text", id := "lishogiStudyUrl", cls := "form-control bg-dark text-light border-secondary", placeholder := "https://lishogi.org/study/...")
              ),
              p(cls := "text-muted small")("Enter a Lishogi study URL. If the URL includes a chapter ID, only that chapter will be imported. Otherwise, all chapters will be imported as separate studies."),
              div(id := "importStudyProgress", style := "display:none")(
                div(cls := "mb-2")(tag("small")(id := "importStudyStatus")("Preparing...")),
                div(cls := "progress")(
                  div(cls := "progress-bar", id := "importStudyBar", role := "progressbar", style := "width:0%")
                )
              )
            ),
            div(cls := "modal-footer")(
              button(`type` := "button", cls := "btn btn-secondary", attr("data-bs-dismiss") := "modal")("Cancel"),
              button(`type` := "button", cls := "btn btn-info", id := "importLishogiStudyBtn", onclick := "importLishogiStudy()")("Import")
            )
          )
        )
      )
    )
  }

  @cask.post("/study/create")
  def create(request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      try {
        val json = ujson.read(request.text())
        val name = json("name").str
        val isAutoReload = json.obj.get("isAutoReload").map(_.bool).getOrElse(false)
        val reloadThreshold = json.obj.get("reloadThreshold").map(_.num.toInt).getOrElse(200)
        val reloadColor = json.obj.get("reloadColor").map(_.str)
        logger.info(s"Creating repertoire with name: $name, isAutoReload: $isAutoReload, reloadThreshold: $reloadThreshold, reloadColor: $reloadColor")
        val id = Await.result(StudyRepository.createStudy(name, Some(email), isAutoReload, reloadThreshold, reloadColor), 10.seconds)
        logger.info(s"Repertoire created with id: $id")
        cask.Response(ujson.Obj("id" -> id), headers = Seq("Content-Type" -> "application/json"))
      } catch {
        case e: Exception =>
          logger.error("Error creating repertoire", e)
          cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500)
      }
    }
  }

  @cask.post("/study/create-from-lishogi-study")
  def createFromLishogiStudy(request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      try {
        val json = ujson.read(request.text())
        val url = json("url").str

        // Parse study ID and optional chapter ID from URL
        val urlPattern = """https?://lishogi\.org/study/([a-zA-Z0-9]+)(?:/([a-zA-Z0-9]+))?""".r
        val (studyId, chapterIdOpt) = url match {
          case urlPattern(sid, cid) => (sid, Option(cid))
          case _ => throw new Exception("Invalid Lishogi study URL. Expected: https://lishogi.org/study/{studyId} or https://lishogi.org/study/{studyId}/{chapterId}")
        }

        // Fetch KIF from Lishogi
        val kifUrl = chapterIdOpt match {
          case Some(cid) => s"https://lishogi.org/study/$studyId/$cid.kif"
          case None => s"https://lishogi.org/study/$studyId.kif"
        }
        logger.info(s"Fetching Lishogi study KIF from: $kifUrl")
        val kifResponse = requests.get(kifUrl)
        if (kifResponse.statusCode != 200) {
          throw new Exception(s"Failed to fetch study from Lishogi (HTTP ${kifResponse.statusCode})")
        }
        val fullKif = kifResponse.text()

        // Extract author (ownerId) from Lishogi study page
        val studyUrl = s"https://lishogi.org/study/$studyId"
        val sourceAuthor: Option[String] = try {
          val pageResponse = requests.get(studyUrl)
          val ownerPattern = """"ownerId"\s*:\s*"([^"]+)"""".r
          ownerPattern.findFirstMatchIn(pageResponse.text()).map(_.group(1))
        } catch {
          case _: Exception =>
            logger.warn(s"Could not fetch author from Lishogi study page: $studyUrl")
            None
        }
        logger.info(s"Study author: ${sourceAuthor.getOrElse("unknown")}")

        // Split multi-chapter KIF by 棋戦： header lines
        val chapters = splitMultiChapterKif(fullKif)
        logger.info(s"Found ${chapters.size} chapter(s) in study")

        val results = chapters.zipWithIndex.map { case ((chapterName, kifContent, chapterSourceUrl, chapterStudyName), idx) =>
          val name = if (chapterName.nonEmpty) chapterName else s"Chapter ${idx + 1}"
          val sName = chapterStudyName.filter(_.nonEmpty)
          val (rootSfen, moves, initialComment) = GameLoader.parseKifTreeWithInitialComment(kifContent)
          val id = Await.result(StudyRepository.createStudy(name, Some(email), rootSfen = Some(rootSfen), rootComment = initialComment, sourceUrl = chapterSourceUrl, sourceAuthor = sourceAuthor, studyUrl = Some(studyUrl), studyName = sName), 10.seconds)

          val addFuture = moves.foldLeft(scala.concurrent.Future.successful(())) { case (prev, (parentSfen, usi, nextSfen, comment)) =>
            prev.flatMap(_ => StudyRepository.addMove(id, parentSfen, usi, nextSfen, comment, None))
          }
          Await.result(addFuture, 120.seconds)

          logger.info(s"Chapter '$name' created: id=$id, moves=${moves.size}")
          (id, name, moves.size)
        }

        cask.Response(
          ujson.Obj(
            "ids" -> ujson.Arr(results.map(r => ujson.Str(r._1)): _*),
            "chapters" -> ujson.Arr(results.map(r => ujson.Obj("name" -> r._2, "moveCount" -> r._3)): _*)
          ),
          headers = Seq("Content-Type" -> "application/json")
        )
      } catch {
        case e: Exception =>
          logger.error("Error creating repertoire from Lishogi study", e)
          cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500)
      }
    }
  }

  @cask.post("/study/prepare-lishogi-study")
  def prepareLishogiStudy(request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { _ =>
      try {
        val json = ujson.read(request.text())
        val url = json("url").str

        val urlPattern = """https?://lishogi\.org/study/([a-zA-Z0-9]+)(?:/([a-zA-Z0-9]+))?""".r
        val (studyId, chapterIdOpt) = url match {
          case urlPattern(sid, cid) => (sid, Option(cid))
          case _ => throw new Exception("Invalid Lishogi study URL. Expected: https://lishogi.org/study/{studyId} or https://lishogi.org/study/{studyId}/{chapterId}")
        }

        val kifUrl = chapterIdOpt match {
          case Some(cid) => s"https://lishogi.org/study/$studyId/$cid.kif"
          case None => s"https://lishogi.org/study/$studyId.kif"
        }
        logger.info(s"Preparing Lishogi study KIF from: $kifUrl")
        val kifResponse = requests.get(kifUrl)
        if (kifResponse.statusCode != 200) {
          throw new Exception(s"Failed to fetch study from Lishogi (HTTP ${kifResponse.statusCode})")
        }
        val fullKif = kifResponse.text()

        val studyUrl = s"https://lishogi.org/study/$studyId"
        val sourceAuthor: Option[String] = try {
          val pageResponse = requests.get(studyUrl)
          val ownerPattern = """"ownerId"\s*:\s*"([^"]+)"""".r
          ownerPattern.findFirstMatchIn(pageResponse.text()).map(_.group(1))
        } catch {
          case _: Exception =>
            logger.warn(s"Could not fetch author from Lishogi study page: $studyUrl")
            None
        }

        val chapters = splitMultiChapterKif(fullKif)
        logger.info(s"Prepared ${chapters.size} chapter(s) for study $studyId")

        val key = java.util.UUID.randomUUID().toString
        studyCache.put(key, (chapters, sourceAuthor, studyUrl))

        // Schedule cache cleanup after 10 minutes
        val cleanupThread = new Thread(() => {
          Thread.sleep(10 * 60 * 1000)
          studyCache.remove(key)
        })
        cleanupThread.setDaemon(true)
        cleanupThread.start()

        val chaptersJson = ujson.Arr(chapters.zipWithIndex.map { case ((name, _, _, _), idx) =>
          ujson.Obj("name" -> (if (name.nonEmpty) name else s"Chapter ${idx + 1}"), "index" -> idx)
        }: _*)

        cask.Response(
          ujson.Obj("key" -> key, "chapters" -> chaptersJson),
          headers = Seq("Content-Type" -> "application/json")
        )
      } catch {
        case e: Exception =>
          logger.error("Error preparing Lishogi study", e)
          cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500)
      }
    }
  }

  @cask.post("/study/import-lishogi-chapter")
  def importLishogiChapter(request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      try {
        val json = ujson.read(request.text())
        val key = json("key").str
        val index = json("index").num.toInt

        val cached = studyCache.get(key)
        if (cached == null) {
          throw new Exception("Study data expired or not found. Please try again.")
        }

        val (chapters, sourceAuthor, studyUrl) = cached
        if (index < 0 || index >= chapters.size) {
          throw new Exception(s"Invalid chapter index: $index")
        }

        val (chapterName, kifContent, chapterSourceUrl, chapterStudyName) = chapters(index)
        val name = if (chapterName.nonEmpty) chapterName else s"Chapter ${index + 1}"
        val sName = chapterStudyName.filter(_.nonEmpty)

        val (rootSfen, moves, initialComment) = GameLoader.parseKifTreeWithInitialComment(kifContent)
        val id = Await.result(StudyRepository.createStudy(name, Some(email), rootSfen = Some(rootSfen), rootComment = initialComment, sourceUrl = chapterSourceUrl, sourceAuthor = sourceAuthor, studyUrl = Some(studyUrl), studyName = sName), 10.seconds)

        val addFuture = moves.foldLeft(scala.concurrent.Future.successful(())) { case (prev, (parentSfen, usi, nextSfen, comment)) =>
          prev.flatMap(_ => StudyRepository.addMove(id, parentSfen, usi, nextSfen, comment, None))
        }
        Await.result(addFuture, 120.seconds)

        logger.info(s"Chapter '$name' imported: id=$id, moves=${moves.size}")
        cask.Response(
          ujson.Obj("id" -> id, "name" -> name, "moveCount" -> moves.size),
          headers = Seq("Content-Type" -> "application/json")
        )
      } catch {
        case e: Exception =>
          logger.error("Error importing Lishogi chapter", e)
          cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500)
      }
    }
  }

  private def extractChapterMeta(chapterLines: Seq[String]): (String, Option[String], Option[String]) = {
    val fullTitle = chapterLines.find(_.startsWith("棋戦：")).map(_.stripPrefix("棋戦：").trim).getOrElse("")
    val dashIdx = fullTitle.lastIndexOf(" - ")
    val (studyName, chapterName) = if (dashIdx >= 0) {
      (Some(fullTitle.substring(0, dashIdx).trim), fullTitle.substring(dashIdx + 3).trim)
    } else {
      (None, fullTitle)
    }
    val sourceUrl = chapterLines.find(_.startsWith("場所：")).map(_.stripPrefix("場所：").trim).filter(_.startsWith("http"))
    (chapterName, sourceUrl, studyName)
  }

  private def splitMultiChapterKif(fullKif: String): Seq[(String, String, Option[String], Option[String])] = {
    val lines = fullKif.split("\n").toSeq
    val chapterBreaks = lines.zipWithIndex.filter { case (line, _) =>
      line.startsWith("棋戦：")
    }.map(_._2)

    if (chapterBreaks.size <= 1) {
      val (name, sourceUrl, studyName) = extractChapterMeta(lines)
      Seq((name, fullKif, sourceUrl, studyName))
    } else {
      val chapterStarts = chapterBreaks.map { idx =>
        var start = idx
        while (start > 0 && lines(start - 1).trim.nonEmpty) start -= 1
        start
      }

      chapterStarts.zipWithIndex.map { case (startIdx, i) =>
        val endIdx = if (i + 1 < chapterStarts.size) chapterStarts(i + 1) else lines.size
        val chapterLines = lines.slice(startIdx, endIdx)
        val kifContent = chapterLines.mkString("\n")
        val (name, sourceUrl, studyName) = extractChapterMeta(chapterLines)
        (name, kifContent, sourceUrl, studyName)
      }
    }
  }

  @cask.post("/study/create-from-kif")
  def createFromKif(request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      try {
        val json = ujson.read(request.text())
        val name = json("name").str
        val kif = json("kif").str

        val (rootSfen, moves, initialComment) = GameLoader.parseKifTreeWithInitialComment(kif)
        val id = Await.result(StudyRepository.createStudy(name, Some(email), rootSfen = Some(rootSfen), rootComment = initialComment), 10.seconds)

        val addFuture = moves.foldLeft(scala.concurrent.Future.successful(())) { case (prev, (parentSfen, usi, nextSfen, comment)) =>
          prev.flatMap(_ => StudyRepository.addMove(id, parentSfen, usi, nextSfen, comment, None))
        }
        Await.result(addFuture, 120.seconds)

        logger.info(s"Repertoire created from KIF: id=$id, moves=${moves.size}")
        cask.Response(
          ujson.Obj("id" -> id, "moveCount" -> moves.size),
          headers = Seq("Content-Type" -> "application/json")
        )
      } catch {
        case e: Exception =>
          logger.error("Error creating repertoire from KIF", e)
          cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500)
      }
    }
  }

  @cask.post("/study/:id/import-kif")
  def importKif(id: String, request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      withOwnership(id, email) { _ =>
        try {
          val json = ujson.read(request.text())
          val kif = json("kif").str

          val (_, moves) = GameLoader.parseKifTree(kif)

          val addFuture = moves.foldLeft(scala.concurrent.Future.successful(())) { case (prev, (parentSfen, usi, nextSfen, comment)) =>
            prev.flatMap(_ => StudyRepository.addMove(id, parentSfen, usi, nextSfen, comment, None))
          }
          Await.result(addFuture, 120.seconds)

          logger.info(s"Imported ${moves.size} moves from KIF into repertoire $id")
          cask.Response(
            ujson.Obj("success" -> true, "importedCount" -> moves.size),
            headers = Seq("Content-Type" -> "application/json")
          )
        } catch {
          case e: Exception =>
            logger.error(s"Error importing KIF into repertoire $id", e)
            cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500)
        }
      }
    }
  }

  @cask.post("/study/:id/reload-from-study")
  def reloadFromStudy(id: String, request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      withOwnership(id, email) { rep =>
        try {
          val sourceUrl = rep.get("sourceUrl").map(_.asString().getValue).getOrElse("")
          if (sourceUrl.isEmpty) {
            return cask.Response(ujson.Obj("error" -> "This study has no Lishogi study source URL."), statusCode = 400)
          }

          val kifUrl = if (sourceUrl.endsWith(".kif")) sourceUrl else sourceUrl + ".kif"
          logger.info(s"Reloading repertoire $id from study: $kifUrl")
          val kifResponse = requests.get(kifUrl)
          if (kifResponse.statusCode != 200) {
            throw new Exception(s"Failed to fetch study from Lishogi (HTTP ${kifResponse.statusCode})")
          }
          val kifContent = kifResponse.text()
          val (_, moves, initialComment) = GameLoader.parseKifTreeWithInitialComment(kifContent)

          // Update rootComment if present
          initialComment.foreach { comment =>
            Await.result(StudyRepository.updateRootComment(id, comment), 10.seconds)
          }

          // Merge moves (addMove skips duplicates)
          val addFuture = moves.foldLeft(scala.concurrent.Future.successful(())) { case (prev, (parentSfen, usi, nextSfen, comment)) =>
            prev.flatMap(_ => StudyRepository.addMove(id, parentSfen, usi, nextSfen, comment, None))
          }
          Await.result(addFuture, 120.seconds)

          logger.info(s"Reloaded ${moves.size} moves from study into repertoire $id")
          cask.Response(
            ujson.Obj("success" -> true, "moveCount" -> moves.size),
            headers = Seq("Content-Type" -> "application/json")
          )
        } catch {
          case e: Exception =>
            logger.error(s"Error reloading repertoire $id from study", e)
            cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500)
        }
      }
    }
  }
  @cask.post("/study/toggle-public")
  def togglePublic(request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      val json = ujson.read(request.text())
      val id = json("id").str
      val isPublic = json("isPublic").bool
      withOwnership(id, email) { _ =>
        Await.result(StudyRepository.toggleStudyPublic(id, isPublic), 10.seconds)
        cask.Response(ujson.Obj("success" -> true))
      }
    }
  }

  @cask.post("/study/delete")
  def delete(request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      val id = ujson.read(request.text())("id").str
      withOwnership(id, email) { _ =>
        Await.result(StudyRepository.deleteStudy(id), 10.seconds)
        cask.Response(ujson.Obj("success" -> true))
      }
    }
  }

  @cask.post("/study/reload")
  def reload(request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      val id = ujson.read(request.text())("id").str
      withOwnership(id, email) { rep =>
        try {
          val isAutoReload = rep.get("isAutoReload").exists(_.asBoolean().getValue)

          if (!isAutoReload) {
            cask.Response(ujson.Obj("error" -> "This study is not configured for auto-reload."), statusCode = 400)
          } else {
            val reloadThreshold = rep.get("reloadThreshold").map(_.asInt32().getValue).getOrElse(200)
            val reloadColor = rep.get("reloadColor").map(_.asString().getValue)
            val result = Await.result(performReload(id, Some(email), reloadThreshold, reloadColor), 5.minutes)
            cask.Response(ujson.Obj("success" -> true, "processedGames" -> result), headers = Seq("Content-Type" -> "application/json"))
          }
        } catch {
          case e: Exception =>
            logger.error(s"Error reloading repertoire $id", e)
            cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500)
        }
      }
    }
  }

  private def performReload(studyId: String, userEmail: Option[String], threshold: Int, reloadColor: Option[String]): scala.concurrent.Future[Int] = {
    import shogi.puzzler.db.GameRepository
    import shogi.puzzler.game.GameLoader
    import shogi.Color
    import scala.jdk.CollectionConverters._

    for {
      _ <- StudyRepository.clearStudyNodes(studyId)
      games <- GameRepository.getAllGames()
      analyzedGames = games.filter(_.get("is_analyzed").exists(_.asBoolean().getValue))
      
      _ = logger.info(s"Starting reload for study $studyId, found ${analyzedGames.size} analyzed games")
      
      processedCount <- analyzedGames.foldLeft(scala.concurrent.Future.successful(0)) { (accFut, gameDoc) =>
        accFut.flatMap { acc =>
          val kif = gameDoc.getString("kif")
          val kifHash = gameDoc.getString("kif_hash")
          val scores: Seq[Int] = gameDoc.get("scores") match {
            case Some(bsonValue) if bsonValue.isArray =>
              bsonValue.asArray().asScala.map(_.asInt32().getValue).toSeq
            case _ => Seq.empty
          }

          if (scores.isEmpty) {
            scala.concurrent.Future.successful(acc)
          } else {
            try {
              val parsedGame = GameLoader.parseKif(kif)
              val playerColor = parsedGame.playerColorInGame

              val shouldProcess = reloadColor match {
                case Some("sente") => playerColor == Color.Sente
                case Some("gote") => playerColor == Color.Gote
                case _ => true
              }

              if (!shouldProcess) {
                scala.concurrent.Future.successful(acc)
              } else {
                val sente = gameDoc.getString("sente")
                val gote = gameDoc.getString("gote")
                val date = gameDoc.getString("date")
                val gameName = s"$sente vs $gote ($date)"

                val positions = parsedGame.allPositions // Seq[(Vector[Usi], Sfen)]

                val movesToAdd = scala.collection.mutable.Buffer[(String, String, String, String, Boolean)]() // (parentSfen, usi, nextSfen, comment, isPuzzle)

                var stop = false
                for (i <- 0 until positions.size if !stop) {
                  val (usis, sfen) = positions(i)
                  if (usis.isEmpty) {
                    // skip start pos
                  } else {
                    val usi = usis.last.usi

                    val scoreBefore = if (i == 0) 0 else scores.lift(i - 1).getOrElse(0)
                    val scoreAfter = scores.lift(i).getOrElse(0)

                    // Derive move color from SFEN: if next turn is "w" (gote), the move was by Sente
                    val moveColor = if (sfen.value.split(" ")(1) == "w") Color.Sente else Color.Gote

                    var isPuzzle = false
                    if (moveColor == playerColor) {
                      val diff = if (playerColor == Color.Sente) scoreAfter - scoreBefore else scoreBefore - scoreAfter

                      if (diff < -threshold) {
                        stop = true
                        isPuzzle = true
                      }
                    }

                    if (!stop || isPuzzle) {
                      val parentSfen = if (i == 0) shogi.variant.Standard.initialSfen.value else positions(i - 1)._2.value
                      val evalStr = if (scoreAfter >= 0) s"+$scoreAfter" else s"$scoreAfter"
                      val comment = s"$gameName [eval: $evalStr]"
                      movesToAdd += ((parentSfen, usi, sfen.value, comment, isPuzzle))
                    }
                  }
                }

                addMovesSequentially(studyId, movesToAdd.toSeq).map(_ => acc + 1)
              }
            } catch {
              case e: Exception =>
                logger.error(s"Error processing game $kifHash during reload", e)
                scala.concurrent.Future.successful(acc)
            }
          }
        }
      }
    } yield processedCount
  }

  private def addMovesSequentially(studyId: String, moves: Seq[(String, String, String, String, Boolean)]): scala.concurrent.Future[Unit] = {
    if (moves.isEmpty) scala.concurrent.Future.successful(())
    else {
      val (parent, usi, next, comment, isPuzzle) = moves.head
      StudyRepository.addMove(studyId, parent, usi, next, Some(comment), Some(isPuzzle)).flatMap { _ =>
        addMovesSequentially(studyId, moves.tail)
      }
    }
  }

  @cask.get("/study/:id")
  def view(id: String, request: cask.Request) = {
    withAuth(request, "repertoire") { email =>
      val userEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      val pageLang = getLang(request)
      val repertoire = Await.result(StudyRepository.getStudy(id), 10.seconds)

      repertoire match {
        case Some(rep) =>
          val ownerEmail = rep.get("ownerEmail").map(_.asString().getValue).getOrElse("")
          if (ownerEmail != email) {
            cask.Response("Forbidden", statusCode = 403)
          } else {
            cask.Response(
              renderStudyEditor(userEmail, settings, rep, pageLang).render,
              headers = Seq(
                "Content-Type"                 -> "text/html; charset=utf-8",
                "Cross-Origin-Opener-Policy"   -> "same-origin",
                "Cross-Origin-Embedder-Policy" -> "credentialless"
              )
            )
          }
        case None => cask.Response("Not Found", statusCode = 404)
      }
    }
  }

  def renderStudyEditor(userEmail: Option[String], settings: AppSettings, repertoire: org.mongodb.scala.Document, pageLang: String = I18n.defaultLang) = {
    val id = repertoire.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
    val name = repertoire.getString("name")
    val sourceUrl = repertoire.get("sourceUrl").map(_.asString().getValue).getOrElse("")
    val sourceAuthor = repertoire.get("sourceAuthor").map(_.asString().getValue).getOrElse("")
    val studyUrl = repertoire.get("studyUrl").map(_.asString().getValue).getOrElse("")

    html(lang := "en", cls := "dark", style := "--zoom:90;")(
      head(
        meta(charset := "utf-8"),
        meta(attr("name") := "viewport", attr("content") := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(attr("name") := "theme-color", attr("content") := "#2e2a24"),
        tag("title")(s"Editing $name"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"),
        link(rel := "stylesheet", href := "/assets/css/shogiground.css"),
        link(rel := "stylesheet", href := "/assets/css/portella.css"),
        link(rel := "stylesheet", href := "/assets/css/site.css"),
        link(rel := "stylesheet", href := "/assets/css/puzzle.css"),
        link(rel := "stylesheet", href := "/assets/css/common.css"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.10.3/font/bootstrap-icons.css"),
        script(src := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js")
      ),
      body(cls := "wood coords-out playing online")(
        Components.renderHeader(userEmail, settings, appVersion, pageLang),
        div(attr("id") := "main-wrap")(
          div(cls := "rp-main-col")(
            div(cls := "puzzle__board-header")(
              div(cls := "d-flex align-items-center gap-2", attr("id") := "study-title-wrap")(
                h2(cls := "puzzle__board-header__title mb-0", attr("id") := "studyTitle")(name),
                button(cls := "btn btn-sm btn-outline-secondary p-0 px-1", onclick := "startEditTitle()", title := "Rename study")(i(cls := "bi bi-pencil"))
              ),
              if (sourceAuthor.nonEmpty || studyUrl.nonEmpty) {
                div(cls := "puzzle__board-header__source")(
                  if (sourceAuthor.nonEmpty) frag(
                    i(cls := "bi bi-person me-1"),
                    a(href := s"https://lishogi.org/@/$sourceAuthor", attr("target") := "_blank")(sourceAuthor)
                  ) else frag(),
                  if (sourceAuthor.nonEmpty && studyUrl.nonEmpty) tag("span")(cls := "mx-1")(" · ") else frag(),
                  if (studyUrl.nonEmpty) frag(
                    i(cls := "bi bi-book me-1"),
                    a(href := studyUrl, attr("target") := "_blank")("Lishogi Study")
                  ) else frag()
                )
              } else frag()
            ),
          tag("main")(cls := "puzzle puzzle-play")(
            div(cls := "puzzle__board main-board")(
              div(cls := "sg-wrap d-9x9")(
                tag("sg-hand-wrap")(attr("id") := "hand-top"),
                div(attr("id") := "dirty", cls := "dirty"),
                tag("sg-hand-wrap")(attr("id") := "hand-bottom")
              )
            ),
            div(cls := "puzzle__comment", attr("id") := "comment-card")(
              div(cls := "puzzle__controls mb-2")(
                div(cls := "analyse__tools")(
                  div(cls := "analyse__tools__menu")(
                    button(cls := "btn btn-outline-light", onclick := "toRoot()", title := "Back to Start")(i(cls := "bi bi-chevron-double-left")),
                    button(cls := "btn btn-outline-light", onclick := "revertMove()", title := "Previous Move")(i(cls := "bi bi-chevron-left")),
                    button(cls := "btn btn-outline-light", onclick := "advanceMove()", title := "Next Move")(i(cls := "bi bi-chevron-right")),
                    button(cls := "btn btn-outline-secondary", onclick := "saveAnnotations()", title := "Save drawn circles & arrows to comment")(i(cls := "bi bi-pin-angle"), tag("span")(cls := "d-none d-md-inline ms-1")("Save")),
                    button(cls := "btn btn-outline-info", attr("id") := "analyzeServerBtn", attr("data-bs-toggle") := "modal", attr("data-bs-target") := "#analyzeModal", title := "Analyze position with server engine")(i(cls := "bi bi-cpu"), tag("span")(cls := "d-none d-md-inline ms-1")("Analyze")),
                    button(cls := "btn btn-outline-warning", attr("id") := "analyzeCancelBtn", onclick := "cancelAnalysis()", title := "Cancel server analysis", style := "display:none;")(i(cls := "bi bi-stop-circle")),
                    button(cls := "btn btn-outline-success", attr("id") := "studyCevalBtn", onclick := "studyToggleCeval()", title := "Local engine (browser WASM)")(i(cls := "bi bi-cpu-fill"), tag("span")(cls := "d-none d-md-inline ms-1")("Local")),
                    button(cls := "btn btn-outline-secondary", onclick := "studyOpenCevalSettings()", title := "Local engine settings")(i(cls := "bi bi-gear"))
                  )
                )
              ),
              div(attr("id") := "comment-display", style := "display:none;")
            ),

            div(cls := "puzzle__side")(
              div(cls := "puzzle__side__box")(
                div(cls := "puzzle__tools")(
                  div(cls := "analyse__tools")(
                    div(cls := "analyse__engine-results", attr("id") := "engine-results", style := "display:none;"),
                    div(attr("id") := "engine-status", cls := "text-muted small px-1 pb-1", style := "display:none;"),
                    div(cls := "analyse__moves")(
                      div(cls := "analyse__moves__list")(
                        div(attr("id") := "variation-list")(
                          "Loading moves..."
                        )
                      ),
                      div(cls := "analyse__moves__import d-flex flex-wrap gap-1 pt-2 mt-2")(
                        button(cls := "btn btn-sm btn-outline-success", attr("data-bs-toggle") := "modal", attr("data-bs-target") := "#importKifModal", title := "Import moves from KIF file")(i(cls := "bi bi-file-earmark-arrow-up me-1"), "Import KIF"),
                        button(cls := "btn btn-sm btn-outline-light", attr("data-bs-toggle") := "modal", attr("data-bs-target") := "#importMovesModal", title := "Import USI move sequence")(i(cls := "bi bi-list-ol me-1"), "Import USI")
                      )
                    ),
                    div(cls := "analyse__tools__actions")(
                      div(cls := "btn-group btn-group-sm w-100")(
                        button(cls := "btn btn-outline-warning", onclick := "reviewInPuzzleCreator()", title := "Open in Puzzle Creator for review")(i(cls := "bi bi-puzzle me-1"), tag("span")(cls := "d-none d-lg-inline")("Review")),
                        button(cls := "btn btn-outline-info", onclick := "window.open('https://lishogi.org/analysis/' + currentSfen.replace(/ /g, '_'), '_blank')", title := "Analyze on Lishogi")(i(cls := "bi bi-search me-1"), tag("span")(cls := "d-none d-lg-inline")("Lishogi"))
                      )
                    )
                  )
                ),
                div(cls := "puzzle__feedback")()
              )
            )
          )
          )
        ),
        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        script(src := "/js/ceval.js"),
        input(`type` := "hidden", attr("id") := "studyId", attr("value") := id),
        input(`type` := "hidden", attr("id") := "sourceUrl", attr("value") := sourceUrl),
        
        // Move Edit Modal
        div(cls := "modal fade", attr("id") := "moveEditModal", attr("tabindex") := "-1")(
          div(cls := "modal-dialog")(
            div(cls := "modal-content bg-dark text-light border-secondary")(
              div(cls := "modal-header")(
                h5(cls := "modal-title", attr("id") := "moveEditModalLabel")("Move Settings"),
                button(`type` := "button", cls := "btn-close btn-close-white", attr("data-bs-dismiss") := "modal")()
              ),
              div(cls := "modal-body")(
                div(cls := "form-check")(
                  input(`type` := "checkbox", attr("id") := "moveIsPuzzle", cls := "form-check-input"),
                  label(cls := "form-check-label", attr("for") := "moveIsPuzzle")("Suitable for puzzle")
                )
              ),
              div(cls := "modal-footer")(
                button(`type` := "button", cls := "btn btn-secondary", attr("data-bs-dismiss") := "modal")("Cancel"),
                button(`type` := "button", cls := "btn btn-primary", onclick := "saveMoveDetails()")("Save")
              )
            )
          )
        ),

        // Import KIF Modal
        div(cls := "modal fade", attr("id") := "importKifModal", attr("tabindex") := "-1")(
          div(cls := "modal-dialog")(
            div(cls := "modal-content bg-dark text-light border-secondary")(
              div(cls := "modal-header")(
                h5(cls := "modal-title")("Import KIF File"),
                button(`type` := "button", cls := "btn-close btn-close-white", attr("data-bs-dismiss") := "modal")()
              ),
              div(cls := "modal-body")(
                div(cls := "mb-3")(
                  label(cls := "form-label")("KIF File"),
                  input(`type` := "file", attr("id") := "kifFileInput", cls := "form-control bg-dark text-light border-secondary", attr("accept") := ".kif,.kifu")
                ),
                p(cls := "text-muted small")("Moves and variations from the KIF file will be merged into this study.")
              ),
              div(cls := "modal-footer")(
                button(`type` := "button", cls := "btn btn-secondary", attr("data-bs-dismiss") := "modal")("Cancel"),
                button(`type` := "button", cls := "btn btn-success", onclick := "importKifFile()")("Import")
              )
            )
          )
        ),

        // Import Moves Modal
        div(cls := "modal fade", attr("id") := "importMovesModal", attr("tabindex") := "-1")(
          div(cls := "modal-dialog")(
            div(cls := "modal-content bg-dark text-light border-secondary")(
              div(cls := "modal-header")(
                h5(cls := "modal-title")("Import Move Sequence"),
                button(`type` := "button", cls := "btn-close btn-close-white", attr("data-bs-dismiss") := "modal")()
              ),
              div(cls := "modal-body")(
                div(cls := "mb-3")(
                  label(cls := "form-label")("USI Sequence (space separated)"),
                  textarea(attr("id") := "importUsis", cls := "form-control bg-dark text-light border-secondary", attr("rows") := "5", placeholder := "7g7f 3c3d 2g2f ...")
                ),
                div(cls := "mb-3")(
                  label(cls := "form-label")("Comment (applied to the last move)"),
                  textarea(attr("id") := "importComment", cls := "form-control bg-dark text-light border-secondary", attr("rows") := "2")
                ),
                div(cls := "form-check mb-3")(
                  input(`type` := "checkbox", attr("id") := "importIsPuzzle", cls := "form-check-input"),
                  label(cls := "form-check-label", attr("for") := "importIsPuzzle")("Suitable for puzzle (last move)")
                ),
                p(cls := "text-muted small")("Moves will be added starting from the current position.")
              ),
              div(cls := "modal-footer")(
                button(`type` := "button", cls := "btn btn-secondary", attr("data-bs-dismiss") := "modal")("Cancel"),
                button(`type` := "button", cls := "btn btn-success", onclick := "importMoves()")("Import")
              )
            )
          )
        ),

        // Analyze Modal
        div(cls := "modal fade", attr("id") := "analyzeModal", attr("tabindex") := "-1")(
          div(cls := "modal-dialog modal-sm")(
            div(cls := "modal-content bg-dark text-light border-secondary")(
              div(cls := "modal-header")(
                h5(cls := "modal-title")("Engine Analysis"),
                button(`type` := "button", cls := "btn-close btn-close-white", attr("data-bs-dismiss") := "modal")()
              ),
              div(cls := "modal-body")(
                div(cls := "mb-3")(
                  label(cls := "form-label")("Depth"),
                  input(`type` := "number", attr("id") := "analyzeDepth", cls := "form-control bg-dark text-light border-secondary", attr("value") := "15", attr("min") := "1", attr("max") := "30")
                ),
                div(cls := "mb-3")(
                  label(cls := "form-label")("Time (seconds, 0 = no limit)"),
                  input(`type` := "number", attr("id") := "analyzeTime", cls := "form-control bg-dark text-light border-secondary", attr("value") := "0", attr("min") := "0", attr("max") := "120", attr("step") := "1")
                ),
                div(cls := "mb-3")(
                  label(cls := "form-label")("MultiPV (candidate moves)"),
                  input(`type` := "number", attr("id") := "analyzeMultiPv", cls := "form-control bg-dark text-light border-secondary", attr("value") := "3", attr("min") := "1", attr("max") := "10")
                )
              ),
              div(cls := "modal-footer")(
                button(`type` := "button", cls := "btn btn-secondary", attr("data-bs-dismiss") := "modal")("Cancel"),
                button(`type` := "button", cls := "btn btn-info", attr("id") := "analyzeBtn", onclick := "analyzePosition()")("Analyze")
              )
            )
          )
        ),

        script(src := "/js/study-editor.js", `type` := "module")
      )
    )
  }

  @cask.get("/study/:id/json")
  def getStudyJson(id: String, request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      withOwnership(id, email) { rep =>
        logger.info(s"Found repertoire: ${rep.getString("name")}")
        cask.Response(
          ujson.read(rep.toJson()),
          headers = Seq("Content-Type" -> "application/json")
        )
      }
    }
  }

  @cask.post("/study/:id/move")
  def addMove(id: String, request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      withOwnership(id, email) { _ =>
        val json = ujson.read(request.text())
        val parentSfen = json("parentSfen").str
        val usi = json("usi").str
        val nextSfen = json("nextSfen").str
        val comment = json.obj.get("comment").map(_.str)
        val isPuzzle = json.obj.get("isPuzzle").map(_.bool)

        Await.result(StudyRepository.addMove(id, parentSfen, usi, nextSfen, comment, isPuzzle), 10.seconds)
        logger.info(s"Move $usi added successfully")
        cask.Response(ujson.Obj("success" -> true))
      }
    }
  }

  @cask.post("/study/:id/move/update")
  def updateMove(id: String, request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      withOwnership(id, email) { _ =>
        val json = ujson.read(request.text())
        val parentSfen = json("parentSfen").str
        val usi = json("usi").str
        val comment = json.obj.get("comment").map(_.str)
        val isPuzzle = json.obj.get("isPuzzle").map(_.bool)

        Await.result(StudyRepository.updateMove(id, parentSfen, usi, comment, isPuzzle), 10.seconds)
        logger.info(s"Move $usi updated successfully")
        cask.Response(ujson.Obj("success" -> true))
      }
    }
  }

  @cask.post("/study/:id/move/delete")
  def deleteMove(id: String, request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      withOwnership(id, email) { _ =>
        val json = ujson.read(request.text())
        val parentSfen = json("parentSfen").str
        val usi = json("usi").str

        Await.result(StudyRepository.deleteMove(id, parentSfen, usi), 10.seconds)
        logger.info(s"Move $usi deleted successfully")
        cask.Response(ujson.Obj("success" -> true))
      }
    }
  }

  @cask.post("/study/:id/import")
  def importMoves(id: String, request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      withOwnership(id, email) { _ =>
        val json = ujson.read(request.text())
        val usiListStr = json("usis").str
        val startSfen = json.obj.get("startSfen").map(_.str).getOrElse(shogi.variant.Standard.initialSfen.value)
        val comment = json.obj.get("comment").map(_.str).filter(_.nonEmpty)
        val isPuzzle = json.obj.get("isPuzzle").map(_.bool)

        val usisStrings = usiListStr.split("\\s+").filter(_.nonEmpty).toList

        import shogi.format.usi.Usi
        import shogi.format.forsyth.Sfen
        import shogi.Replay

        val res = Replay.gamesWhileValid(usisStrings.flatMap(Usi.apply), Some(Sfen(startSfen)), shogi.variant.Standard)
        val games = res._1.toList

        var currentParentSfen = startSfen
        var importedCount = 0

        games.zipWithIndex.foreach { case (state, idx) =>
          state.usis.lastOption.foreach { lastUsi =>
            val usi = lastUsi.usi
            val nextSfen = state.toSfen.value
            val isLast = idx == games.size - 1

            val (c, p) = if (isLast) (comment, isPuzzle) else (None, None)

            Await.result(StudyRepository.addMove(id, currentParentSfen, usi, nextSfen, c, p), 10.seconds)
            currentParentSfen = nextSfen
            importedCount += 1
          }
        }

        cask.Response(ujson.Obj("success" -> true, "importedCount" -> importedCount))
      }
    }
  }

  @cask.post("/study/:id/move/translate")
  def translateMove(id: String, request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      withOwnership(id, email) { _ =>
        try {
          val json       = ujson.read(request.text())
          val parentSfen = json("parentSfen").str
          val usi        = json("usi").str
          val lang       = json("lang").str
          val comment    = json("comment").str

          Await.result(StudyRepository.saveMovei18nComment(id, parentSfen, usi, lang, comment), 10.seconds)
          cask.Response(ujson.Obj("success" -> true), headers = Seq("Content-Type" -> "application/json"))
        } catch {
          case e: Exception =>
            logger.error(s"Error saving move translation for repertoire $id", e)
            cask.Response(
              ujson.Obj("error" -> Option(e.getMessage).getOrElse("Unknown error")),
              statusCode = 500,
              headers = Seq("Content-Type" -> "application/json")
            )
        }
      }
    }
  }

  @cask.post("/study/:id/rename")
  def renameStudy(id: String, request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      withOwnership(id, email) { _ =>
        val json = ujson.read(request.text())
        val name = json("name").str.trim
        if (name.isEmpty) {
          cask.Response(ujson.Obj("error" -> "Name cannot be empty"), statusCode = 400, headers = Seq("Content-Type" -> "application/json"))
        } else {
          Await.result(StudyRepository.renameStudy(id, name), 10.seconds)
          cask.Response(ujson.Obj("success" -> true), headers = Seq("Content-Type" -> "application/json"))
        }
      }
    }
  }

  @cask.post("/study/:id/root-comment")
  def saveRootComment(id: String, request: cask.Request): cask.Response[ujson.Value] = {
    withAuthJson(request, "repertoire") { email =>
      withOwnership(id, email) { _ =>
        val json = ujson.read(request.text())
        val comment = json("comment").str
        Await.result(StudyRepository.updateRootComment(id, comment), 10.seconds)
        cask.Response(ujson.Obj("success" -> true), headers = Seq("Content-Type" -> "application/json"))
      }
    }
  }

  initialize()
}
