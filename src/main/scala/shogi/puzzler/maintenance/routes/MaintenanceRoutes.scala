package shogi.puzzler.maintenance.routes

import shogi.Color
import java.io.File
import cask._
import ujson.Value
import scalatags.Text.all._
import scala.concurrent.duration._
import scala.concurrent.Await
import shogi.puzzler.db.{GameRepository, PuzzleRepository, SettingsRepository, AppSettings, TrainingRepository, OCRRepository, OCRHistoryEntry}
import shogi.puzzler.i18n.I18n
import shogi.puzzler.ui.Components
import shogi.puzzler.game.{GameLoader, KifAnnotator}
import shogi.puzzler.analysis.{GameAnalyzer, PuzzleExtractor}
import shogi.puzzler.engine.EngineManager
import shogi.puzzler.serialization.PuzzleJsonSerializer
import shogi.puzzler.domain.SearchGame
import shogi.puzzler.maintenance.{
  ShogiWarsSource,
  LishogiSource,
  Dojo81Source,
  TaskManager,
  AnalysisQueue
}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration._
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

object MaintenanceRoutes extends BaseRoutes {

  private val gamesCache = mutable.Map[String, Seq[SearchGame]]()
  private val engineManagers = mutable.Map[String, EngineManager]()

  private val sources = Map(
    "shogiwars" -> ShogiWarsSource,
    "lishogi" -> LishogiSource,
    "dojo81" -> Dojo81Source
  )

  def getEngineManager(name: String): EngineManager = {
    logger.info(s"[MAINTENANCE] Requesting engine manager for: $name")
    engineManagers.getOrElseUpdate(name, {
      logger.info(s"[MAINTENANCE] Creating new engine manager for: $name")
      val manager = new EngineManager(Seq(name))
      manager.initialize()
      manager
    })
  }

  @cask.get("/ocr")
  def ocr(request: cask.Request): cask.Response[String] = {
    withAuth(request, "ocr") { email =>
      val targetEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(targetEmail), 10.seconds)
      val pageLang = getLang(request)
      val history = Await.result(OCRRepository.getOCRHistory(Some(email).getOrElse("global")), 10.seconds)

      cask.Response(
        renderOcrPage(Some(email), settings, history, pageLang).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  @cask.get("/ocr/new")
  def ocrNew(request: cask.Request): cask.Response[String] = {
    withAuth(request, "ocr") { email =>
      val targetEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(targetEmail), 10.seconds)
      val pageLang = getLang(request)

      cask.Response(
        renderOcrEditPage(Some(email), settings, None, pageLang).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  @cask.get("/ocr/edit/:id")
  def ocrEdit(id: String, request: cask.Request): cask.Response[String] = {
    withAuth(request, "ocr") { email =>
      val targetEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(targetEmail), 10.seconds)
      val pageLang = getLang(request)
      val entry = Await.result(OCRRepository.getOCRById(id), 10.seconds)

      if (entry.isEmpty || entry.get.userEmail != email) {
        noCacheRedirect("/ocr")
      } else {
        cask.Response(
          renderOcrEditPage(Some(email), settings, entry, pageLang).render,
          headers = Seq("Content-Type" -> "text/html; charset=utf-8")
        )
      }
    }
  }

  @cask.post("/ocr/save")
  def ocrSave(request: cask.Request): cask.Response[String] = {
    val userEmail = getSessionUserEmail(request)
    if (userEmail.isEmpty) {
      cask.Response(ujson.write(ujson.Obj("error" -> "Unauthorized")), statusCode = 401, headers = Seq("Content-Type" -> "application/json"))
    } else {
      try {
        val json = ujson.read(request.readAllBytes())
        val id = json.obj.get("id").filter(_.str.nonEmpty).map(_.str)
        val image_data = json("image").str
        val boardRect = json("boardRect").obj.map { case (k, v) => k -> v.num.toInt }.toMap
        val senteRect = json.obj.get("senteRect").map(_.obj.map { case (k, v) => k -> v.num.toInt }.toMap)
        val goteRect = json.obj.get("goteRect").map(_.obj.map { case (k, v) => k -> v.num.toInt }.toMap)
        val sfen = json("sfen").str
        val manualAdjustments = json("manualAdjustments").obj.map { case (k, v) => k -> v.str }.toMap
        val comment = json("comment").str
        val pieceReferences = json.obj.get("pieceReferences").map(_.obj.map { case (k, v) => k -> v.str }.toMap).getOrElse(Map.empty)

        val savedId = Await.result(OCRRepository.saveOCRHistory(
          userEmail.getOrElse("global"),
          image_data,
          boardRect,
          senteRect,
          goteRect,
          sfen,
          manualAdjustments,
          comment,
          id,
          pieceReferences
        ), 10.seconds)

        cask.Response(ujson.write(ujson.Obj("success" -> savedId.isDefined, "id" -> ujson.Str(savedId.getOrElse("")))), headers = Seq("Content-Type" -> "application/json"))
      } catch {
        case e: Exception =>
          logger.error(s"Failed to save OCR history entry: ${e.getMessage}")
          cask.Response(ujson.write(ujson.Obj("error" -> e.getMessage)), statusCode = 500, headers = Seq("Content-Type" -> "application/json"))
      }
    }
  }

  @cask.post("/ocr/delete/:id")
  def ocrDelete(id: String, request: cask.Request): cask.Response[String] = {
    val userEmail = getSessionUserEmail(request)
    if (userEmail.isEmpty) {
      cask.Response(ujson.write(ujson.Obj("error" -> "Unauthorized")), statusCode = 401, headers = Seq("Content-Type" -> "application/json"))
    } else {
      val success = Await.result(OCRRepository.deleteOCR(id, userEmail.get), 10.seconds)
      cask.Response(ujson.write(ujson.Obj("success" -> success)), headers = Seq("Content-Type" -> "application/json"))
    }
  }

  @cask.post("/ocr/save-regions")
  def ocrSaveRegions(request: cask.Request): cask.Response[String] = {
    val userEmail = getSessionUserEmail(request)
    if (userEmail.isEmpty) {
      cask.Response(ujson.write(ujson.Obj("error" -> "Unauthorized")), statusCode = 401, headers = Seq("Content-Type" -> "application/json"))
    } else {
      try {
        val targetEmail = userEmail.get
        val json = ujson.read(request.readAllBytes())
        val profileName = json.obj.get("profile").map(_.str).getOrElse("Default")
        val regionsJson = json("regions").obj
        
        val regions = regionsJson.map { case (k, v) =>
          k -> shogi.puzzler.maintenance.Rect(
            v("x").num.toInt,
            v("y").num.toInt,
            v("w").num.toInt,
            v("h").num.toInt
          )
        }.toMap
        
        val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
        val updatedProfiles = settings.ocrProfiles + (profileName -> regions)
        val updatedSettings = settings.copy(ocrProfiles = updatedProfiles, activeOcrProfile = Some(profileName))
        
        Await.result(SettingsRepository.updateGlobalOcrProfiles(updatedProfiles), 10.seconds)
        Await.result(SettingsRepository.saveAppSettings(targetEmail, updatedSettings), 10.seconds)

        // Update all OCR history entries with these new regions
        val br = regions("board")
        val boardRect = Map("x" -> br.x, "y" -> br.y, "w" -> br.width, "h" -> br.height)
        val senteRect = regions.get("sente").map(r => Map("x" -> r.x, "y" -> r.y, "w" -> r.width, "h" -> r.height))
        val goteRect = regions.get("gote").map(r => Map("x" -> r.x, "y" -> r.y, "w" -> r.width, "h" -> r.height))
        Await.result(OCRRepository.updateAllHistoryRegions(boardRect, senteRect, goteRect), 10.seconds)
        
        cask.Response(ujson.write(ujson.Obj("success" -> true)), headers = Seq("Content-Type" -> "application/json"))
      } catch {
        case e: Exception =>
          logger.error(s"Failed to save OCR regions: ${e.getMessage}")
          cask.Response(ujson.write(ujson.Obj("error" -> e.getMessage)), statusCode = 500, headers = Seq("Content-Type" -> "application/json"))
      }
    }
  }

  @cask.post("/ocr/delete-profile")
  def ocrDeleteProfile(request: cask.Request): cask.Response[String] = {
    val userEmail = getSessionUserEmail(request)
    if (userEmail.isEmpty) {
      cask.Response(ujson.write(ujson.Obj("error" -> "Unauthorized")), statusCode = 401, headers = Seq("Content-Type" -> "application/json"))
    } else {
      try {
        val targetEmail = userEmail.get
        val json = ujson.read(request.readAllBytes())
        val profileName = json("profile").str
        
        val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
        val updatedProfiles = settings.ocrProfiles - profileName
        val newActiveProfile = if (settings.activeOcrProfile.contains(profileName)) updatedProfiles.keys.headOption else settings.activeOcrProfile
        val updatedSettings = settings.copy(ocrProfiles = updatedProfiles, activeOcrProfile = newActiveProfile)
        
        Await.result(SettingsRepository.updateGlobalOcrProfiles(updatedProfiles), 10.seconds)
        Await.result(SettingsRepository.saveAppSettings(targetEmail, updatedSettings), 10.seconds)
        
        cask.Response(ujson.write(ujson.Obj("success" -> true)), headers = Seq("Content-Type" -> "application/json"))
      } catch {
        case e: Exception =>
          logger.error(s"Failed to delete OCR profile: ${e.getMessage}")
          cask.Response(ujson.write(ujson.Obj("error" -> e.getMessage)), statusCode = 500, headers = Seq("Content-Type" -> "application/json"))
      }
    }
  }

  @cask.post("/ocr/select-profile")
  def ocrSelectProfile(request: cask.Request): cask.Response[String] = {
    val userEmail = getSessionUserEmail(request)
    if (userEmail.isEmpty) {
      cask.Response(ujson.write(ujson.Obj("error" -> "Unauthorized")), statusCode = 401, headers = Seq("Content-Type" -> "application/json"))
    } else {
      try {
        val targetEmail = userEmail.get
        val json = ujson.read(request.readAllBytes())
        val profileName = json("profile").str
        
        val settings = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
        val updatedSettings = settings.copy(activeOcrProfile = Some(profileName))
        
        Await.result(SettingsRepository.saveAppSettings(targetEmail, updatedSettings), 10.seconds)

        val regions = updatedSettings.ocrProfiles.get(profileName).getOrElse(shogi.puzzler.maintenance.ShogiOCR.detectDefaultRegions())
        val regionsJson = ujson.Obj()
        regions.foreach { case (k, v) =>
          regionsJson(k) = ujson.Obj("x" -> v.x, "y" -> v.y, "w" -> v.width, "h" -> v.height)
        }
        
        cask.Response(ujson.write(ujson.Obj("success" -> true, "regions" -> regionsJson)), headers = Seq("Content-Type" -> "application/json"))
      } catch {
        case e: Exception =>
          logger.error(s"Failed to select OCR profile: ${e.getMessage}")
          cask.Response(ujson.write(ujson.Obj("error" -> e.getMessage)), statusCode = 500, headers = Seq("Content-Type" -> "application/json"))
      }
    }
  }

  def renderOcrPage(userEmail: Option[String], settings: AppSettings, history: Seq[OCRHistoryEntry], pageLang: String = I18n.defaultLang) = {
    Components.layout(
      "OCR Library",
      userEmail,
      settings,
      appVersion,
      lang = pageLang,
      scripts = Seq(
        link(rel := "stylesheet", href := "/assets/css/shogiground.css"),
        raw(tag("style")("""
          .ocr-history-card {
            background: #2e2a24;
            border: 1px solid #444;
            border-radius: 8px;
            margin-bottom: 20px;
            overflow: hidden;
            display: flex;
            height: 150px;
          }
          @media (max-width: 768px) {
            .ocr-history-card {
              flex-direction: column;
              height: auto;
            }
            .ocr-history-img {
              width: 100% !important;
              height: 200px !important;
              border-right: none !important;
              border-bottom: 1px solid #444;
            }
            .ocr-history-content {
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
          .ocr-history-img {
            width: 150px;
            height: 150px;
            object-fit: cover;
            border-right: 1px solid #444;
          }
          .ocr-history-content {
            padding: 15px;
            flex-grow: 1;
            display: flex;
            flex-direction: column;
            justify-content: space-between;
          }
          .ocr-history-comment {
            font-style: italic;
            color: #ccc;
            margin-bottom: 5px;
          }
          .ocr-history-date {
            font-size: 0.8em;
            color: #888;
          }
          .ocr-history-actions {
            display: flex;
            gap: 10px;
            align-items: center;
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
          h2("OCR Library"),
          a(href := "/ocr/new", cls := "btn btn-success")("New OCR Scan")
        ),
        if (history.isEmpty) {
          div(cls := "alert alert-info")("No OCR history found. Start by adding a new scan!")
        } else {
          div(
            history.map { entry =>
              div(cls := "ocr-history-card")(
                img(cls := "ocr-history-img", src := entry.image_data),
                div(cls := "ocr-history-content")(
                  div(
                    div(cls := "ocr-history-comment")(if (entry.comment.nonEmpty) entry.comment else "No comment"),
                    div(cls := "ocr-history-date")(new java.util.Date(entry.timestamp).toString),
                    if (entry.sfen.nonEmpty) {
                      div(cls := "sfen-container")(
                        input(cls := "sfen-input", value := entry.sfen, readonly := true),
                        button(cls := "copy-btn", onclick := s"window.copySfen('${entry.sfen}')")("Copy"),
                        a(cls := "lishogi-link", href := s"https://lishogi.org/analysis/${entry.sfen.replace(" ", "_")}", target := "_blank", rel := "noopener")("Lishogi")
                      )
                    } else div()
                  ),
                  div(cls := "ocr-history-actions")(
                    a(href := s"/ocr/edit/${entry.id}", cls := "btn btn-primary btn-sm")("Edit"),
                    button(cls := "btn btn-danger btn-sm", onclick := s"window.deleteOcr('${entry.id}')")("Delete")
                  )
                )
              )
            }
          )
        },
        script(raw("""
          window.deleteOcr = function(id) {
            if (confirm('Are you sure you want to delete this OCR entry?')) {
              fetch('/ocr/delete/' + id, { method: 'POST' })
                .then(r => r.json())
                .then(data => {
                  if (data.success) location.reload();
                  else alert('Delete failed: ' + data.error);
                });
            }
          }
          window.copySfen = function(sfen) {
            navigator.clipboard.writeText(sfen).then(() => {
              // Optionally show some feedback
              console.log('SFEN copied to clipboard');
            });
          }
        """))
      )
    )
  }

  def renderOcrEditPage(userEmail: Option[String], settings: AppSettings, entry: Option[OCRHistoryEntry], pageLang: String = I18n.defaultLang) = {
    val trainingCount = Await.result(TrainingRepository.countPieces(), 10.seconds)
    Components.layout(
      if (entry.isDefined) "Edit OCR Scan" else "New OCR Scan",
      userEmail,
      settings,
      appVersion,
      lang = pageLang,
      scripts = Seq(
        link(rel := "stylesheet", href := "/assets/css/shogiground.css"),
        link(rel := "stylesheet", href := "/assets/css/portella.css"),
        tag("style")("""
          .ocr-board-container {
            display: flex;
            flex-direction: column;
            gap: 10px;
            background-color: #2e2a24;
            padding: 20px;
            border-radius: 5px;
            border: 1px solid #444;
            margin: 0 auto;
          }
          @media (max-width: 768px) {
            .ocr-board-container {
              padding: 10px;
            }
            .piece-picker-grid {
              grid-template-columns: repeat(4, 1fr) !important;
            }
            .picker-section-label {
              grid-column: span 4 !important;
            }
            .card-body {
              padding: 10px;
            }
            h1 {
              font-size: 1.5rem;
            }
            .btn-group-regions {
              display: flex;
              width: 100%;
            }
            .btn-group-regions .btn {
              flex: 1;
              padding: 5px 2px;
              font-size: 0.8rem;
            }
            .squares-grid {
              grid-template-columns: repeat(9, 1fr) !important;
              gap: 2px !important;
              width: 100% !important;
              min-width: 320px !important;
              padding-bottom: 15px !important;
            }
            .squares-grid-container {
              overflow-x: auto !important;
              display: block !important;
              width: 100% !important;
              max-width: 100vw !important;
              -webkit-overflow-scrolling: touch;
              margin-bottom: 15px;
            }
            .square-item img {
              width: 100% !important;
              max-width: 40px !important;
              height: auto !important;
              aspect-ratio: 1 / 1;
            }
            .square-item {
              padding: 2px !important;
            }
            .square-item .current-piece {
              font-size: 10px !important;
            }
            .square-item .piece-count {
              font-size: 9px !important;
              margin-bottom: 2px !important;
            }
            .ocr-badge {
              font-size: 8px !important;
              padding: 0 2px !important;
            }
            .hand-grid {
              grid-template-columns: repeat(7, 1fr) !important;
              gap: 2px !important;
              width: 100% !important;
              max-width: 320px !important;
              padding-bottom: 10px !important;
            }
            .ocr-board-container {
              max-width: 100% !important;
              padding: 5px;
              margin: 0;
            }
            .ocr-board-container .sg-wrap {
              width: 100% !important;
              max-width: none !important;
              height: auto !important;
              aspect-ratio: 11 / 12;
            }
          }
          .ocr-board-container sg-hand-wrap {
            width: 100%;
            height: 45px;
            background: #262421;
            border-radius: 3px;
            display: block;
            border: 1px solid #333;
            overflow: hidden;
          }
          .ocr-board-container sg-hand-wrap sg-hand {
            display: flex;
            flex-direction: row;
            flex-wrap: nowrap;
            height: 100%;
            width: 100%;
            padding: 2px;
            align-items: center;
            justify-content: flex-start;
          }
          .ocr-board-container sg-hand-wrap sg-hand sg-hp-wrap {
            flex: 0 0 12.5%;
            height: 100%;
            position: relative;
          }
          .ocr-board-container sg-hand-wrap sg-hand sg-hp-wrap piece {
            width: 100% !important;
            height: 100% !important;
            background-size: contain;
            background-position: center;
            background-repeat: no-repeat;
            display: block;
            position: static !important;
            margin: 0 !important;
            transform: none !important;
          }
          .ocr-board-container .hand-top sg-hand {
            flex-direction: row-reverse !important;
            justify-content: flex-end !important;
          }
          .ocr-board-container sg-hand-wrap sg-hand sg-hp-wrap:after {
            content: attr(data-nb);
            position: absolute;
            bottom: 0;
            right: 0;
            background: rgba(0,0,0,0.7);
            color: #fff;
            font-size: 10px;
            padding: 0 2px;
            border-radius: 2px;
            line-height: 1;
          }
          .ocr-board-container sg-hand-wrap sg-hand sg-hp-wrap[data-nb="0"] {
            display: none;
          }
          .ocr-board-container sg-hand-wrap sg-hand sg-hp-wrap[data-nb="1"]:after {
            display: none;
          }
          .ocr-board-container .sg-wrap {
            width: 100%;
            max-width: 400px;
            height: auto;
            aspect-ratio: 11 / 12;
            position: relative;
            background-image: url(/assets/images/boards/wood.png);
            background-size: cover;
            box-shadow: 0 4px 8px rgba(0,0,0,0.5);
            margin: 0 auto;
          }
          #ocr-board {
            width: 100%;
            height: 100%;
          }
          #piece-picker-modal h5 {
            color: #fff;
            margin-bottom: 15px;
            border-bottom: 1px solid #444;
            padding-bottom: 10px;
          }
          .ocr-board-container coords {
            font-weight: bold;
            color: #eee;
          }
          #ocr-canvas-container {
            position: relative;
            display: inline-block;
            margin-top: 20px;
            user-select: none;
          }
          #ocr-canvas-container canvas {
            max-width: 100%;
            height: auto;
            display: block;
            margin: 0 auto;
          }
          .region-rect {
            position: absolute;
            border: 2px solid yellow;
            background: rgba(255, 255, 0, 0.2);
            pointer-events: none;
            box-sizing: border-box;
          }
          .region-rect.active {
            border-color: #00ff00;
            background: rgba(0, 255, 0, 0.1);
          }
          .squares-grid-container {
            width: 100%;
            overflow-x: auto;
            -webkit-overflow-scrolling: touch;
            display: block;
          }
          .squares-grid {
            display: grid;
            grid-template-columns: repeat(9, 1fr);
            gap: 5px;
            margin-top: 20px;
            background: #2e2a24;
            padding: 10px;
            border-radius: 5px;
          }
          .hand-grid {
            display: grid;
            grid-template-columns: repeat(7, 1fr);
            gap: 5px;
            margin-top: 10px;
            background: #262421;
            padding: 10px;
            border-radius: 5px;
            border: 1px solid #444;
          }
          .hand-label {
            grid-column: span 7;
            font-size: 12px;
            color: #aaa;
            margin-bottom: 5px;
            border-bottom: 1px solid #333;
          }
          .square-item {
            display: flex;
            flex-direction: column;
            align-items: center;
            background: #3e3a34;
            padding: 5px;
            border-radius: 3px;
            border: 1px solid #555;
            cursor: pointer;
            transition: background 0.2s;
            position: relative;
          }
          .square-item:hover {
            background: #4e4a44;
          }
          .square-item img {
            width: 48px;
            height: 48px;
            object-fit: contain;
            background: white;
            margin-bottom: 5px;
            border-radius: 2px;
          }
          .square-item .ocr-badge {
            position: absolute;
            top: 2px;
            right: 2px;
            font-size: 10px;
            background: rgba(0,0,0,0.8);
            color: #eee;
            padding: 1px 4px;
            border-radius: 3px;
            border: 1px solid #555;
            white-space: nowrap;
          }
          .square-item .current-piece {
            font-weight: bold;
            font-size: 14px;
            color: #fff;
            min-height: 21px;
          }
          .square-item .piece-count {
            font-size: 12px;
            color: #f0ad4e;
            font-weight: bold;
            margin-top: -5px;
            margin-bottom: 5px;
          }
          .square-item.changed {
            border-color: #f0ad4e;
            background: rgba(240, 173, 78, 0.1);
          }
          .square-item.verified {
            border-color: #5cb85c;
            background: rgba(92, 184, 92, 0.1);
          }
          .square-item.is-empty {
            opacity: 0.6;
          }
          
          #piece-picker-modal {
            display: none;
            position: fixed;
            z-index: 1050;
            left: 0;
            top: 0;
            width: 100%;
            height: 100%;
            background-color: rgba(0,0,0,0.8);
            align-items: center;
            justify-content: center;
          }
          .piece-picker-content {
            background: #2e2a24;
            padding: 20px;
            border-radius: 10px;
            border: 1px solid #444;
            max-width: 600px;
            width: 95%;
            max-height: 90vh;
            overflow-y: auto;
          }
          .piece-picker-grid {
            display: grid;
            grid-template-columns: repeat(8, 1fr);
            gap: 5px;
            margin-top: 15px;
          }
          .picker-section-label {
            grid-column: span 8;
            font-size: 14px;
            color: #aaa;
            margin-top: 10px;
            padding-bottom: 5px;
            border-bottom: 1px solid #444;
            text-align: left;
          }
          .picker-item {
            aspect-ratio: 1;
            display: flex;
            align-items: center;
            justify-content: center;
            background: #3e3a34;
            border: 1px solid #555;
            border-radius: 5px;
            cursor: pointer;
            transition: transform 0.1s, background 0.2s;
            position: relative;
            overflow: hidden;
            padding: 5px;
          }
          .picker-item piece {
            width: 100% !important;
            height: 100% !important;
            background-size: contain !important;
            background-position: center !important;
            background-repeat: no-repeat !important;
            display: block;
            position: relative !important;
            transform: none !important;
          }
          .picker-item.empty-item {
            color: #777;
            font-style: italic;
          }
          .picker-item:hover {
            background: #4e4a44;
            transform: scale(1.1);
            border-color: #777;
          }
          .picker-item.active {
            background: #5cb85c;
            border-color: #4cae4c;
          }
          .region-label {
            position: absolute;
            background: rgba(0, 0, 0, 0.7);
            color: white;
            font-size: 12px;
            padding: 2px 5px;
            top: -22px;
            left: -2px;
            white-space: nowrap;
          }
          .btn-group-regions .btn.active {
             background-color: #00ff00;
             color: black;
          }
        """),
        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        script(src := "/js/ocr.js", `type` := "module"),
        script(raw(s"""
          window.initialData = ${entry.map { e =>
            val initialData = ujson.Obj(
              "image" -> e.image_data,
              "boardRect" -> ujson.Obj.from(e.boardRect.map { case (k, v) => k -> ujson.Num(v) }),
              "senteRect" -> ujson.Obj.from(e.senteRect.map(r => r.map { case (k, v) => k -> ujson.Num(v) }).getOrElse(Map.empty)),
              "goteRect" -> ujson.Obj.from(e.goteRect.map(r => r.map { case (k, v) => k -> ujson.Num(v) }).getOrElse(Map.empty)),
              "sfen" -> e.sfen,
              "manualAdjustments" -> ujson.Obj.from(e.manualAdjustments.map { case (k, v) => k -> ujson.Str(v) }),
              "pieceReferences" -> ujson.Obj.from(e.pieceReferences.map { case (k, v) => k -> ujson.Str(v) }),
              "comment" -> e.comment
            )
            ujson.write(initialData).replace("&", "\\u0026").replace("</", "<\\/")
          }.getOrElse("null")};
        """))
      )
    )(
      div(cls := "container mt-4")(
        h1(cls := "mb-4")("Image to SFEN"),
        div(cls := "card bg-dark text-light border-secondary mb-4")(
          div(cls := "card-header border-secondary")(h5("Result & Save")),
          div(cls := "card-body")(
            div(cls := "row g-3 align-items-end")(
              div(cls := "col-12 col-md-5")(
                label(`for` := "sfenResult", cls := "form-label")("Result SFEN"),
                div(cls := "input-group")(
                  input(`type` := "text", id := "sfenResult", cls := "form-control bg-dark text-light border-secondary", readonly := true),
                  button(cls := "btn btn-outline-secondary", `type` := "button", id := "copySfen")(i(cls := "bi bi-clipboard")),
                  a(id := "lishogi-link", href := "#", target := "_blank", cls := "btn btn-outline-primary", title := "Open in Lishogi")(i(cls := "bi bi-box-arrow-up-right"))
                )
              ),
              div(cls := "col-12 col-md-2")(
                label(`for` := "ocrProfileSelect", cls := "form-label")("OCR Profile"),
                div(cls := "input-group")(
                  select(id := "ocrProfileSelect", cls := "form-select bg-dark text-light border-secondary", style := "font-size: 0.85rem;")(
                    if (settings.ocrProfiles.isEmpty) {
                      option(value := "Default")("Default")
                    } else {
                      settings.ocrProfiles.keys.toSeq.sorted.map { profileName =>
                        option(value := profileName, if (settings.activeOcrProfile.contains(profileName)) attr("selected") := "selected" else "") (profileName)
                      }
                    }
                  ),
                  button(`type` := "button", id := "btnNewProfile", cls := "btn btn-outline-success", title := "New Profile")(i(cls := "bi bi-plus-lg")),
                  button(`type` := "button", id := "btnDeleteProfile", cls := "btn btn-outline-danger", title := "Delete Profile")(i(cls := "bi bi-trash"))
                )
              ),
              div(cls := "col-12 col-md-3")(
                label(`for` := "ocr-comment", cls := "form-label")("Comment"),
                input(`type` := "text", id := "ocr-comment", cls := "form-control bg-dark text-light border-secondary", 
                  value := entry.map(_.comment).getOrElse(""), placeholder := "Add a comment...")
              ),
              div(cls := "col-12 col-md-2")(
                button(id := "save-ocr-btn", cls := "btn btn-success w-100", onclick := "window.saveOcrEntry()")("Save to Library")
              )
            )
          )
        ),
        div(cls := "row")(
          div(cls := "col-12 col-md-6")(
            div(cls := "card bg-dark text-light border-secondary mb-4")(
              div(cls := "card-header border-secondary")(h5("Upload Image")),
              div(cls := "card-body")(
                div(cls := "mb-3")(
                  input(`type` := "file", id := "imageUpload", cls := "form-control bg-dark text-light border-secondary", accept := "image/*")
                ),
                div(id := "regionControls", cls := "mb-3", style := "display: none;")(
                   div(cls := "small text-muted mb-2")("Click and drag on the image to define the region."),
                   div(cls := "btn-group w-100 mb-2")(
                     button(`type` := "button", id := "processModel", cls := "btn btn-warning", onclick := "window.identifySquaresWithMode('model')")("Identify with Model"),
                     button(`type` := "button", id := "processOcr", cls := "btn btn-info", onclick := "window.identifySquaresWithMode('tesseract')")("Identify with OCR"),
                     button(`type` := "button", id := "loadFromDb", cls := "btn btn-outline-warning", style := (if (entry.isDefined) "display: inline-block" else "display: none"), onclick := "window.loadFromDb()")("Load from DB")
                   ),
                   button(`type` := "button", id := "identifySquares", cls := "btn btn-secondary w-100 mt-2", onclick := "window.identifySquaresWithOCR(true)")("Identify Squares (no OCR)"),
                   div(id := "ocrStatus", cls := "small text-info mt-2", style := "display: none;"),
                   div(id := "pieceCountStatus", cls := "small mt-2", style := "display: none;"),
                   div(cls := "squares-grid-container")(
                     div(id := "goteHandGrid", cls := "hand-grid", style := "display: none;")
                   ),
                   div(cls := "squares-grid-container")(
                     div(id := "squaresGrid", cls := "squares-grid")
                   ),
                   div(cls := "squares-grid-container")(
                     div(id := "senteHandGrid", cls := "hand-grid", style := "display: none;")
                   ),
                   div(cls := "row mt-2")(
                     div(cls := "col-6")(
                       button(`type` := "button", id := "updateSfenFromVerified", cls := "btn btn-outline-primary w-100", style := "display:none", onclick := "window.updateSfenFromVerified()")("Update SFEN")
                     ),
                     div(cls := "col-6")(
                       button(`type` := "button", id := "saveAllVerified", cls := "btn btn-success w-100", style := "display:none", onclick := "window.saveAllVerified()")("Save to DB")
                     )
                   )
                 ),
              div(id := "piece-picker-modal")(
                 div(cls := "piece-picker-content")(
                    h5("Select Piece"),
                    div(id := "piece-picker-grid", cls := "piece-picker-grid"),
                    div(id := "piece-picker-count-group", cls := "mt-3", style := "display: none;")(
                      label(cls := "form-label text-light")("Count:"),
                      div(cls := "input-group")(
                        button(cls := "btn btn-outline-secondary", `type` := "button", onclick := "window.adjustPickerCount(-1)")("-"),
                        input(`type` := "number", id := "piece-picker-count", cls := "form-control bg-dark text-light border-secondary", value := "1", min := "0", max := "18"),
                        button(cls := "btn btn-outline-secondary", `type` := "button", onclick := "window.adjustPickerCount(1)")("+")
                      )
                    ),
                    div(cls := "row mt-3")(
                      div(cls := "col-6")(
                        button(`type` := "button", cls := "btn btn-primary w-100", id := "btnConfirmPiece")("OK")
                      ),
                      div(cls := "col-6")(
                        button(`type` := "button", cls := "btn btn-secondary w-100", onclick := "document.getElementById('piece-picker-modal').style.display='none'")("Cancel")
                      )
                    )
                 )
              )
            )
          )
        ),
        div(cls := "col-12 col-md-6")(
          div(cls := "card bg-dark text-light border-secondary mb-4")(
            div(cls := "card-header border-secondary")(h5("Board Verification")),
            div(cls := "card-body d-flex flex-column align-items-center")(
              div(cls := "ocr-board-container mb-4", style := "width: 100%;")(
                tag("sg-hand-wrap")(id := "hand-top", cls := "hand-top r-5"),
                div(cls := "sg-wrap d-9x9")(
                  div(id := "ocr-board")
                ),
                tag("sg-hand-wrap")(id := "hand-bottom", cls := "hand-bottom r-5")
              ),
              div(cls := "card bg-dark text-light border-secondary w-100")(
                div(cls := "card-header border-secondary")(h5("Image Verification")),
                div(cls := "card-body d-flex flex-column align-items-center")(
                  div(id := "ocr-region-buttons", style := "display: none; margin-bottom: 10px;")(
                    div(cls := "btn-group btn-group-regions", role := "group")(
                      button(`type` := "button", id := "btn-detect-board", cls := "btn btn-outline-info active", onclick := "window.setActiveRegion('board')")("Board"),
                      button(`type` := "button", id := "btn-detect-sente", cls := "btn btn-outline-info", onclick := "window.setActiveRegion('sente')")("Sente Hand"),
                      button(`type` := "button", id := "btn-detect-gote", cls := "btn btn-outline-info", onclick := "window.setActiveRegion('gote')")("Gote Hand")
                    )
                  ),
                  div(id := "ocr-canvas-container", style := "display: none; width: 100%; text-align: center;")(
                     canvas(id := "ocr-canvas"),
                     div(id := "rect-board", cls := "region-rect active", style := "display: none;")(tag("span")(cls := "region-label")("Board")),
                     div(id := "rect-sente", cls := "region-rect", style := "display: none;")(tag("span")(cls := "region-label")("Sente Hand")),
                     div(id := "rect-gote", cls := "region-rect", style := "display: none;")(tag("span")(cls := "region-label")("Gote Hand"))
                  )
                )
              )
            )
          )
         ),
          input(`type` := "hidden", id := "ocr-entry-id", attr("value") := entry.map(_.id).getOrElse("")),
          input(`type` := "hidden", id := "ocr-initial-data", attr("value") := entry.map { e =>
            val initialData = ujson.Obj(
              "sfen" -> e.sfen,
              "manualAdjustments" -> ujson.Obj.from(e.manualAdjustments.map { case (k, v) => k -> ujson.Str(v) }),
              "pieceReferences" -> ujson.Obj.from(e.pieceReferences.map { case (k, v) => k -> ujson.Str(v) }),
              "comment" -> e.comment
            )
            ujson.write(initialData)
          }.getOrElse(""))
        )
      )
    )
  }

  @cask.post("/ocr/upload")
  def ocrUpload(request: cask.Request): cask.Response[String] = {
    logger.info("OCR upload request received")
    
    try {
      val bytes = request.readAllBytes()
      
      // Heuristic: find where the image data starts (look for JPEG magic bytes 0xFF 0xD8 0xFF)
      val start = bytes.indexOfSlice(Array[Byte](0xFF.toByte, 0xD8.toByte, 0xFF.toByte))
      val imageData = if (start >= 0) {
        // Find end of JPEG (0xFF 0xD9)
        val end = bytes.indexOfSlice(Array[Byte](0xFF.toByte, 0xD9.toByte), start)
        if (end >= 0) bytes.slice(start, end + 2) else bytes.drop(start)
      } else {
        bytes // Fallback
      }
      
      val tempFile = File.createTempFile("ocr_upload_", ".jpg")
      java.nio.file.Files.write(tempFile.toPath, imageData)
      
      val img = javax.imageio.ImageIO.read(tempFile)
      val detectedRegions = shogi.puzzler.maintenance.ShogiOCR.detectDefaultRegions()
      
      // Convert image to base64 for frontend preview
      val baos = new java.io.ByteArrayOutputStream()
      javax.imageio.ImageIO.write(img, "jpg", baos)
      val base64Image = java.util.Base64.getEncoder.encodeToString(baos.toByteArray)
      
      tempFile.delete()
      
      val regionObj = ujson.Obj()
      
      // Use saved regions from active profile if they exist, otherwise use detected ones
      val settings = Await.result(SettingsRepository.getAppSettings(getSessionUserEmail(request)), 10.seconds)
      val activeProfileName = settings.activeOcrProfile.getOrElse("Default")
      val savedRegions = settings.ocrProfiles.get(activeProfileName)
      val finalRegions = savedRegions.getOrElse(detectedRegions)

      finalRegions.foreach { case (k, v) =>
        regionObj(k) = ujson.Obj("x" -> v.x, "y" -> v.y, "w" -> v.width, "h" -> v.height)
      }
      
      cask.Response(
        ujson.write(ujson.Obj(
          "image" -> s"data:image/jpeg;base64,$base64Image",
          "width" -> img.getWidth,
          "height" -> img.getHeight,
          "regions" -> regionObj
        )),
        headers = Seq("Content-Type" -> "application/json")
      )
    } catch {
      case e: Exception =>
        logger.error(s"OCR upload failed: ${e.getMessage}")
        cask.Response(
          ujson.write(ujson.Obj("error" -> e.getMessage)),
          statusCode = 500,
          headers = Seq("Content-Type" -> "application/json")
        )
    }
  }

  @cask.post("/ocr/process")
  def ocrProcess(request: cask.Request): cask.Response[String] = {
    try {
      val json = ujson.read(request.readAllBytes())
      val imageDataBase64 = if (json("image").str.contains(",")) json("image").str.split(",")(1) else json("image").str
      val boardRectJson = json("boardRect")
      val boardRect = shogi.puzzler.maintenance.Rect(
        boardRectJson("x").num.toInt,
        boardRectJson("y").num.toInt,
        boardRectJson("w").num.toInt,
        boardRectJson("h").num.toInt
      )
      
      val bytes = java.util.Base64.getDecoder.decode(imageDataBase64)
      val bis = new java.io.ByteArrayInputStream(bytes)
      val img = javax.imageio.ImageIO.read(bis)
      
      val senteRect = json.obj.get("senteRect").map(r => shogi.puzzler.maintenance.Rect(
        r("x").num.toInt, r("y").num.toInt, r("w").num.toInt, r("h").num.toInt
      ))

      val goteRect = json.obj.get("goteRect").map(r => shogi.puzzler.maintenance.Rect(
        r("x").num.toInt, r("y").num.toInt, r("w").num.toInt, r("h").num.toInt
      ))

      val sfen = shogi.puzzler.maintenance.ShogiOCR.extractSfen(img, boardRect, senteRect, goteRect)
      
      cask.Response(
        ujson.write(ujson.Obj("sfen" -> sfen)),
        headers = Seq("Content-Type" -> "application/json")
      )
    } catch {
      case e: Exception =>
        logger.error(s"OCR processing failed: ${e.getMessage}")
        cask.Response(
          ujson.write(ujson.Obj("error" -> e.getMessage)),
          statusCode = 500,
          headers = Seq("Content-Type" -> "application/json")
        )
    }
  }

  @cask.post("/ocr/identify-squares")
  def ocrIdentifySquares(request: cask.Request): cask.Response[String] = {
    try {
      val json = ujson.read(request.readAllBytes())
      val imageDataBase64 = if (json("image").str.contains(",")) json("image").str.split(",")(1) else json("image").str
      val boardRectJson = json("boardRect")
      val boardRect = shogi.puzzler.maintenance.Rect(
        boardRectJson("x").num.toInt,
        boardRectJson("y").num.toInt,
        boardRectJson("w").num.toInt,
        boardRectJson("h").num.toInt
      )
      
      val senteRect = json.obj.get("senteRect").map(r => shogi.puzzler.maintenance.Rect(
        r("x").num.toInt, r("y").num.toInt, r("w").num.toInt, r("h").num.toInt
      ))

      val goteRect = json.obj.get("goteRect").map(r => shogi.puzzler.maintenance.Rect(
        r("x").num.toInt, r("y").num.toInt, r("w").num.toInt, r("h").num.toInt
      ))

      val skipOCR = json.obj.get("skipOCR").exists(_.bool)
      val mode = json.obj.get("mode").map(_.str).getOrElse("all")

      val bytes = java.util.Base64.getDecoder.decode(imageDataBase64)
      val bis = new java.io.ByteArrayInputStream(bytes)
      val img = javax.imageio.ImageIO.read(bis)
      
      val squares = shogi.puzzler.maintenance.ShogiOCR.identifyAllSquares(img, boardRect, senteRect, goteRect, skipOCR = skipOCR, mode = mode)
      
      cask.Response(
        ujson.write(ujson.Obj("squares" -> squares.map(s => ujson.Obj(
        "coords" -> ujson.Str(s.getOrElse("coords", "")),
        "piece" -> ujson.Str(s.getOrElse("piece", "")),
        "label" -> ujson.Str(s.getOrElse("label", "")),
        "isGote" -> ujson.Str(s.getOrElse("isGote", "false")),
        "count" -> ujson.Str(s.getOrElse("count", "1")),
        "image" -> ujson.Str(s.getOrElse("image", "")),
        "type" -> ujson.Str(s.getOrElse("type", "board"))
        )))),
        headers = Seq("Content-Type" -> "application/json")
      )
    } catch {
      case e: Exception =>
        logger.error(s"OCR square identification failed: ${e.getMessage}")
        cask.Response(
          ujson.write(ujson.Obj("error" -> e.getMessage)),
          statusCode = 500,
          headers = Seq("Content-Type" -> "application/json")
        )
    }
  }

  @cask.post("/ocr/verify-piece")
  def ocrVerifyPiece(request: cask.Request): cask.Response[String] = {
    try {
      val json = ujson.read(request.readAllBytes())
      val label = json("label").str
      val image_data_raw = json("image").str
      val isHand = json.obj.get("isHand").exists(_.bool)
      val count = json.obj.get("count").map(_.str.toInt).getOrElse(1)
      val isGote = json.obj.get("isGote").exists(_.bool)
      
      // Remove data:image/png;base64, prefix if present
      val image_data = if (image_data_raw.startsWith("data:")) image_data_raw.split(",")(1) else image_data_raw
      
      val savedId = if (isHand) {
        Await.result(TrainingRepository.saveTrainingHand(label, count, image_data, isGote), 10.seconds)
      } else {
        Await.result(TrainingRepository.saveTrainingPiece(label, image_data, isGote), 10.seconds)
      }
      
      cask.Response(
        ujson.write(ujson.Obj("success" -> savedId.isDefined, "id" -> ujson.Str(savedId.getOrElse("")))),
        headers = Seq("Content-Type" -> "application/json")
      )
    } catch {
      case e: Exception =>
        logger.error(s"OCR piece verification failed: ${e.getMessage}")
        cask.Response(
          ujson.write(ujson.Obj("error" -> e.getMessage)),
          statusCode = 500,
          headers = Seq("Content-Type" -> "application/json")
        )
    }
  }

  @cask.get("/my-games")
  def index(player: Option[String] = None, search_text: Option[String] = None, request: cask.Request): cask.Response[String] = {
    withAuth(request, "my-games") { email =>
      val initialPlayer = player.getOrElse(search_text.getOrElse(""))
      val targetEmail = Some(email)
      val settings = Await.result(SettingsRepository.getAppSettings(targetEmail), 10.seconds)
      val pageLang = getLang(request)

      if (!settings.isConfigured) {
        logger.info(s"[MY-GAMES] Redirecting to /config because settings are not configured or still defaults for $targetEmail")
        noCacheRedirect("/config")
      } else {
        cask.Response(
          renderMaintenancePage(initialPlayer, Some(email), settings, pageLang).render,
          headers = Seq("Content-Type" -> "text/html; charset=utf-8")
        )
      }
    }
  }

  def renderMaintenancePage(initialPlayer: String = "", userEmail: Option[String] = None, settings: AppSettings, pageLang: String = I18n.defaultLang)(implicit lang: String = pageLang) = {
    Components.layout(
      I18n.t("maintenance.dashboardTitle"),
      userEmail,
      settings,
      appVersion,
      lang = pageLang,
      scripts = Seq(
        script(src := "https://cdn.jsdelivr.net/npm/chart.js"),
        script(src := "/js/maintenance.js")
      )
    )(
      div(cls := "d-flex flex-wrap justify-content-between align-items-center gap-3 mb-4")(
        h1(cls := "mb-0")(I18n.t("maintenance.pageTitle")),
        div(cls := "d-flex gap-2")(
          button(cls := "btn btn-outline-warning reload-data", title := I18n.t("maintenance.refreshData")) (
            i(cls := "bi bi-arrow-clockwise me-1"), I18n.t("maintenance.refresh")
          ),
          a(href := "/viewer", cls := "btn btn-info")(I18n.t("nav.puzzles"))
        )
      ),
      Components.gameFetcherCard(I18n.t("maintenance.lishogi"), "lishogi", settings.lishogiNickname, I18n.t("maintenance.fetchHint")),
      Components.gameFetcherCard(I18n.t("maintenance.shogiwars"), "shogiwars", settings.shogiwarsNickname, I18n.t("maintenance.fetchHint")),
      Components.gameFetcherCard(I18n.t("maintenance.dojo81"), "dojo81", settings.dojo81Nickname, I18n.t("maintenance.fetchHint")),
      
      input(`type` := "hidden", id := "lishogiNickname", value := settings.lishogiNickname),
      input(`type` := "hidden", id := "shogiwarsNickname", value := settings.shogiwarsNickname),
      input(`type` := "hidden", id := "dojo81Nickname", value := settings.dojo81Nickname),
      
      // Graph Modal
      div(cls := "modal fade", id := "graphModal", tabindex := "-1")(
        div(cls := "modal-dialog modal-fullscreen")(
          div(cls := "modal-content bg-dark text-light border-secondary")(
            div(cls := "modal-header border-secondary")(
              h5(cls := "modal-title", id := "graphTitle")(I18n.t("maintenance.graphTitle")),
              button(`type` := "button", cls := "btn-close btn-close-white", attr("data-bs-dismiss") := "modal")()
            ),
            div(cls := "modal-body d-flex flex-column")(
              div(cls := "flex-grow-1", style := "min-height: 0;")(
                canvas(id := "analysisChart")
              )
            )
          )
        )
      )
    )
  }

  @cask.get("/maintenance-tasks")
  def tasks(request: cask.Request): cask.Response[Value] = {
    withAuthJson(request, "my-games") { _ =>
      val taskList = TaskManager.getAllTasks
        .sortBy(_.createdAt)
        .map { t =>
          ujson.Obj(
            "id" -> t.id,
            "status" -> t.status,
            "message" -> t.message,
            "kifHash" -> t.kifHash.map(ujson.Str).getOrElse(ujson.Null)
          )
        }
      cask.Response(ujson.Arr(taskList: _*))
    }
  }

  @cask.post("/maintenance-analyze")
  def analyze(request: cask.Request): cask.Response[Value] = {
    withAuthJson(request, "my-games") { userEmailStr =>
      val userEmail = if (oauthEnabled) Some(userEmailStr) else getSessionUserEmail(request)
      val body = request.text()
      logger.info(s"[MAINTENANCE] Received analysis request")
      val jsonResult = try {
        Right(ujson.read(body))
      } catch {
        case e: Exception =>
          logger.error(s"[MAINTENANCE] Failed to parse JSON body: $body", e)
          Left(cask.Response(ujson.Obj("error" -> s"Invalid JSON: ${e.getMessage}"), statusCode = 400))
      }
      
      jsonResult match {
        case Left(errorResponse) => errorResponse
        case Right(json) =>
          val source = json.obj.get("source").map(_.str).getOrElse("unknown")
          val player = try { json("player").str } catch { case _: Exception => "unknown" }
          val kif = try { json("kif").str } catch { case _: Exception => "" }

          if (kif.isEmpty) {
            logger.error(s"[MAINTENANCE] KIF is missing in the request")
            cask.Response(ujson.Obj("error" -> "KIF is required"), statusCode = 400)
          } else {
            logger.info(s"[MAINTENANCE] Analysis request received for player: $player, source: $source, user: $userEmail")
            
            val kifHash = GameRepository.md5Hash(kif)
            val taskId = TaskManager.createTask(Some(kifHash))
            
            AnalysisQueue.enqueue(taskId, kif, player, source, userEmail)
            
            cask.Response(ujson.Obj("taskId" -> taskId), headers = Seq("Content-Type" -> "application/json"))
          }
      }
    }
  }

  @cask.get("/maintenance-task-status")
  def taskStatus(id: String, request: cask.Request): cask.Response[Value] = {
    withAuthJson(request, "my-games") { _ =>
      val taskOpt = TaskManager.getTask(id)
      if (taskOpt.isEmpty) {
        logger.warn(s"[MAINTENANCE] Task status requested for non-existent task: $id")
      }
      val result = taskOpt match {
        case Some(task) =>
          ujson.Obj(
            "id" -> task.id,
            "status" -> task.status,
            "message" -> task.message,
            "resultHtml" -> ujson.Str(task.resultHtml.getOrElse("")),
            "error" -> ujson.Str(task.error.getOrElse(""))
          )
        case None =>
          ujson.Obj("error" -> "Task not found")
      }
      cask.Response(result)
    }
  }

  @cask.get("/maintenance-fetch")
  def fetch(player: String = "", source: String = "shogiwars", search_text: String = "", force: Boolean = false, limit: Int = 10, request: cask.Request): cask.Response[Value] = {
    withAuthJson(request, "my-games") { userEmailStr =>
      val userEmail = if (oauthEnabled) Some(userEmailStr) else getSessionUserEmail(request)
      val targetPlayer = if (player.nonEmpty) player else search_text
      val sourceId = source
      val cacheKey = s"$sourceId:$targetPlayer"
      logger.info(s"[MAINTENANCE] Fetch request for player: '$targetPlayer', source: '$sourceId', force: $force, limit: $limit, user: $userEmail")
      
      if (targetPlayer.trim.isEmpty) {
        cask.Response(ujson.Obj("error" -> s"No nickname configured or provided for source '$sourceId'."), headers = Seq("Content-Type" -> "application/json"))
      } else {
        val taskId = TaskManager.createTask()
        
        val effectiveLimit = if (force) limit else 100
        
        Future {
          try {
            logger.info(s"[MAINTENANCE] Processing fetch for '$targetPlayer' from '$sourceId'")
            val games = if (!force) {
              logger.info(s"[MAINTENANCE] Fetching games from DB for $targetPlayer, source $sourceId with limit $effectiveLimit")
              val dbGames = Await.result(GameRepository.findByPlayerAndSource(targetPlayer, sourceId, effectiveLimit), 10.seconds)
              logger.info(s"[MAINTENANCE] DB returned ${dbGames.size} games")
              val mapped = dbGames.map { doc =>
                SearchGame(
                  sente = doc.get("sente").map(v => cleanPlayerName(v.asString().getValue)).getOrElse(""),
                  gote = doc.get("gote").map(v => cleanPlayerName(v.asString().getValue)).getOrElse(""),
                  date = doc.get("date").map(_.asString().getValue).getOrElse(""),
                  kif = doc.get("kif").map(_.asString().getValue),
                  existsInDb = true,
                  isAnalyzed = doc.get("is_analyzed").exists {
                    case b: org.bson.BsonBoolean => b.getValue
                    case i: org.bson.BsonInt32 => i.getValue != 0
                    case _ => false
                  },
                  puzzleCount = 0,
                  site = doc.get("site").map(_.asString().getValue)
                )
              }.take(limit)
              logger.info(s"[MAINTENANCE] Mapped ${mapped.size} games from DB")
              mapped
            } else {
              logger.info(s"[MAINTENANCE] Fetching games from external source $sourceId for $targetPlayer with limit $limit")
              val fetcher = sources.getOrElse(sourceId, ShogiWarsSource)
              val fetched = fetcher.fetchGames(targetPlayer, limit, userEmail, msg => TaskManager.updateProgress(taskId, msg), skipExisting = true)
              
              // Save fetched games to DB
              fetched.foreach { g =>
                if (g.kif.isDefined && !g.existsInDb) {
                  try {
                    val details = Map(
                      "sente" -> g.sente,
                      "gote" -> g.gote,
                      "date" -> g.date,
                      "site" -> g.site.getOrElse(sourceId)
                    )
                    Await.result(GameRepository.saveGame(g.kif.get, details), 5.seconds)
                    logger.info(s"[MAINTENANCE] Saved game to DB: ${g.sente} vs ${g.gote} (${g.date})")
                  } catch {
                    case e: Exception =>
                      logger.warn(s"[MAINTENANCE] Failed to save game to DB (might already exist): ${e.getMessage}")
                  }
                }
              }
              
              gamesCache(cacheKey) = fetched
              fetched
            }

            val gamesWithDbStatus = games.map { g =>
              val kifHash = g.kif.map(GameRepository.md5Hash)
              
              val (exists, analyzed, pCount) = if (g.existsInDb) {
                val hash = kifHash.getOrElse("")
                val count = if (hash.nonEmpty) Await.result(PuzzleRepository.countPuzzlesForGame(hash), 10.seconds).toInt else 0
                // Use g.isAnalyzed from SearchGame if it was already populated from DB document
                (true, g.isAnalyzed, count)
              } else {
                val dbGame = kifHash.flatMap(hash => Await.result(GameRepository.getGameByHash(hash), 10.seconds))
                
                val ex = dbGame.isDefined
                val an = dbGame.exists { doc =>
                   doc.get("is_analyzed").exists {
                     case b: org.bson.BsonBoolean => b.getValue
                     case i: org.bson.BsonInt32 => i.getValue != 0
                     case _ => false
                   }
                }
                val pc = if (ex && an && kifHash.isDefined) Await.result(PuzzleRepository.countPuzzlesForGame(kifHash.get), 10.seconds).toInt else 0
                (ex, an, pc)
              }
              
              g.copy(existsInDb = exists, isAnalyzed = analyzed, puzzleCount = pCount)
            }
            logger.info(s"[MAINTENANCE] gamesWithDbStatus size: ${gamesWithDbStatus.size}")
            
            val filteredGames = gamesWithDbStatus
            logger.info(s"[MAINTENANCE] filteredGames size: ${filteredGames.size}")

            val renderedFrag = div(
              div(cls := "d-flex flex-wrap justify-content-between align-items-center gap-3 mb-3")(
                div(
                  h3(cls := "mb-0")(s"Results for $targetPlayer")
                ),
                if (filteredGames.nonEmpty) {
                  div(cls := "btn-group btn-group-sm", role := "group")(
                    input(`type` := "radio", cls := "btn-check", name := s"filter-$sourceId", id := s"all-$sourceId", checked := "checked", onclick := s"window.maintenance.filterGames('all', '$sourceId-results')"),
                    label(cls := "btn btn-outline-secondary", `for` := s"all-$sourceId")("All"),
                    
                    input(`type` := "radio", cls := "btn-check", name := s"filter-$sourceId", id := s"analyzed-$sourceId", onclick := s"window.maintenance.filterGames('analyzed', '$sourceId-results')"),
                    label(cls := "btn btn-outline-info", `for` := s"analyzed-$sourceId")("Analyzed"),
                    
                    input(`type` := "radio", cls := "btn-check", name := s"filter-$sourceId", id := s"indb-$sourceId", onclick := s"window.maintenance.filterGames('indb', '$sourceId-results')"),
                    label(cls := "btn btn-outline-success", `for` := s"indb-$sourceId")("In DB")
                  )
                } else frag()
              ),
              if (filteredGames.isEmpty) p("No new games found.")
              else
                div(cls := "table-responsive")(
                  table(cls := "table table-dark table-hover align-middle")(
                    thead(
                      tr(
                        th(""), 
                        th("Players"), 
                        th(cls := "d-none d-md-table-cell")("Date"), 
                        th("Status"), 
                        th(cls := "d-none d-md-table-cell")("Puzzles"), 
                        th("Analysis"), 
                        th(cls := "d-none d-md-table-cell")("Actions")
                      )
                    ),
                    tbody(
                      filteredGames.map { g =>
                        val kifHash = g.kif.map(GameRepository.md5Hash).getOrElse("")
                        val status = if (g.isAnalyzed) "analyzed" else "indb"
                        
                        val kif = g.kif.getOrElse("")
                        val kifHashStr = if (kif.nonEmpty) {
                          var h = 0
                          var i = 0
                          while (i < kif.length) {
                            h = 31 * h + kif.charAt(i)
                            i += 1
                          }
                          java.lang.Math.abs(h).toHexString
                        } else ""
                        tr(attr("data-status") := status, cls := "game-row")(
                          td(
                            if (!g.existsInDb && g.kif.isDefined)
                              input(`type` := "checkbox", cls := "game-check", 
                                attr("data-sente") := g.sente,
                                attr("data-gote") := g.gote,
                                attr("data-date") := g.date,
                                attr("data-kif") := kif,
                                attr("data-site") := g.site.getOrElse(""),
                                checked := true
                              )
                            else frag()
                          ),
                          td(
                            div(cls := "d-flex flex-column")(
                              tag("span")(cls := "text-info")(s"☗ ${g.sente}"),
                              tag("span")(cls := "text-warning")(s"☖ ${g.gote}"),
                              tag("span")(cls := "d-md-none small text-muted mt-1")(g.date)
                            )
                          ),
                          td(cls := "d-none d-md-table-cell")(g.date),
                          td(
                            tag("span")(cls := "badge bg-success")("In DB"),
                            if (g.isAnalyzed) frag(
                              br(),
                              tag("span")(cls := "badge bg-info mt-1")("Analyzed")
                            ) else frag()
                          ),
                          td(cls := "d-none d-md-table-cell")(if (g.isAnalyzed) tag("span")(g.puzzleCount.toString) else "-"),
                          td(
                            if (g.existsInDb) {
                              if (g.isAnalyzed) {
                                div(cls := "d-flex flex-wrap gap-2")(
                                  if (g.puzzleCount > 0) {
                                    a(href := s"/puzzle-creator?game=$kifHash", target := "_blank", cls := "btn btn-sm btn-primary", title := "Puzzles")(
                                      i(cls := "bi bi-puzzle"), tag("span")(cls := "d-none d-lg-inline ms-1")("Puzzles")
                                    )
                                  } else frag(),
                                  button(cls := "btn btn-sm btn-outline-primary graph-btn",
                                    attr("data-hash") := kifHash,
                                    attr("data-sente") := g.sente,
                                    attr("data-gote") := g.gote,
                                    title := "Graph"
                                  )(i(cls := "bi bi-graph-up"), tag("span")(cls := "d-none d-lg-inline ms-1")("Graph")),
                                  a(href := s"/lishogi-redirect?hash=$kifHash", target := "_blank", cls := "btn btn-sm btn-outline-success", title := "Lishogi")(
                                    i(cls := "bi bi-box-arrow-up-right"), tag("span")(cls := "d-none d-lg-inline ms-1")("Lishogi")
                                  ),
                                  button(cls := "btn btn-sm btn-outline-danger delete-analysis-btn d-md-none",
                                    attr("data-hash") := kifHash,
                                    attr("data-player") := targetPlayer,
                                    title := "Delete Analysis"
                                  )(i(cls := "bi bi-trash"))
                                )
                              } else {
                                button(cls := s"btn btn-sm btn-warning analyze-btn btn-task-$kifHashStr", 
                                          attr("data-kif") := kif,
                                          attr("data-player") := targetPlayer,
                                          attr("data-site") := g.site.getOrElse(""))("Analyze")
                              }
                            } else frag()
                          ),
                          td(cls := "d-none d-md-table-cell")(
                            if (g.isAnalyzed) {
                              button(cls := "btn btn-sm btn-outline-danger delete-analysis-btn",
                                attr("data-hash") := kifHash,
                                attr("data-player") := targetPlayer,
                                title := "Delete Analysis")(i(cls := "bi bi-trash"))
                            } else frag()
                          )
                        )
                      }.toSeq: _*
                    )
                  )
                ),
                if (filteredGames.exists(g => !g.existsInDb && g.kif.isDefined)) {
                  button(id := "storeBtn", cls := "btn btn-success")("Download Selected to DB")
                } else frag()
            )
            val rendered = renderedFrag.render
            logger.info(s"[MAINTENANCE] rendered length for $targetPlayer from $sourceId: ${rendered.length}")
            
            TaskManager.complete(taskId, rendered)
          } catch {
            case e: Exception =>
              logger.error(s"[MAINTENANCE] Fetch failed for '$targetPlayer' from '$sourceId'", e)
              TaskManager.fail(taskId, e.getMessage)
          }
        }
        
        cask.Response(ujson.Obj("taskId" -> taskId), headers = Seq("Content-Type" -> "application/json"))
      }
    }
  }

  private def cleanPlayerName(name: String): String = {
    name.stripPrefix("先手：").stripPrefix("後手：")
      .replaceAll("\\s+\\d+[級段].*$", "")
      .trim
  }

  @cask.post("/maintenance-store")
  def storeBatch(request: cask.Request) = {
    logger.info(s"[MAINTENANCE] Received store request")
    val games = try {
      val text = request.text()
      logger.debug(s"[MAINTENANCE] Raw store request body: $text")
      ujson.read(text).arr.toSeq
    } catch {
      case e: Exception =>
        logger.error(s"[MAINTENANCE] Failed to parse JSON in storeBatch: ${e.getMessage}")
        throw e
    }
    var stored = 0
    var duplicates = 0

    games.foreach { g =>
      val kif = g("kif").str
      val exists = Await.result(GameRepository.exists(kif), 5.seconds)
      if (!exists) {
        val parsed = try {
          Some(GameLoader.parseKif(kif))
        } catch {
          case e: Exception =>
            logger.error(s"[MAINTENANCE] KIF parsing failed: ${e.getMessage}")
            None
        }

        val details = Map(
          "sente" -> parsed.flatMap(_.sentePlayerName).getOrElse(cleanPlayerName(g("sente").str)),
          "gote" -> parsed.flatMap(_.gotePlayerName).getOrElse(cleanPlayerName(g("gote").str)),
          "date" -> parsed.flatMap(_.gameDate).getOrElse(g("date").str),
          "site" -> parsed.flatMap(_.gameSite).getOrElse(g.obj.get("site").map(_.str).getOrElse(""))
        )
        try {
          Await.result(GameRepository.saveGame(kif, details), 5.seconds)
          stored += 1
        } catch {
          case e: com.mongodb.MongoWriteException if e.getError.getCategory == com.mongodb.ErrorCategory.DUPLICATE_KEY =>
            logger.info(s"[MAINTENANCE] Game already stored during batch (race condition)")
            duplicates += 1
        }
      } else {
        duplicates += 1
      }
    }

    corsResponse(ujson.write(ujson.Obj("stored" -> stored, "duplicates" -> duplicates)))
  }

  @cask.post("/maintenance-delete-analysis")
  def deleteAnalysis(request: cask.Request) = {
    withAuthJson(request, "my-games") { _ =>
      val body = request.text()
      logger.debug(s"[MAINTENANCE] Raw delete analysis request body: $body")
      val hash = try {
        ujson.read(body)("hash").str
      } catch {
        case e: Exception =>
          logger.error(s"[MAINTENANCE] Failed to parse delete analysis request: $body", e)
          throw e
      }
      logger.info(s"[MAINTENANCE] Deleting analysis for hash $hash")
      Await.result(GameRepository.deleteAnalysis(hash), 10.seconds)
      cask.Response(ujson.Obj("success" -> true), headers = Seq("Content-Type" -> "application/json"))
    }
  }

  @cask.get("/maintenance-analysis-data")
  def analysisData(hash: String, request: cask.Request) = {
    withAuthJson(request, "my-games") { _ =>
      val game = Await.result(GameRepository.getGameByHash(hash), 10.seconds)
      val legacyPuzzles = Await.result(PuzzleRepository.getLegacyPuzzlesForGame(hash), 10.seconds)
      val puzzles = Await.result(PuzzleRepository.getPuzzlesForGame(hash), 10.seconds)

      val scores = game.flatMap(_.get("scores")).map { s =>
        s.asArray().getValues.asScala.map { v =>
          if (v.isInt32) v.asInt32().getValue
          else if (v.isInt64) v.asInt64().getValue.toInt
          else if (v.isDouble) v.asDouble().getValue.toInt
          else 0
        }.toSeq
      }.getOrElse(Seq.empty)

      val legacyPuzzleDetails = legacyPuzzles.map { p =>
        val moveNumber = p.get("move_number").map { v =>
          if (v.isInt32) v.asInt32().getValue
          else if (v.isInt64) v.asInt64().getValue.toInt
          else if (v.isDouble) v.asDouble().getValue.toInt
          else 1
        }.getOrElse(1)
        val ply = moveNumber - 1
        val id = p.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
        val comment = p.get("comment").map(_.asString().getValue).getOrElse("")
        ujson.Obj(
          "ply" -> ujson.Num(ply.toDouble),
          "id" -> ujson.Str(id),
          "comment" -> ujson.Str(comment)
        )
      }

      val puzzleDetails = puzzles.flatMap { p =>
        val moveNumber = p.get("move_number").map { v =>
          if (v.isInt32) v.asInt32().getValue
          else if (v.isInt64) v.asInt64().getValue.toInt
          else if (v.isDouble) v.asDouble().getValue.toInt
          else 0
        }.getOrElse(0)
        if (moveNumber > 0) {
          val ply = moveNumber - 1
          val id = p.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
          val comment = p.get("comments").map(_.asString().getValue).getOrElse("")
          Some(ujson.Obj(
            "ply" -> ujson.Num(ply.toDouble),
            "id" -> ujson.Str(id),
            "comment" -> ujson.Str(comment)
          ))
        } else None
      }

      val allPuzzleDetails = legacyPuzzleDetails ++ puzzleDetails

      cask.Response(ujson.Obj(
        "scores" -> ujson.Arr(scores.map(s => ujson.Num(s.toDouble)): _*),
        "puzzles" -> ujson.Arr(allPuzzleDetails: _*)
      ), headers = Seq("Content-Type" -> "application/json"))
    }
  }

  @cask.get("/maintenance-puzzle-stats")
  def puzzleStats(hash: String, request: cask.Request) = {
    withAuthJson(request, "my-games") { _ =>
      val stats = Await.result(PuzzleRepository.getPuzzleStatsForGame(hash), 10.seconds)
      val regular = stats.getOrElse("regular", 0L)
      val accepted = stats.getOrElse("accepted", 0L)
      val review = stats.getOrElse("review", 0L)
      val total = stats.getOrElse("total", 0L)
      cask.Response(ujson.Obj(
        "regular" -> ujson.Num(regular.toDouble),
        "accepted" -> ujson.Num(accepted.toDouble),
        "review" -> ujson.Num(review.toDouble),
        "total" -> ujson.Num(total.toDouble)
      ), headers = Seq("Content-Type" -> "application/json"))
    }
  }

  @cask.route("/maintenance-<path:rest>", methods = Seq("OPTIONS"))
  def maintenanceOptions(rest: String) = {
    logger.info(s"[MAINTENANCE] OPTIONS request for $rest")
    cask.Response(
      "",
      headers = Seq(
        "Access-Control-Allow-Origin" -> "*",
        "Access-Control-Allow-Methods" -> "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers" -> "Content-Type, Access-Control-Allow-Headers, Authorization, X-Requested-With"
      )
    )
  }

  @cask.get("/lishogi-redirect")
  def lishogiRedirect(hash: String = "", request: cask.Request) = {
    withAuth(request, "my-games") { _ =>
      logger.info(s"[MAINTENANCE] lishogiRedirect entered. Hash param: '$hash'")

      try {
        if (hash.isEmpty) {
          logger.error("[MAINTENANCE] Error: hash parameter is missing or empty")
          cask.Response("Missing hash parameter", statusCode = 400)
        } else {
          getAnnotatedKif(hash) match {
            case Right(annotated) =>
              logger.info(s"[MAINTENANCE] Annotated KIF for Lishogi (hash: $hash):")
              val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()

              val formData = s"notation=${URLEncoder.encode(annotated, StandardCharsets.UTF_8)}&analyse=false"

              val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://lishogi.org/import"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .build()

              val response = client.send(httpRequest, HttpResponse.BodyHandlers.discarding())

              logger.info(s"[MAINTENANCE] Lishogi import response: ${response.statusCode()}")

              if (response.statusCode() == 303) {
                val location = response.headers().firstValue("Location").orElse("")
                val fullUrl = if (location.startsWith("http")) location else "https://lishogi.org" + location
                logger.info(s"[MAINTENANCE] Redirecting user to: $fullUrl")
                cask.Redirect(fullUrl)
              } else {
                val errorMsg = s"Lishogi returned status ${response.statusCode()}"
                logger.error(s"[MAINTENANCE] Error: $errorMsg")
                cask.Response(errorMsg, statusCode = 500)
              }
            case Left(error) =>
              logger.error(s"[MAINTENANCE] Game not found for hash: $hash")
              cask.Response(error, statusCode = 404)
          }
        }
      } catch {
        case e: Exception =>
          logger.error(s"[MAINTENANCE] CRITICAL ERROR in lishogiRedirect: ${e.getMessage}", e)
          cask.Response(s"Internal error: ${e.getMessage}", statusCode = 500)
      }
    }
  }

  @cask.get("/maintenance-annotated-kif")
  def annotatedKif(hash: String, request: cask.Request) = {
    withAuthJson(request, "my-games") { _ =>
      val responseObj = getAnnotatedKif(hash) match {
        case Right(annotated) => ujson.Obj("success" -> true, "kif" -> annotated)
        case Left(error) => ujson.Obj("success" -> false, "error" -> error)
      }
      cask.Response(responseObj)
    }
  }

  private def getAnnotatedKif(hash: String): Either[String, String] = {
    val gameDoc = Await.result(GameRepository.getGameByHash(hash), 5.seconds)
    val puzzles = Await.result(PuzzleRepository.getPuzzlesForGame(hash), 5.seconds)

    logger.info(s"[MAINTENANCE] Annotating KIF for hash $hash. Puzzles found: ${puzzles.size}")

    gameDoc match {
      case Some(doc) =>
        val kif = doc.get("kif").map(_.asString().getValue).getOrElse("")
        val comments = puzzles.flatMap { p =>
          val moveNumber = p.get("move_number").map { v =>
            if (v.isInt32) v.asInt32().getValue
            else if (v.isInt64) v.asInt64().getValue.toInt
            else if (v.isDouble) v.asDouble().getValue.toInt
            else 1
          }.getOrElse(1)
          val comment = p.get("comment").map(_.asString().getValue).getOrElse("")
          if (comment.nonEmpty) {
            logger.info(s"[MAINTENANCE]   Adding comment to move $moveNumber: ${comment.take(20)}...")
            Some(moveNumber -> comment)
          } else None
        }.toMap

        val annotated = KifAnnotator.annotate(kif, comments)
        Right(annotated)
      case None =>
        Left("Game not found")
    }
  }

  initialize()
}
