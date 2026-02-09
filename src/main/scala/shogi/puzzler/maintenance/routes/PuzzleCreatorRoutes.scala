package shogi.puzzler.maintenance.routes

import scalatags.Text.all._
import shogi.Color
import shogi.puzzler.db.{AppSettings, GameRepository, SettingsRepository}
import shogi.puzzler.domain.{CpScore, MateScore, PovScore, Score}
import shogi.puzzler.engine.{EngineManager, Limit}
import shogi.puzzler.maintenance.routes.MaintenanceRoutes.getEngineManager
import shogi.puzzler.ui.Components

import scala.concurrent.Await
import scala.concurrent.duration._

object PuzzleCreatorRoutes extends BaseRoutes {

  import org.mongodb.scala.bson.collection.immutable.{Document => BsonDocument}

  @cask.get("/puzzle-creator")
  def puzzleCreatorList(request: cask.Request) = {
    withAuth(request, "puzzle-creator") { email =>
      val userEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      val puzzles = Await.result(GameRepository.getCustomPuzzles(email), 10.seconds)
      cask.Response(
        renderPuzzleListPage(userEmail, settings, puzzles).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  @cask.get("/puzzle-creator/new")
  def puzzleCreatorNew(request: cask.Request) = {
    withAuth(request, "puzzle-creator") { email =>
      val userEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      cask.Response(
        renderPuzzleEditor(userEmail, settings, None).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  @cask.get("/puzzle-creator/edit/:id")
  def puzzleCreatorEdit(request: cask.Request, id: String) = {
    withAuth(request, "puzzle-creator") { email =>
      val userEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
      val puzzle = Await.result(GameRepository.getCustomPuzzle(id, email), 10.seconds)
      puzzle match {
        case Some(doc) =>
          cask.Response(
            renderPuzzleEditor(userEmail, settings, Some(doc)).render,
            headers = Seq("Content-Type" -> "text/html; charset=utf-8")
          )
        case None =>
          noCacheRedirect("/puzzle-creator")
      }
    }
  }

  def renderPuzzleListPage(userEmail: Option[String], settings: AppSettings, puzzles: Seq[BsonDocument]) = {
    Components.layout(
      "Puzzle Creator",
      userEmail,
      settings,
      appVersion,
      scripts = Seq(
        raw(tag("style")("""
          .puzzle-card {
            background: #2e2a24;
            border: 1px solid #444;
            border-radius: 8px;
            margin-bottom: 20px;
            overflow: hidden;
            display: flex;
            min-height: 120px;
          }
          @media (max-width: 768px) {
            .puzzle-card {
              flex-direction: column;
              min-height: auto;
            }
            .puzzle-card-icon {
              width: 100% !important;
              height: 60px !important;
              border-right: none !important;
              border-bottom: 1px solid #444;
            }
            .puzzle-card-content {
              padding: 10px;
            }
            .sfen-container {
              flex-wrap: wrap;
            }
            .sfen-input {
              width: 100%;
              margin-bottom: 5px;
            }
          }
          .puzzle-card-icon {
            width: 80px;
            min-height: 120px;
            display: flex;
            align-items: center;
            justify-content: center;
            background: #1a1712;
            border-right: 1px solid #444;
            font-size: 2em;
            color: #d5ae39;
          }
          .puzzle-card-content {
            padding: 15px;
            flex-grow: 1;
            display: flex;
            flex-direction: column;
            justify-content: space-between;
          }
          .puzzle-card-name {
            font-size: 1.1em;
            font-weight: bold;
            color: #eee;
            margin-bottom: 4px;
          }
          .puzzle-card-date {
            font-size: 0.8em;
            color: #888;
          }
          .puzzle-card-comments {
            font-style: italic;
            color: #ccc;
            margin-top: 4px;
            font-size: 0.9em;
          }
          .puzzle-card-actions {
            display: flex;
            gap: 10px;
            align-items: center;
          }
          .badge-public {
            background: #198754;
            color: #fff;
            font-size: 0.75em;
            padding: 2px 8px;
            border-radius: 10px;
            margin-left: 8px;
          }
          .sfen-container {
            display: flex;
            gap: 5px;
            margin-top: 5px;
            background: #1a1712;
            padding: 5px;
            border-radius: 4px;
            align-items: center;
          }
          .sfen-input {
            background: transparent;
            border: none;
            color: #aaa;
            font-family: monospace;
            font-size: 0.85em;
            flex-grow: 1;
            outline: none;
          }
          .copy-btn {
            background: #444;
            border: none;
            color: #fff;
            padding: 2px 6px;
            border-radius: 3px;
            font-size: 0.75em;
            cursor: pointer;
          }
          .copy-btn:hover {
            background: #555;
          }
          .lishogi-link {
            color: #d5ae39;
            font-size: 0.85em;
            text-decoration: none;
          }
          .lishogi-link:hover {
            text-decoration: underline;
          }
        """).render)
      )
    )(
      div(cls := "mt-4")(
        div(cls := "d-flex justify-content-between align-items-center mb-4")(
          h2("Puzzle Creator"),
          a(href := "/puzzle-creator/new", cls := "btn btn-success")(i(cls := "bi bi-plus-lg me-2"), "New Puzzle")
        ),
        if (puzzles.isEmpty) {
          div(cls := "alert alert-info")("No puzzles created yet. Click \"New Puzzle\" to get started!")
        } else {
          div(
            puzzles.map { doc =>
              val puzzleId = doc.getObjectId("_id").toHexString
              val puzzleName = doc.getString("name")
              val sfen = doc.getString("sfen")
              val puzzleComments = doc.getString("comments")
              val isPublic = doc.getBoolean("is_public", false)
              val createdAt = doc.getLong("created_at")
              val dateStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(createdAt))

              div(cls := "puzzle-card")(
                div(cls := "puzzle-card-icon")(
                  i(cls := "bi bi-puzzle")
                ),
                div(cls := "puzzle-card-content")(
                  div(
                    div(
                      tag("span")(cls := "puzzle-card-name")(puzzleName),
                      if (isPublic) tag("span")(cls := "badge-public")("Public") else ()
                    ),
                    div(cls := "puzzle-card-date")(dateStr),
                    if (puzzleComments.nonEmpty) div(cls := "puzzle-card-comments")(puzzleComments) else (),
                    if (sfen.nonEmpty) {
                      div(cls := "sfen-container")(
                        input(cls := "sfen-input", value := sfen, readonly := true),
                        button(cls := "copy-btn", onclick := s"window.copySfen('${sfen.replace("'", "\\'")}')")("Copy"),
                        a(cls := "lishogi-link", href := s"https://lishogi.org/analysis/${sfen.replace(" ", "_")}", target := "_blank", rel := "noopener")("Lishogi")
                      )
                    } else div()
                  ),
                  div(cls := "puzzle-card-actions")(
                    a(href := s"/puzzle-creator/edit/$puzzleId", cls := "btn btn-primary btn-sm")(i(cls := "bi bi-pencil me-1"), "Edit"),
                    button(cls := "btn btn-danger btn-sm", onclick := s"window.deletePuzzle('$puzzleId')")(i(cls := "bi bi-trash me-1"), "Delete")
                  )
                )
              ): Frag
            }
          )
        },
        script(raw("""
          window.deletePuzzle = function(id) {
            if (confirm('Are you sure you want to delete this puzzle?')) {
              fetch('/puzzle-creator/delete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id: id })
              })
                .then(r => r.json())
                .then(data => {
                  if (data.success) location.reload();
                  else alert('Delete failed: ' + (data.error || data.message));
                });
            }
          }
          window.copySfen = function(sfen) {
            navigator.clipboard.writeText(sfen).then(() => {
              console.log('SFEN copied to clipboard');
            });
          }
        """))
      )
    )
  }

  def renderPuzzleEditor(userEmail: Option[String] = None, settings: AppSettings, puzzle: Option[BsonDocument] = None) = {
    html(lang := "en", cls := "dark", style := "--zoom:90;")(
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", content := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(name := "theme-color", content := "#2e2a24"),
        tag("title")(if (puzzle.isDefined) "Edit Puzzle" else "New Puzzle"),
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
        div(id := "main-wrap")(
          tag("main")(cls := "puzzle puzzle-play")(
            div(cls := "puzzle__board main-board")(
              div(cls := "sg-wrap d-9x9")(
                tag("sg-hand-wrap")(id := "hand-top"),
                div(id := "dirty"),
                tag("sg-hand-wrap")(id := "hand-bottom")
              )
            ),
            div(cls := "puzzle__controls")(
              div(cls := "container-fluid p-0")(
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    a(href := "/puzzle-creator", cls := "btn btn-outline-secondary btn-sm mb-2")(
                      i(cls := "bi bi-arrow-left me-1"), "Back to list"
                    )
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    label(cls := "form-label text-light")("Puzzle Name"),
                    input(cls := "form-control form-control-sm", id := "puzzle-name", `type` := "text", placeholder := "Enter puzzle name")
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    label(cls := "form-label text-light")("SFEN (optional - leave empty for initial board)"),
                    textarea(cls := "form-control form-control-sm", id := "puzzle-sfen", rows := "3", placeholder := "Enter SFEN notation")
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    label(cls := "form-label text-light")("Comments (optional)"),
                    textarea(cls := "form-control form-control-sm", id := "puzzle-comments", rows := "2", placeholder := "Add notes about this puzzle")
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    div(cls := "form-check")(
                      input(cls := "form-check-input", id := "puzzle-public", `type` := "checkbox"),
                      label(cls := "form-check-label text-light", `for` := "puzzle-public")("Make this puzzle public")
                    )
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    button(cls := "btn btn-success w-100", id := "save-puzzle")(
                      i(cls := "bi bi-save me-2"), "Save Puzzle"
                    )
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-6")(
                    button(cls := "btn btn-success w-100", id := "analyze-sequence")(
                      i(cls := "bi bi-play-fill me-2"), "Analyze"
                    )
                  ),
                  div(cls := "col-6")(
                    button(cls := "btn btn-danger w-100", id := "stop-analysis", style := "display:none;")(
                      i(cls := "bi bi-stop-fill me-2"), "Stop"
                    )
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    button(cls := "btn btn-info w-100", id := "toggle-arrows")(
                      i(cls := "bi bi-eye me-2"), "Show/Hide Arrows & Reset"
                    )
                  )
                ),
                div(cls := "row g-2 mb-2")(
                  div(cls := "col-3")(
                    label(cls := "form-label text-light small mb-1")("Depth"),
                    input(cls := "form-control form-control-sm", id := "analysis-depth", `type` := "number", value := "15", min := "1", max := "50")
                  ),
                  div(cls := "col-3")(
                    label(cls := "form-label text-light small mb-1")("Time (s)"),
                    input(cls := "form-control form-control-sm", id := "analysis-time", `type` := "number", value := "0", min := "0", max := "300", placeholder := "0=off")
                  ),
                  div(cls := "col-3")(
                    label(cls := "form-label text-light small mb-1")("Moves"),
                    input(cls := "form-control form-control-sm", id := "analysis-moves", `type` := "number", value := "3", min := "1", max := "10")
                  ),
                  div(cls := "col-3")(
                    label(cls := "form-label text-light small mb-1")("Candidates"),
                    input(cls := "form-control form-control-sm", id := "analysis-candidates", `type` := "number", value := "1", min := "1", max := "10")
                  )
                ),
                div(cls := "row g-2 mb-3")(
                  div(cls := "col-12")(
                    small(cls := "text-muted")("Depth: search plies. Time: seconds per move (0=depth only). Both can be combined.")
                  )
                )
              )
            ),
            div(cls := "puzzle__side")(
              div(cls := "puzzle__side__box")(
                div(cls := "puzzle__feedback")(
                  div(cls := "content")(if (puzzle.isDefined) "Edit your puzzle!" else "Create your custom puzzle!"),
                  div(id := "turn-text", cls := "badge bg-secondary mb-1")("Sente to move")
                )
              )
            )
          )
        ),
        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        script(src := "https://cdn.jsdelivr.net/npm/sweetalert2@11"),
        script(src := "https://cdnjs.cloudflare.com/ajax/libs/jquery/3.6.3/jquery.min.js", attr("integrity") := "sha512-STof4xm1wgkfm7heWqFJVn58Hm3EtS31XFaagaa8VMReCXAkQnJZ+jEy8PCC/iT18dFy95WcExNHFTqLyp72eQ==", attr("crossorigin") := "anonymous", attr("referrerpolicy") := "no-referrer"),
        puzzle.map { doc =>
          script(raw(s"window.__puzzleData = ${ujson.write(ujson.read(doc.toJson()))};"))
        }.getOrElse(()),
        script(src := "/js/puzzle-creator.js")
      )
    )
  }

  @cask.get("/puzzle-creator/list")
  def listCustomPuzzles(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val puzzles = Await.result(GameRepository.getCustomPuzzles(email), 10.seconds)
      
      val jsonArray = puzzles.map { doc =>
        ujson.read(doc.toJson())
      }
      cask.Response(
        ujson.Arr(jsonArray: _*),
        headers = Seq("Content-Type" -> "application/json")
      )
    }
  }

  @cask.post("/puzzle-creator/save")
  def saveCustomPuzzle(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val json = ujson.read(request.text())
      val name = json("name").str
      val sfen = json("sfen").str
      val isPublic = json.obj.get("isPublic").map(_.bool).getOrElse(false)
      val comments = json.obj.get("comments").map(_.str)
      val selectedSequence = json.obj.get("selectedSequence").map { seq =>
        seq.arr.map(_.str).toSeq
      }
      // Parse move comments as a map
      val moveComments = json.obj.get("moveComments").map { mc =>
        mc.obj.map { case (k, v) => k -> v.str }.toMap
      }
      val analysisData = json.obj.get("analysisData").map(_.str)
      val selectedCandidates = json.obj.get("selectedCandidates").map { sc =>
        sc.arr.map(_.num.toInt).toSeq
      }
      val id = json.obj.get("id").map(_.str)

      val result = id match {
        case Some(puzzleId) =>
          Await.result(GameRepository.updateCustomPuzzle(puzzleId, name, sfen, email, isPublic, comments, selectedSequence, moveComments, analysisData, selectedCandidates), 10.seconds)
          ujson.Obj("success" -> true, "message" -> "Puzzle updated successfully")
        case None =>
          Await.result(GameRepository.saveCustomPuzzle(name, sfen, email, isPublic, comments, selectedSequence, moveComments, analysisData, selectedCandidates), 10.seconds)
          ujson.Obj("success" -> true, "message" -> "Puzzle saved successfully")
      }
      
      cask.Response(result, headers = Seq("Content-Type" -> "application/json"))
    }
  }

  @cask.post("/puzzle-creator/delete")
  def deleteCustomPuzzle(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val json = ujson.read(request.text())
      val id = json("id").str
      
      Await.result(GameRepository.deleteCustomPuzzle(id, email), 10.seconds)
      cask.Response(
        ujson.Obj("success" -> true, "message" -> "Puzzle deleted successfully"),
        headers = Seq("Content-Type" -> "application/json")
      )
    }
  }

  @cask.post("/puzzle-creator/analyze")
  def analyzePosition(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val settings = Await.result(SettingsRepository.getAppSettings(Some(email)), 10.seconds)
      val json = ujson.read(request.text())
      val sfen = json("sfen").str
      val multiPv = json.obj.get("multiPv").map(_.num.toInt).getOrElse(3)
      val depth = json.obj.get("depth").map(_.num.toInt).getOrElse(15)
      val time = json.obj.get("time").map(_.num.toInt).filter(_ > 0) // milliseconds from frontend

      try {
        val engineManager = getEngineManager(settings.enginePath)
        val limit = Limit(depth = Some(depth), time = time)
        val results = engineManager.analyze(sfen, limit, multiPv)
        
        println(s"[PUZZLE-CREATOR] Engine returned ${results.length} results")
        results.foreach { result =>
          println(s"[PUZZLE-CREATOR] Result keys: ${result.keys.mkString(", ")}")
          println(s"[PUZZLE-CREATOR] Result: $result")
        }
        
        // Determine whose turn it is from the SFEN
        val colorToMove = sfenTurnToColor(sfen)
        
        val movesArray = results.map { result =>
          // Extract the first move from the PV (Principal Variation)
          val usiMove = result.get("pv") match {
            case Some(pvList: List[_]) if pvList.nonEmpty => pvList.head.toString
            case Some(pvStr: String) if pvStr.nonEmpty => pvStr.split(" ").head
            case _ => result.get("usi").orElse(result.get("move")).getOrElse("").toString
          }
          println(s"[PUZZLE-CREATOR] Extracted USI: $usiMove")
          
          // Use PovScore.forPlayer to get score from sente's perspective
          val povScore = Score.fromEngine(result.get("score"), colorToMove)
          val senteScore = povScore.forPlayer(Color.Sente)
          
          val (scoreKind, scoreValue) = senteScore match {
            case CpScore(cp) => ("cp", cp)
            case MateScore(moves) => ("mate", moves)
            case _ => ("cp", 0)
          }
          
          ujson.Obj(
            "usi" -> usiMove,
            "score" -> ujson.Obj("kind" -> scoreKind, "value" -> scoreValue),
            "depth" -> ujson.Num(result.getOrElse("depth", depth).toString.toInt),
            "pv" -> (result.get("pv") match {
              case Some(pvList: List[_]) => pvList.mkString(" ")
              case Some(pvStr: String) => pvStr
              case _ => ""
            })
          )
        }
        
        cask.Response(
          ujson.Obj("success" -> true, "moves" -> ujson.Arr(movesArray: _*)),
          headers = Seq("Content-Type" -> "application/json")
        )
      } catch {
        case e: Exception =>
          cask.Response(
            ujson.Obj("success" -> false, "error" -> e.getMessage),
            headers = Seq("Content-Type" -> "application/json"),
            statusCode = 500
          )
      }
    }
  }

  @cask.post("/puzzle-creator/analyze-sequence")
  def analyzeSequence(request: cask.Request) = {
    withAuthJson(request, "puzzle-creator") { email =>
      val settings = Await.result(SettingsRepository.getAppSettings(Some(email)), 10.seconds)
      val json = ujson.read(request.text())
      val sfen = json("sfen").str
      val numMoves = json.obj.get("numMoves").map(_.num.toInt).getOrElse(3)
      val multiPv = json.obj.get("multiPv").map(_.num.toInt).getOrElse(1)
      
      try {
        val engineManager = getEngineManager(settings.enginePath)

        // Analyze with specified depth, time, and number of candidates
        val depth = json.obj.get("depth").map(_.num.toInt).getOrElse(15)
        val time = json.obj.get("time").map(_.num.toInt).filter(_ > 0) // seconds
        val sequences = analyzeMoveSequences(engineManager, sfen, numMoves, depth, multiPv, time)
        
        cask.Response(
          ujson.Obj("success" -> true, "sequences" -> ujson.Arr(sequences.map(seq => ujson.Arr(seq: _*)): _*)),
          headers = Seq("Content-Type" -> "application/json")
        )
      } catch {
        case e: Exception =>
          cask.Response(
            ujson.Obj("success" -> false, "error" -> e.getMessage),
            headers = Seq("Content-Type" -> "application/json"),
            statusCode = 500
          )
      }
    }
  }

  private def analyzeMoveSequences(engineManager: EngineManager, initialSfen: String, numMoves: Int, depth: Int, multiPv: Int, timeSec: Option[Int] = None): Vector[Vector[ujson.Obj]] = {
    // Build limit with depth and optional time (converted to milliseconds)
    val limit = Limit(depth = Some(depth), time = timeSec.map(_ * 1000))

    // Get more candidates than requested to filter out duplicates
    val searchMultiPv = math.max(multiPv * 3, 10) // Get 3x more candidates to find unique ones

    // Get all candidate first moves from the initial position
    val initialResults = engineManager.analyze(initialSfen, limit, searchMultiPv)
    
    // Determine whose turn it is from the initial SFEN
    val initialColorToMove = sfenTurnToColor(initialSfen)
    
    // Extract unique first moves with their scores
    val uniqueFirstMoves = scala.collection.mutable.LinkedHashMap[String, (String, Score)]()
    
    initialResults.foreach { result =>
      val usiMove = result.get("pv") match {
        case Some(pvList: List[_]) if pvList.nonEmpty => pvList.head.toString
        case Some(pvStr: String) if pvStr.nonEmpty => pvStr.split(" ").head
        case _ => result.get("usi").orElse(result.get("move")).getOrElse("").toString
      }
      
      // Only add if we haven't seen this move yet
      if (!uniqueFirstMoves.contains(usiMove) && usiMove.nonEmpty) {
        // Use PovScore.forPlayer to get score from sente's perspective
        val povScore = Score.fromEngine(result.get("score"), initialColorToMove)
        val senteScore = povScore.forPlayer(Color.Sente)
        uniqueFirstMoves(usiMove) = (usiMove, senteScore)
      }
    }
    
    println(s"[PUZZLE-CREATOR] Found ${uniqueFirstMoves.size} unique first moves out of ${initialResults.length} candidates")
    
    // Build sequences for each unique first move (up to multiPv)
    val sequences = Vector.newBuilder[Vector[ujson.Obj]]
    var candidateCount = 0
    
    uniqueFirstMoves.keys.take(multiPv).foreach { firstMove =>
      if (candidateCount < multiPv) {
        val moves = Vector.newBuilder[ujson.Obj]
        val moveHistory = scala.collection.mutable.ArrayBuffer[String](firstMove)
        
        // Add the first move with score already converted to sente's perspective
        val firstScore = uniqueFirstMoves(firstMove)._2
        val (firstScoreKind, firstScoreValue) = firstScore match {
          case CpScore(cp) => ("cp", cp)
          case MateScore(moves) => ("mate", moves)
          case _ => ("cp", 0)
        }
        
        moves += ujson.Obj(
          "moveNum" -> 1,
          "usi" -> firstMove,
          "score" -> ujson.Obj("kind" -> firstScoreKind, "value" -> firstScoreValue),
          "depth" -> depth,
          "sfenBefore" -> initialSfen,
          "pv" -> firstMove
        )
        
        // Analyze subsequent moves
        for (moveNum <- 1 until numMoves) {
          val results = engineManager.analyzeWithMoves(initialSfen, moveHistory.toSeq, limit, 1)
          
          if (results.nonEmpty && results.head.contains("pv")) {
            val pv = results.head("pv").asInstanceOf[List[String]]
            
            if (pv.nonEmpty) {
              val bestMove = pv.head
              
              // Determine whose turn it is for this move (color to move in the analyzed position)
              val isCurrentSente = isSenteTurn(moveNum + 1, initialSfen)
              val currentColorToMove = if (isCurrentSente) Color.Sente else Color.Gote
              
              // Use PovScore.forPlayer to get score from sente's perspective
              val povScore = Score.fromEngine(results.head.get("score"), currentColorToMove)
              val senteScore = povScore.forPlayer(Color.Sente)
              
              val (scoreKind, scoreValue) = senteScore match {
                case CpScore(cp) => ("cp", cp)
                case MateScore(m) => ("mate", m)
                case _ => ("cp", 0)
              }
              
              moves += ujson.Obj(
                "moveNum" -> (moveNum + 1),
                "usi" -> bestMove,
                "score" -> ujson.Obj("kind" -> scoreKind, "value" -> scoreValue),
                "depth" -> depth,
                "sfenBefore" -> initialSfen,
                "pv" -> pv.mkString(" ")
              )
              
              moveHistory += bestMove
            }
          }
        }
        
        sequences += moves.result()
        candidateCount += 1
      }
    }
    
    println(s"[PUZZLE-CREATOR] Generated ${sequences.result().length} sequences with unique first moves")
    sequences.result()
  }
  
  /**
   * Convert SFEN turn notation to Color.
   * SFEN format: board turn hands moves
   * turn is 'b' for sente (Black) and 'w' for gote (White)
   */
  private def sfenTurnToColor(sfen: String): Color = {
    val parts = sfen.split(" ")
    if (parts.length >= 2) {
      if (parts(1) == "b") Color.Sente else Color.Gote
    } else {
      Color.Sente // Default to sente (Sente) if SFEN is malformed
    }
  }
  
  /**
   * Determine if a move number is sente's turn.
   * Move numbers are 1-indexed:
   * - Odd move numbers (1, 3, 5, ...) are sente's turn
   * - Even move numbers (2, 4, 6, ...) are gote's turn
   */
  private def isSenteTurn(moveNum: Int, initialSfen: String): Boolean = {
    val initialIsSente = sfenTurnToColor(initialSfen) == Color.Sente
    if (initialIsSente) {
      // Initial position is sente, so odd moves are sente
      (moveNum % 2) == 1
    } else {
      // Initial position is gote, so even moves are sente
      (moveNum % 2) == 0
    }
  }

  initialize()
}
