package shogi.puzzler.maintenance.routes

import cask._
import scalatags.Text.all._
import shogi.puzzler.db.{RepertoireRepository, SettingsRepository, AppSettings}
import shogi.puzzler.ui.Components
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object RepertoireRoutes extends BaseRoutes {

  @cask.get("/repertoire")
  def index(request: cask.Request) = {
    withAuth(request, "repertoire") { email =>
      val userEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      val repertoires = Await.result(RepertoireRepository.getRepertoires(userEmail), 10.seconds)
      
      cask.Response(
        renderRepertoirePage(userEmail, settings, repertoires).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  def renderRepertoirePage(userEmail: Option[String], settings: AppSettings, repertoires: Seq[org.mongodb.scala.Document]) = {
    Components.layout(
      "Shogi Repertoire",
      userEmail,
      settings,
      appVersion,
      scripts = Seq(
        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        script(`type` := "module", src := "/js/repertoire.js")
      )
    )(
      div(cls := "d-flex justify-content-between align-items-center mb-4")(
        h1("My Repertoires"),
        button(cls := "btn btn-primary", attr("data-bs-toggle") := "modal", attr("data-bs-target") := "#createRepertoireModal")("Create New")
      ),
      
      div(cls := "row")(
        repertoires.map { rep =>
          val id = rep.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
          val name = rep.getString("name")
          val isAutoReload = rep.get("isAutoReload").exists(_.asBoolean().getValue)
          val reloadColor = rep.get("reloadColor").map(_.asString().getValue).getOrElse("")
          val colorBadge: Modifier = if (isAutoReload && reloadColor.nonEmpty) {
            tag("span")(cls := "badge bg-secondary ms-2")(reloadColor.capitalize)
          } else frag()

          div(cls := "col-md-4 mb-3")(
            div(cls := "card bg-dark text-light border-secondary")(
              div(cls := "card-body")(
                h5(cls := "card-title")(name, colorBadge),
                a(href := s"/repertoire/$id", cls := "btn btn-sm btn-outline-info")("Open"),
                if (isAutoReload) {
                  button(cls := "btn btn-sm btn-outline-warning ms-2", onclick := s"reloadRepertoire('$id')")("Reload")
                } else frag(),
                button(cls := "btn btn-sm btn-outline-danger ms-2", onclick := s"deleteRepertoire('$id')")("Delete")
              )
            )
          )
        }
      ),

      // Create Modal
      div(cls := "modal fade", id := "createRepertoireModal", tabindex := "-1")(
        div(cls := "modal-dialog")(
          div(cls := "modal-content bg-dark text-light border-secondary")(
            div(cls := "modal-header")(
              h5(cls := "modal-title")("New Repertoire"),
              button(`type` := "button", cls := "btn-close btn-close-white", attr("data-bs-dismiss") := "modal")()
            ),
            div(cls := "modal-body")(
              div(cls := "mb-3")(
                label(cls := "form-label")("Name"),
                input(`type` := "text", id := "repertoireName", cls := "form-control bg-dark text-light border-secondary")
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
              button(`type` := "button", cls := "btn btn-primary", onclick := "createRepertoire()")("Create")
            )
          )
        )
      )
    )
  }

  @cask.post("/repertoire/create")
  def create(request: cask.Request): cask.Response[ujson.Value] = {
    logger.info(s"Received create repertoire request: ${request.text()}")
    try {
      val userEmail = getSessionUserEmail(request)
      logger.info(s"User email from session: $userEmail")
      val json = ujson.read(request.text())
      val name = json("name").str
      val isAutoReload = json.obj.get("isAutoReload").map(_.bool).getOrElse(false)
      val reloadThreshold = json.obj.get("reloadThreshold").map(_.num.toInt).getOrElse(200)
      val reloadColor = json.obj.get("reloadColor").map(_.str)
      logger.info(s"Creating repertoire with name: $name, isAutoReload: $isAutoReload, reloadThreshold: $reloadThreshold, reloadColor: $reloadColor")
      val id = Await.result(RepertoireRepository.createRepertoire(name, userEmail, isAutoReload, reloadThreshold, reloadColor), 10.seconds)
      logger.info(s"Repertoire created with id: $id")
      cask.Response(ujson.Obj("id" -> id), headers = Seq("Content-Type" -> "application/json"))
    } catch {
      case e: Exception =>
        logger.error("Error creating repertoire", e)
        cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500)
    }
  }

  @cask.post("/repertoire/delete")
  def delete(request: cask.Request) = {
    val id = ujson.read(request.text())("id").str
    Await.result(RepertoireRepository.deleteRepertoire(id), 10.seconds)
    ujson.Obj("success" -> true)
  }

  @cask.post("/repertoire/reload")
  def reload(request: cask.Request): cask.Response[ujson.Value] = {
    val id = ujson.read(request.text())("id").str
    val userEmail = getSessionUserEmail(request)
    
    try {
      val repertoire = Await.result(RepertoireRepository.getRepertoire(id), 10.seconds)
      val isAutoReload = repertoire.flatMap(_.get("isAutoReload")).exists(_.asBoolean().getValue)
      
      if (!isAutoReload) {
        cask.Response(ujson.Obj("error" -> "Tento repertoár nie je určený na automatické načítanie hier."), statusCode = 400)
      } else {
        val reloadThreshold = repertoire.flatMap(_.get("reloadThreshold")).map(_.asInt32().getValue).getOrElse(200)
        val reloadColor = repertoire.flatMap(_.get("reloadColor")).map(_.asString().getValue)
        val result = Await.result(performReload(id, userEmail, reloadThreshold, reloadColor), 5.minutes)
        cask.Response(ujson.Obj("success" -> true, "processedGames" -> result), headers = Seq("Content-Type" -> "application/json"))
      }
    } catch {
      case e: Exception =>
        logger.error(s"Error reloading repertoire $id", e)
        cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 500)
    }
  }

  private def performReload(repertoireId: String, userEmail: Option[String], threshold: Int, reloadColor: Option[String]): scala.concurrent.Future[Int] = {
    import shogi.puzzler.db.GameRepository
    import shogi.puzzler.game.GameLoader
    import shogi.Color
    import scala.jdk.CollectionConverters._

    for {
      _ <- RepertoireRepository.clearRepertoireNodes(repertoireId)
      games <- GameRepository.getAllGames()
      analyzedGames = games.filter(_.get("is_analyzed").exists(_.asBoolean().getValue))
      
      _ = logger.info(s"Starting reload for repertoire $repertoireId, found ${analyzedGames.size} analyzed games")
      
      processedCount <- scala.concurrent.Future.sequence(analyzedGames.map { gameDoc =>
        val kif = gameDoc.getString("kif")
        val kifHash = gameDoc.getString("kif_hash")
        val scores: Seq[Int] = gameDoc.get("scores") match {
          case Some(bsonValue) if bsonValue.isArray => 
            bsonValue.asArray().asScala.map(_.asInt32().getValue).toSeq
          case _ => Seq.empty
        }
        
        if (scores.isEmpty) {
          scala.concurrent.Future.successful(0)
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
              scala.concurrent.Future.successful(0)
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
                   // skip start pos if it somehow ended up in allPositions as first element
                } else {
                  val usi = usis.last.usi
                  
                  val scoreBefore = if (i == 0) 0 else scores.lift(i-1).getOrElse(0)
                  val scoreAfter = scores.lift(i).getOrElse(0)
                  
                  val moveColor = if ((i + 1) % 2 != 0) Color.Sente else Color.Gote
                  
                  var isPuzzle = false
                  if (moveColor == playerColor) {
                    val diff = if (playerColor == Color.Sente) scoreAfter - scoreBefore else scoreBefore - scoreAfter
                    
                    if (diff < -threshold) { 
                      stop = true
                      isPuzzle = true
                    }
                  }
                  
                  if (!stop || isPuzzle) {
                    val parentSfen = if (i == 0) shogi.variant.Standard.initialSfen.value else positions(i-1)._2.value
                    val evalStr = if (scoreAfter >= 0) s"+$scoreAfter" else s"$scoreAfter"
                    val comment = s"$gameName [eval: $evalStr]"
                    movesToAdd += ((parentSfen, usi, sfen.value, comment, isPuzzle))
                  }
                }
              }
              
              addMovesSequentially(repertoireId, movesToAdd.toSeq).map(_ => 1)
            }
          } catch {
            case e: Exception => 
              logger.error(s"Error processing game $kifHash during reload", e)
              scala.concurrent.Future.successful(0)
          }
        }
      }).map(_.sum)
    } yield processedCount
  }

  private def addMovesSequentially(repertoireId: String, moves: Seq[(String, String, String, String, Boolean)]): scala.concurrent.Future[Unit] = {
    if (moves.isEmpty) scala.concurrent.Future.successful(())
    else {
      val (parent, usi, next, comment, isPuzzle) = moves.head
      RepertoireRepository.addMove(repertoireId, parent, usi, next, Some(comment), Some(isPuzzle)).flatMap { _ =>
        addMovesSequentially(repertoireId, moves.tail)
      }
    }
  }

  @cask.get("/repertoire/:id")
  def view(id: String, request: cask.Request) = {
    withAuth(request, "repertoire") { email =>
      val userEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      val repertoire = Await.result(RepertoireRepository.getRepertoire(id), 10.seconds)
      
      repertoire match {
        case Some(rep) =>
          cask.Response(
            renderRepertoireEditor(userEmail, settings, rep).render,
            headers = Seq("Content-Type" -> "text/html; charset=utf-8")
          )
        case None => cask.Response("Not Found", statusCode = 404)
      }
    }
  }

  def renderRepertoireEditor(userEmail: Option[String], settings: AppSettings, repertoire: org.mongodb.scala.Document) = {
    val id = repertoire.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
    val name = repertoire.getString("name")
    
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
        Components.renderHeader(userEmail, settings, appVersion),
        div(attr("id") := "main-wrap")(
          tag("main")(cls := "puzzle puzzle-play")(
            div(cls := "puzzle__board main-board")(
              div(cls := "sg-wrap d-9x9")(
                tag("sg-hand-wrap")(attr("id") := "hand-top"),
                div(attr("id") := "dirty", cls := "dirty"),
                tag("sg-hand-wrap")(attr("id") := "hand-bottom")
              )
            ),
            div(cls := "puzzle__side")(
              div(cls := "puzzle__side__box")(
                div(cls := "puzzle__tools")(
                  div(cls := "analyse__tools")(
                    div(cls := "analyse__tools__menu")(
                      button(cls := "btn btn-sm btn-outline-light me-2", onclick := "revertMove()", title := "Previous Move")(i(cls := "bi bi-chevron-left")),
                      button(cls := "btn btn-sm btn-outline-light", onclick := "toRoot()", title := "Back to Start")(i(cls := "bi bi-chevron-double-left")),
                      div(cls := "ms-auto")(
                        button(cls := "btn btn-sm btn-outline-success me-2", attr("data-bs-toggle") := "modal", attr("data-bs-target") := "#importMovesModal", title := "Import sequence (e.g. 7g7f 3c3d 2g2f)")(i(cls := "bi bi-download"), " Import"),
                        button(cls := "btn btn-sm btn-outline-info", onclick := "window.open('https://lishogi.org/analysis/' + currentSfen.replace(/ /g, '_'), '_blank')", title := "Analyze on Lishogi")(i(cls := "bi bi-search"), " Lishogi")
                      )
                    ),
                    div(cls := "analyse__moves")(
                      div(cls := "analyse__moves__list")(
                        div(attr("id") := "variation-list")(
                          "Loading moves..."
                        )
                      )
                    )
                  )
                ),
                div(cls := "puzzle__feedback")(
                  h2(name)
                )
              )
            )
          )
        ),
        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        input(`type` := "hidden", attr("id") := "repertoireId", attr("value") := id),
        
        // Move Edit Modal
        div(cls := "modal fade", attr("id") := "moveEditModal", attr("tabindex") := "-1")(
          div(cls := "modal-dialog")(
            div(cls := "modal-content bg-dark text-light border-secondary")(
              div(cls := "modal-header")(
                h5(cls := "modal-title", attr("id") := "moveEditModalLabel")("Move Details"),
                button(`type` := "button", cls := "btn-close btn-close-white", attr("data-bs-dismiss") := "modal")()
              ),
              div(cls := "modal-body")(
                div(cls := "mb-3")(
                  label(cls := "form-label")("Comment"),
                  textarea(attr("id") := "moveComment", cls := "form-control bg-dark text-light border-secondary", attr("rows") := "3")
                ),
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
        
        script(src := "/js/repertoire-editor.js", `type` := "module")
      )
    )
  }

  @cask.get("/repertoire/:id/json")
  def getRepertoireJson(id: String, request: cask.Request) = {
    logger.info(s"Fetching repertoire JSON for id: $id")
    val repertoire = Await.result(RepertoireRepository.getRepertoire(id), 10.seconds)
    repertoire match {
      case Some(rep) =>
        logger.info(s"Found repertoire: ${rep.getString("name")}")
        cask.Response(
          ujson.read(rep.toJson()),
          headers = Seq("Content-Type" -> "application/json")
        )
      case None => 
        logger.warn(s"Repertoire not found: $id")
        cask.Response(ujson.Obj("error" -> "Not Found"), statusCode = 404)
    }
  }

  @cask.post("/repertoire/:id/move")
  def addMove(id: String, request: cask.Request) = {
    logger.info(s"Adding move to repertoire $id: ${request.text()}")
    val json = ujson.read(request.text())
    val parentSfen = json("parentSfen").str
    val usi = json("usi").str
    val nextSfen = json("nextSfen").str
    val comment = json.obj.get("comment").map(_.str)
    val isPuzzle = json.obj.get("isPuzzle").map(_.bool)
    
    Await.result(RepertoireRepository.addMove(id, parentSfen, usi, nextSfen, comment, isPuzzle), 10.seconds)
    logger.info(s"Move $usi added successfully")
    ujson.Obj("success" -> true)
  }

  @cask.post("/repertoire/:id/move/update")
  def updateMove(id: String, request: cask.Request) = {
    logger.info(s"Updating move in repertoire $id: ${request.text()}")
    val json = ujson.read(request.text())
    val parentSfen = json("parentSfen").str
    val usi = json("usi").str
    val comment = json.obj.get("comment").map(_.str)
    val isPuzzle = json.obj.get("isPuzzle").map(_.bool)
    
    Await.result(RepertoireRepository.updateMove(id, parentSfen, usi, comment, isPuzzle), 10.seconds)
    logger.info(s"Move $usi updated successfully")
    ujson.Obj("success" -> true)
  }

  @cask.post("/repertoire/:id/move/delete")
  def deleteMove(id: String, request: cask.Request) = {
    logger.info(s"Deleting move from repertoire $id: ${request.text()}")
    val json = ujson.read(request.text())
    val parentSfen = json("parentSfen").str
    val usi = json("usi").str
    
    Await.result(RepertoireRepository.deleteMove(id, parentSfen, usi), 10.seconds)
    logger.info(s"Move $usi deleted successfully")
    ujson.Obj("success" -> true)
  }

  @cask.post("/repertoire/:id/import")
  def importMoves(id: String, request: cask.Request) = {
    logger.info(s"Importing moves to repertoire $id")
    val json = ujson.read(request.text())
    val usiListStr = json("usis").str
    val startSfen = json.obj.get("startSfen").map(_.str).getOrElse(shogi.variant.Standard.initialSfen.value)
    val comment = json.obj.get("comment").map(_.str).filter(_.nonEmpty)
    val isPuzzle = json.obj.get("isPuzzle").map(_.bool)

    val usisStrings = usiListStr.split("\\s+").filter(_.nonEmpty).toList
    
    import shogi.format.usi.Usi
    import shogi.format.forsyth.Sfen
    import shogi.Replay

    // Replay returns GameState which has usis and toSfen
    val res = Replay.gamesWhileValid(usisStrings.flatMap(Usi.apply), Some(Sfen(startSfen)), shogi.variant.Standard)
    val games = res._1.toList 
    
    var currentParentSfen = startSfen
    var importedCount = 0

    games.zipWithIndex.foreach { case (state, idx) =>
      // GameState has usis: Vector[Usi] and toSfen: Sfen
      state.usis.lastOption.foreach { lastUsi =>
          val usi = lastUsi.usi
          val nextSfen = state.toSfen.value
          val isLast = idx == games.size - 1
          
          val (c, p) = if (isLast) (comment, isPuzzle) else (None, None)
          
          Await.result(RepertoireRepository.addMove(id, currentParentSfen, usi, nextSfen, c, p), 10.seconds)
          currentParentSfen = nextSfen
          importedCount += 1
      }
    }

    ujson.Obj("success" -> true, "importedCount" -> importedCount)
  }

  initialize()
}
