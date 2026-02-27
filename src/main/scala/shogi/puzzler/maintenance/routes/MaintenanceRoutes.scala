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
import shogi.puzzler.analysis.{GameAnalyzer, PuzzleExtractor, AnalysisService, SfenUtils}
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
    engineManagers.getOrElseUpdate(name, {
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
  def myGamesRedirect(request: cask.Request): cask.Response[String] =
    noCacheRedirect("/database")

  @cask.get("/fetch-games")
  def fetchGamesPage(request: cask.Request): cask.Response[String] = {
    withOptionalAuth(request) { userEmailOpt =>
      val canAnalyze = userEmailOpt.exists(email => canAccessPage(email, "my-games"))
      val settings   = Await.result(SettingsRepository.getAppSettings(userEmailOpt), 10.seconds)
      val pageLang   = getLang(request)

      cask.Response(
        renderGamesPage(userEmailOpt, settings, canAnalyze, pageLang).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  def renderGamesPage(userEmail: Option[String] = None, settings: AppSettings, canAnalyze: Boolean = false, pageLang: String = I18n.defaultLang)(implicit lang: String = pageLang) = {
    val isLoggedIn = userEmail.isDefined
    val defaultSource =
      if (settings.lishogiNickname.nonEmpty && settings.lishogiNickname != "lishogi_user") "lishogi"
      else if (settings.shogiwarsNickname.nonEmpty && settings.shogiwarsNickname != "swars_user") "shogiwars"
      else if (isLoggedIn && settings.dojo81Nickname.nonEmpty && settings.dojo81Nickname != "dojo81_user") "dojo81"
      else "lishogi"

    Components.layout(
      I18n.t("games.pageTitle"),
      userEmail,
      settings,
      appVersion,
      lang = pageLang,
      scripts = Seq(
        script(src := "/js/games.js")
      )
    )(
      div(cls := "d-flex flex-wrap justify-content-between align-items-center gap-3 mb-4")(
        h1(cls := "mb-0")(I18n.t("games.pageTitle")),
        a(href := "/viewer", cls := "btn btn-info")(I18n.t("nav.puzzles"))
      ),
      Components.gamesSummaryCard(settings.lishogiNickname, settings.shogiwarsNickname, settings.dojo81Nickname, defaultSource, isLoggedIn = isLoggedIn),

      // KIF upload card — visible to all users
      div(cls := "card bg-dark border-secondary mt-4")(
        div(cls := "card-header border-secondary d-flex align-items-center gap-2")(
          i(cls := "bi bi-file-earmark-arrow-up"),
          h5(cls := "mb-0")(I18n.t("games.uploadKif"))
        ),
        div(cls := "card-body")(
          p(cls := "text-muted small mb-3")(I18n.t("games.uploadKifDesc")),
          div(cls := "d-flex flex-wrap align-items-center gap-2")(
            input(
              `type` := "file", id := "kifFileInput",
              accept := ".kif,.kifu",
              cls := "form-control form-control-sm bg-dark text-light border-secondary",
              style := "max-width:380px"
            ),
            button(id := "uploadKifBtn", cls := "btn btn-outline-primary btn-sm", `type` := "button")(
              i(cls := "bi bi-upload me-1"), I18n.t("games.uploadBtn")
            )
          ),
          div(id := "upload-results", cls := "mt-3")()
        )
      )
    )
  }

  @cask.get("/games-fetch")
  def fetchGames(player: String = "", source: String = "lishogi", limit: Int = 10, request: cask.Request): cask.Response[Value] = {
    val userEmail = getSessionUserEmail(request)
    val sourceId = source match {
      case "lishogi" | "shogiwars" | "dojo81" => source
      case _ => "lishogi"
    }

    if (sourceId == "dojo81" && userEmail.isEmpty) {
      cask.Response(ujson.Obj("error" -> "Authentication required for 81Dojo"), statusCode = 401, headers = Seq("Content-Type" -> "application/json"))
    } else if (player.trim.isEmpty) {
      cask.Response(ujson.Obj("error" -> "Player nickname is required"), statusCode = 400, headers = Seq("Content-Type" -> "application/json"))
    } else {
        try {
          // Get current count of games in DB for this player
          val dbGamesCount = Await.result(GameRepository.findByPlayerAndSource(player, sourceId, 10000), 10.seconds).size

          // Fetch games from the source
          val fetcher = sources.getOrElse(sourceId, LishogiSource)
          val fetchedGames = fetcher.fetchGames(player, limit, userEmail, msg => logger.info(s"[GAMES] $msg"), skipExisting = false)

          // Save new games to DB
          var newGamesCount = 0
          fetchedGames.foreach { g =>
            if (g.kif.isDefined) {
              try {
                val exists = Await.result(GameRepository.exists(g.kif.get), 5.seconds)
                if (!exists) {
                  val details = Map(
                    "sente" -> g.sente,
                    "gote" -> g.gote,
                    "date" -> g.date,
                    "site" -> g.site.getOrElse(sourceId)
                  )
                  Await.result(GameRepository.saveGame(g.kif.get, details), 5.seconds)
                  newGamesCount += 1
                }
              } catch {
                case e: Exception =>
                  logger.warn(s"[GAMES] Failed to save game: ${e.getMessage}")
              }
            }
          }

          // Get updated count
          val updatedDbCount = Await.result(GameRepository.findByPlayerAndSource(player, sourceId, 10000), 10.seconds).size

          val message = if (newGamesCount > 0) {
            s"Successfully stored $newGamesCount new games"
          } else if (fetchedGames.isEmpty) {
            "No new games available for this player"
          } else {
            "All fetched games already exist in database"
          }

          cask.Response(
            ujson.Obj(
              "fetched" -> fetchedGames.size,
              "newGames" -> newGamesCount,
              "stored" -> updatedDbCount,
              "message" -> message
            ),
            headers = Seq("Content-Type" -> "application/json")
          )
        } catch {
          case e: Exception =>
            logger.error(s"[GAMES] Error fetching games: ${e.getMessage}", e)
            cask.Response(
              ujson.Obj(
                "error" -> s"Error fetching games: ${e.getMessage}",
                "fetched" -> 0,
                "newGames" -> 0,
                "stored" -> 0
              ),
              statusCode = 500,
              headers = Seq("Content-Type" -> "application/json")
            )
        }
    }
  }

  @cask.post("/games-upload-kif")
  def uploadKif(request: cask.Request): cask.Response[Value] = {
    val kifContent = request.text()
    if (kifContent.trim.isEmpty) {
      cask.Response(ujson.Obj("error" -> "KIF content is empty"), statusCode = 400,
        headers = Seq("Content-Type" -> "application/json"))
    } else {
      try {
        val parsed = shogi.puzzler.game.GameLoader.parseKif(kifContent)
        if (parsed.allPositions.size < 2) {
          cask.Response(ujson.Obj("error" -> "Game has too few moves to be saved"),
            statusCode = 400, headers = Seq("Content-Type" -> "application/json"))
        } else {
          val exists = Await.result(GameRepository.exists(kifContent), 5.seconds)
          if (exists) {
            cask.Response(
              ujson.Obj("error" -> "This game already exists in the database", "duplicate" -> true),
              statusCode = 409, headers = Seq("Content-Type" -> "application/json"))
          } else {
            val details = Map(
              "sente" -> parsed.sentePlayerName.getOrElse(""),
              "gote"  -> parsed.gotePlayerName.getOrElse(""),
              "date"  -> parsed.gameDate.getOrElse(""),
              "site"  -> parsed.gameSite.getOrElse("upload")
            )
            Await.result(GameRepository.saveGame(kifContent, details), 10.seconds)
            cask.Response(
              ujson.Obj(
                "success" -> true,
                "sente"   -> details("sente"),
                "gote"    -> details("gote"),
                "date"    -> details("date").replace("/", "-").take(10),
                "site"    -> details("site"),
                "moves"   -> parsed.allPositions.size
              ),
              headers = Seq("Content-Type" -> "application/json")
            )
          }
        }
      } catch {
        case e: Exception =>
          cask.Response(
            ujson.Obj("error" -> s"Invalid KIF: ${e.getMessage}"),
            statusCode = 400, headers = Seq("Content-Type" -> "application/json"))
      }
    }
  }

  // -------------------------------------------------------------------------
  // View Game page
  // -------------------------------------------------------------------------

  @cask.get("/view-game/:hash")
  def viewGamePage(hash: String, request: cask.Request): cask.Response[String] = {
    withOptionalAuth(request) { userEmailOpt =>
      val canAnalyze = userEmailOpt.exists(email => canAccessPage(email, "my-games"))
      val settings   = if (canAnalyze) Await.result(SettingsRepository.getAppSettings(userEmailOpt), 10.seconds)
                       else AppSettings.default
      val pageLang = getLang(request)
      Await.result(GameRepository.getGameByHash(hash), 10.seconds) match {
        case None =>
          cask.Response("Game not found", statusCode = 404, headers = Seq("Content-Type" -> "text/plain"))
        case Some(doc) =>
          val sente: String = doc.get("sente").map(_.asString().getValue).getOrElse("Sente")
          val gote:  String = doc.get("gote").map(_.asString().getValue).getOrElse("Gote")
          val date:  String = doc.get("date").map(_.asString().getValue).getOrElse("")
          val site:  String = doc.get("site").map(_.asString().getValue).getOrElse("")
          val isAnalyzed: Boolean = doc.get("is_analyzed").exists {
            case b: org.bson.BsonBoolean => b.getValue
            case n: org.bson.BsonInt32   => n.getValue != 0
            case _                       => false
          }
          cask.Response(
            renderViewGamePage(hash, sente, gote, date, site, isAnalyzed, userEmailOpt, settings, pageLang, canAnalyze).render,
            headers = Seq(
              "Content-Type"                   -> "text/html; charset=utf-8",
              "Cross-Origin-Opener-Policy"     -> "same-origin",
              "Cross-Origin-Embedder-Policy"   -> "credentialless"
            )
          )
      }
    }
  }

  private def viewGameSourceBadge(site: String): scalatags.Text.Modifier = {
    val src = site.toLowerCase
    if      (src.contains("lishogi"))                         scalatags.Text.all.span(cls := "badge", style := "background:rgba(108,200,127,.25);color:#6cc87f;border:1px solid rgba(108,200,127,.35)")("Lishogi")
    else if (src.contains("wars"))                            scalatags.Text.all.span(cls := "badge", style := "background:rgba(255,193,7,.2);color:#ffc107;border:1px solid rgba(255,193,7,.3)")("ShogiWars")
    else if (src.contains("81dojo") || src.contains("dojo")) scalatags.Text.all.span(cls := "badge", style := "background:rgba(13,202,240,.2);color:#0dcaf0;border:1px solid rgba(13,202,240,.3)")("81Dojo")
    else if (site.nonEmpty)                                   scalatags.Text.all.span(cls := "badge bg-secondary")(site)
    else                                                      frag()
  }

  def renderViewGamePage(
    kifHash: String, sente: String, gote: String, date: String, site: String, isAnalyzed: Boolean,
    userEmail: Option[String] = None, settings: AppSettings, pageLang: String = I18n.defaultLang,
    canAnalyze: Boolean = true
  )(implicit lang: String = pageLang) = {
    html(scalatags.Text.all.lang := pageLang, cls := "dark", style := "--zoom:90;")(
      head(
        meta(charset := "utf-8"),
        meta(name := "viewport", attr("content") := "width=device-width,initial-scale=1,viewport-fit=cover"),
        meta(name := "theme-color", attr("content") := "#2e2a24"),
        tag("title")(s"$sente vs $gote — ${I18n.t("viewgame.pageTitle")}"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"),
        link(rel := "stylesheet", href := "https://cdn.jsdelivr.net/npm/bootstrap-icons@1.10.3/font/bootstrap-icons.css"),
        link(rel := "stylesheet", href := "/assets/css/shogiground.css"),
        link(rel := "stylesheet", href := "/assets/css/portella.css"),
        link(rel := "stylesheet", href := "/assets/css/site.css"),
        link(rel := "stylesheet", href := "/assets/css/puzzle.css"),
        link(rel := "stylesheet", href := "/assets/css/common.css"),
        script(src := "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js"),
        script(src := "https://code.jquery.com/jquery-3.6.0.min.js"),
        script(src := "https://cdn.jsdelivr.net/npm/chart.js"),
        script(raw(s"window.i18n=${I18n.messagesAsJson(pageLang)};")),
        tag("style")(raw("""
          /* ── Move list ──────────────────────────────────────────────────── */
          .vg-movepairs{padding:4px 0}
          .vg-movepair{display:flex;align-items:center;gap:2px;border-radius:3px;padding:2px 2px}
          .vg-movepair:hover{background:rgba(255,255,255,.04)}
          .vg-movenum{color:#555;font-size:.72rem;width:26px;text-align:right;margin-right:2px;flex-shrink:0}
          .vg-move{padding:4px 7px;border-radius:3px;cursor:pointer;font-size:.82rem;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;transition:background .1s;touch-action:manipulation;-webkit-tap-highlight-color:transparent}
          .vg-move:hover{background:rgba(255,255,255,.09)}
          .vg-move.active{background:rgba(255,220,50,.18);color:#ffe032;font-weight:600}
          .vg-move--init{color:#888;font-style:italic}
          .vg-move--sente{color:#ddd}
          .vg-move--gote{color:#999}
          .vg-move--empty{cursor:default;flex:1}
          .vg-move-analyzed::after{content:"•";color:#4caf50;font-size:.6rem;vertical-align:super;margin-left:2px}
          /* ── Nav controls ───────────────────────────────────────────────── */
          .vg-controls{grid-area:controls;display:flex;justify-content:center;align-items:center;gap:8px;min-height:3rem;padding:6px 8px}
          .vg-controls .btn{min-width:44px;min-height:44px;display:inline-flex;align-items:center;justify-content:center;touch-action:manipulation;-webkit-tap-highlight-color:transparent}
          .vg-counter{min-width:76px;text-align:center;font-size:.88rem;font-weight:600;color:#ccc;user-select:none;letter-spacing:.02em}
          /* ── Score bar ──────────────────────────────────────────────────── */
          .vg-comment-wrap{grid-area:comment;display:flex;flex-direction:column;gap:6px;align-self:start}
          .vg-score-wrap{display:flex;align-items:center;gap:8px;padding:6px 12px;background:var(--c-bg-box,#26231f);border-radius:5px}
          .vg-score-bar{flex:1;height:10px;border-radius:5px;border:1px solid #444;background:#888;transition:background .3s}
          .vg-score-lbl{font-size:.78rem;color:#aaa;min-width:76px;text-align:right;font-variant-numeric:tabular-nums}
          /* ── Move list panel ────────────────────────────────────────────── */
          .vg-movelist{grid-area:tools;display:flex;flex-direction:column;background:var(--c-bg-box,#26231f);border-radius:5px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.3)}
          .vg-movelist .analyse__tools{flex:1;display:flex;flex-direction:column}
          .vg-movelist .analyse__moves{flex:1 1 200px;overflow-y:auto;padding:10px;min-height:200px}
          /* ── Move comment panel ─────────────────────────────────────────── */
          .vg-move-comment-panel{flex-shrink:0;border-top:1px solid #2c2a27}
          .vg-move-comment-panel:empty{display:none}
          .vg-mc-view{padding:8px 10px;font-size:.82rem;color:#ccc;white-space:pre-wrap;word-break:break-word;line-height:1.5;position:relative}
          .vg-mc-view::before{content:'\201C';font-size:1.1rem;color:#ffc107;margin-right:.15rem;opacity:.7}
          .vg-mc-edit{padding:6px 8px;display:flex;flex-direction:column;gap:6px}
          .vg-mc-edit textarea{background:#1e1c18;color:#e8e0d0;border:1px solid #3d3a35;border-radius:4px;resize:vertical;font-size:.82rem;padding:5px 7px;min-height:52px;width:100%;outline:none;line-height:1.45}
          .vg-mc-edit textarea:focus{border-color:#6c757d}
          .vg-mc-edit-row{display:flex;gap:6px;align-items:center}
          .vg-mc-btn{font-size:.75rem;padding:2px 9px;border-radius:3px;border:1px solid;cursor:pointer;background:transparent}
          .vg-mc-btn-save{border-color:#0d6efd;color:#6ea8fe}
          .vg-mc-btn-save:hover{background:#0d6efd22}
          .vg-mc-btn-del{border-color:#6c757d;color:#6c757d}
          .vg-mc-btn-del:hover{background:#6c757d22}
          .vg-mc-edit-hint{font-size:.72rem;color:#6c757d;margin-left:auto}
          .vg-move-commented::after{content:'💬';font-size:.6em;vertical-align:super;margin-left:2px;opacity:.7}
          /* ── Board: isolate stacking context so pieces (z-index 3+) cannot ─ */
          /* ── paint above controls/movelist outside the board element       ─ */
          .puzzle-view-game .puzzle__board{grid-area:board;isolation:isolate}
          /* ── Player strips inside the move list panel ───────────────────── */
          .vg-pl-strip{display:flex;align-items:center;gap:.4rem;padding:5px 10px;background:#1e1c18;font-size:.82rem;font-weight:600;color:#888;min-height:2rem;flex-shrink:0;transition:background .2s}
          .vg-pl-strip--top{border-radius:5px 5px 0 0;border-bottom:1px solid #2c2a27}
          .vg-pl-strip--bot{border-radius:0 0 5px 5px;border-top:1px solid #2c2a27}
          .vg-pl-strip .vg-pl-icon{flex-shrink:0}
          .vg-pl-strip .vg-pl-name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0}
          .vg-pl-strip .vg-pl-tomove{font-size:.7rem;color:#ffc107;font-weight:700;flex-shrink:0;opacity:0;transition:opacity .15s;margin-left:.2rem}
          .vg-pl-strip.is-tomove{background:#232118;color:#e8e4df}
          .vg-pl-strip.is-tomove .vg-pl-tomove{opacity:1}
          /* ── Controls separator ─────────────────────────────────────────── */
          .vg-controls-sep{width:1px;height:22px;background:#444;margin:0 4px;flex-shrink:0}
          /* ── Side panel (game info card + analysis chart) ───────────────── */
          .vg-side{grid-area:side;display:flex;flex-direction:column;gap:10px;align-self:start}
          .vg-game-card{background:var(--c-bg-box,#26231f);border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.3)}
          .vg-gc-players{padding:10px 14px;font-size:1rem;font-weight:600;display:flex;align-items:center;gap:.35rem;flex-wrap:wrap;border-bottom:1px solid rgba(255,255,255,.07)}
          .vg-gc-players .pname{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:12em}
          .vg-gc-meta{padding:8px 14px;display:flex;align-items:center;gap:.4rem;flex-wrap:wrap;font-size:.78rem;color:#888;border-bottom:1px solid rgba(255,255,255,.07)}
          .vg-gc-actions{padding:8px 14px;display:flex;gap:8px;flex-wrap:wrap;align-items:center}
          .vg-gc-chart{background:var(--c-bg-box,#26231f);border-radius:8px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,.3)}
          .vg-gc-chart__hdr{padding:6px 12px;background:#1e1c18;display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid rgba(255,255,255,.07)}
          /* On wide screens the side column spans all board rows — stretch to fill */
          @media (min-width:1259.3px) and (orientation:landscape){
            .vg-side{align-self:stretch}
          }
          /* ── View-game layout (matches puzzle grid breakpoints) ──────────── */
          /* Mobile portrait: side at bottom (same as puzzle) */
          .puzzle-view-game{grid-template-areas:"board" "comment" "controls" "tools" "side"}
          /* Landscape / ≥979px: board left, panel right, side spans bottom row */
          @media (min-width:979.3px),(orientation:landscape){
            .puzzle-view-game{
              grid-template-areas:"board  gauge  comment" "board  gauge  controls" "board  gauge  tools" "side   side   side";
              grid-template-columns:var(--col2-uniboard-main-size) 2vmin minmax(200px,320px);
              grid-template-rows:auto auto 1fr auto
            }
          }
          /* Wide landscape ≥1259px: side on LEFT (matches puzzle wide layout) */
          @media (min-width:1259.3px) and (orientation:landscape){
            .puzzle-view-game{
              grid-template-areas:"side . board  gauge  comment" "side . board  gauge  controls" "side . board  gauge  tools";
              grid-template-columns:minmax(200px,320px) 2vmin var(--col3-uniboard-main-size) 2vmin minmax(200px,320px);
              grid-template-rows:auto auto 1fr
            }
          }
          /* ── Grid overflow prevention ───────────────────────────────────── */
          /* Default min-width for grid items is 'auto' (= min-content), which
             can force the column wider than the viewport → board overflow/clip.
             Reset to 0 so the grid can shrink items to fit available space.   */
          .puzzle-view-game > *{min-width:0}
          /* ── Mobile-only tweaks (portrait / narrow) ─────────────────────── */
          @media (max-width:599px){
            .vg-movelist .analyse__moves{min-height:100px;max-height:180px}
            .vg-chart-body{height:140px!important}
            .vg-chart-hint{display:none!important}
          }
          /* ── Reanalysis panel ───────────────────────────────────────────── */
          .vg-rp{padding:10px 14px;border-top:1px solid rgba(255,255,255,.06)}
          .vg-rp-inputs{display:flex;gap:8px;margin-bottom:8px;flex-wrap:wrap}
          .vg-rp-field{flex:1;min-width:80px}
          .vg-rp-label{display:block;font-size:.68rem;color:#888;margin-bottom:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
          .vg-rp-input{width:100%;background:#1a1917;border:1px solid #3a3835;color:#ddd;border-radius:3px;padding:4px 6px;font-size:.8rem}
          .vg-rp-input:focus{outline:none;border-color:#5a8fff}
          .vg-rp-btn{width:100%}
          /* ── Analysis progress ──────────────────────────────────────────── */
          .vg-ap{padding:10px 14px;border-top:1px solid rgba(255,255,255,.06)}
          .vg-ap-bar{height:3px;border-radius:2px;background:rgba(255,255,255,.08);overflow:hidden;margin-bottom:6px}
          .vg-ap-bar-inner{height:100%;background:#4a90e2;animation:vg-progress-anim 1.4s linear infinite;border-radius:2px;width:45%}
          @keyframes vg-progress-anim{0%{transform:translateX(-100%)}100%{transform:translateX(320%)}}
          .vg-ap-msg{font-size:.75rem;color:#aaa;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-height:1.1em}
          .vg-ap-result{font-size:.78rem;margin-top:4px}
          /* ── Candidates panel ───────────────────────────────────────────── */
          .vg-cp{padding:10px 14px;border-top:1px solid rgba(255,255,255,.06)}
          .vg-cp-title{font-size:.7rem;color:#888;text-transform:uppercase;letter-spacing:.05em;margin-bottom:6px;display:flex;align-items:center;justify-content:space-between}
          .vg-candidate{display:flex;align-items:center;gap:6px;padding:4px 2px;border-radius:3px;border-bottom:1px solid rgba(255,255,255,.04);cursor:default}
          .vg-candidate:last-child{border-bottom:none}
          .vg-candidate.vg-cand-selected{background:rgba(255,255,255,.06)}
          .vg-cand-dot{width:8px;height:8px;border-radius:50%;flex-shrink:0}
          .vg-cand-move{font-size:.82rem;font-weight:600;color:#ddd;min-width:72px}
          .vg-cand-score{font-size:.78rem;color:#aaa;min-width:48px}
          .vg-cand-play{background:none;border:1px solid rgba(255,255,255,.18);color:#aaa;border-radius:3px;font-size:.7rem;padding:1px 6px;cursor:pointer;margin-left:auto;flex-shrink:0;line-height:1.4;transition:background .15s,color .15s}
          .vg-cand-play:hover{background:rgba(255,255,255,.12);color:#fff}
          /* ── Candidate replay ────────────────────────────────────────────── */
          .vg-rn-moves{display:flex;flex-wrap:wrap;gap:3px;padding:6px 0;border-bottom:1px solid rgba(255,255,255,.06);max-height:72px;overflow-y:auto;margin-bottom:6px}
          .vg-rm{padding:2px 6px;border-radius:3px;cursor:pointer;font-size:.75rem;color:#888;white-space:nowrap;transition:background .1s}
          .vg-rm:hover{background:rgba(255,255,255,.08)}
          .vg-rm.active{background:rgba(255,220,50,.18);color:#ffe032;font-weight:600}
          .vg-rn-controls{display:flex;align-items:center;gap:6px}
          .vg-rn-btn{background:none;border:1px solid rgba(255,255,255,.2);color:#ccc;border-radius:3px;padding:2px 10px;cursor:pointer;font-size:.78rem;transition:background .1s}
          .vg-rn-btn:hover:not(:disabled){background:rgba(255,255,255,.1)}
          .vg-rn-btn:disabled{opacity:.35;cursor:default}
          .vg-rn-counter{font-size:.75rem;color:#888;text-align:center;flex:1}
          /* ── Puzzle-at-ply widget ────────────────────────────────────────── */
          .vg-pw{padding:8px 14px;border-top:1px solid rgba(255,255,255,.06)}
          .vg-pw-title{font-size:.7rem;color:#888;text-transform:uppercase;letter-spacing:.05em;margin-bottom:5px}
          .vg-pw-item{display:flex;align-items:center;gap:6px;font-size:.78rem;flex-wrap:wrap}
          /* ── Local engine eval gauge (horizontal, below score bar) ─────────── */
          .vg-ceval-gauge{display:flex;align-items:center;gap:8px;padding:6px 12px;background:var(--c-bg-box,#26231f);border-radius:5px}
          .vg-cg-bar-wrap{flex:1;height:10px;border-radius:5px;border:1px solid #444;background:#cccccc;overflow:hidden;position:relative}
          .vg-cg-fill{position:absolute;top:0;left:0;bottom:0;background:#1a1a1a;width:50%;transition:width .3s ease}
          .vg-cg-info{font-size:.78rem;color:#aaa;min-width:76px;text-align:right;font-variant-numeric:tabular-nums}
          .vg-cg-label{font-size:.7rem;color:#888}
          /* ── Engine status badge in move-list header ─────────────────────── */
          .vg-ceval-status{font-size:.65rem;padding:2px 6px;border-radius:3px;background:rgba(25,135,84,.2);color:#198754;border:1px solid rgba(25,135,84,.3)}
          .vg-ceval-status.is-computing{background:rgba(255,193,7,.15);color:#ffc107;border-color:rgba(255,193,7,.3)}
          .vg-ceval-status.is-error{background:rgba(220,53,69,.15);color:#dc3545;border-color:rgba(220,53,69,.3)}
        """))
      ),
      body(cls := "wood coords-out")(
        Components.renderHeader(userEmail, settings, appVersion, pageLang),

        div(id := "main-wrap")(
          tag("main")(cls := "puzzle puzzle-play puzzle-view-game")(

            // ── Board (grid-area: board) ────────────────────────────────
            div(cls := "puzzle__board main-board")(
              div(cls := "sg-wrap d-9x9")(
                tag("sg-hand-wrap")(attr("id") := "hand-top"),
                div(attr("id") := "dirty", cls := "dirty"),
                tag("sg-hand-wrap")(attr("id") := "hand-bottom")
              )
            ),

            // ── Score bar + ceval gauge (grid-area: comment) ─────────────
            div(cls := "vg-comment-wrap")(
              div(cls := "vg-score-wrap", style := (if (!isAnalyzed) "display:none" else ""))(
                scalatags.Text.all.span(cls := "text-muted", style := "font-size:.7rem")("▲"),
                div(id := "score-bar", cls := "vg-score-bar"),
                scalatags.Text.all.span(cls := "text-muted", style := "font-size:.7rem")("▽"),
                scalatags.Text.all.span(id := "score-label", cls := "vg-score-lbl")()
              ),
              div(id := "ceval-gauge", cls := "vg-ceval-gauge", style := "display:none")(
                scalatags.Text.all.span(cls := "vg-cg-label")("☗"),
                div(cls := "vg-cg-bar-wrap")(
                  div(id := "ceval-bar-fill", cls := "vg-cg-fill")
                ),
                scalatags.Text.all.span(cls := "vg-cg-label")("☖"),
                scalatags.Text.all.span(id := "ceval-bar-info", cls := "vg-cg-info")()
              )
            ),

            // ── Navigation controls (grid-area: controls) ───────────────
            div(cls := "vg-controls")(
              button(id := "btn-first", cls := "btn btn-outline-light btn-sm", title := I18n.t("viewgame.navFirst"))(i(cls := "bi bi-chevron-double-left")),
              button(id := "btn-prev",  cls := "btn btn-outline-light btn-sm", title := I18n.t("viewgame.navPrev" ))(i(cls := "bi bi-chevron-left")),
              scalatags.Text.all.span(id := "move-counter", cls := "vg-counter")("0 / 0"),
              button(id := "btn-next",  cls := "btn btn-outline-light btn-sm", title := I18n.t("viewgame.navNext" ))(i(cls := "bi bi-chevron-right")),
              button(id := "btn-last",  cls := "btn btn-outline-light btn-sm", title := I18n.t("viewgame.navLast" ))(i(cls := "bi bi-chevron-double-right")),
              scalatags.Text.all.span(cls := "vg-controls-sep"),
              button(id := "btn-flip",  cls := "btn btn-outline-secondary btn-sm", title := "Flip board (F)")(i(cls := "bi bi-arrow-down-up"))
            ),

            // ── Move list (grid-area: tools) ────────────────────────────
            div(cls := "vg-movelist")(
              div(id := "vg-player-top", cls := "vg-pl-strip vg-pl-strip--top"),
              div(cls := "analyse__tools")(
                div(cls := "analyse__moves")(
                  div(id := "move-list")(
                    div(cls := "text-muted small p-3")(
                      i(cls := "bi bi-hourglass-split me-1"), "Loading…"
                    )
                  )
                ),
                div(id := "move-comment-panel", cls := "vg-move-comment-panel")()
              ),
              div(id := "vg-player-bottom", cls := "vg-pl-strip vg-pl-strip--bot")
            ),

            // ── Side panel card (grid-area: side) ───────────────────────
            // Mobile/narrow: below tools. Wide ≥1259px: left of board.
            div(cls := "vg-side")(
              // Game info card
              div(cls := "vg-game-card")(
                div(cls := "vg-gc-players")(
                  scalatags.Text.all.span(cls := "text-warning flex-shrink-0")("☗"),
                  scalatags.Text.all.span(cls := "pname")(sente),
                  scalatags.Text.all.span(cls := "text-secondary mx-1 opacity-50 flex-shrink-0")("vs"),
                  scalatags.Text.all.span(cls := "text-secondary opacity-75 flex-shrink-0")("☖"),
                  scalatags.Text.all.span(cls := "pname")(gote)
                ),
                div(cls := "vg-gc-meta")(
                  if (date.nonEmpty) scalatags.Text.all.span()(date.take(10)) else (),
                  viewGameSourceBadge(site),
                  if (isAnalyzed)
                    scalatags.Text.all.span(cls := "badge", style := "background:rgba(25,135,84,.2);color:#198754;border:1px solid rgba(25,135,84,.3)")(
                      i(cls := "bi bi-check-circle-fill me-1"), I18n.t("database.statusAnalyzed"))
                  else
                    scalatags.Text.all.span(cls := "badge", style := "background:rgba(255,193,7,.15);color:#ffc107;border:1px solid rgba(255,193,7,.25)")(
                      i(cls := "bi bi-clock me-1"), I18n.t("database.statusPending"))
                ),
                div(cls := "vg-gc-actions")(
                  a(href := "/database", cls := "btn btn-sm btn-outline-secondary")(
                    i(cls := "bi bi-arrow-left me-1"), I18n.t("viewgame.backToDb")
                  ),
                  if (canAnalyze) button(id := "reanalyzeToggleBtn", cls := "btn btn-sm btn-outline-primary")(
                    i(cls := "bi bi-cpu me-1"),
                    if (isAnalyzed) I18n.t("database.btnReanalyze") else I18n.t("database.btnAnalyze")
                  ) else (),
                  // EXPERIMENTAL (disabled): local full-game analysis — re-enable when quality improves
                  // lgaAnalyzeBtn + lgaSettingsBtn removed from UI intentionally
                  div(cls := "btn-group btn-group-sm")(
                    if (canAnalyze) frag(
                      button(id := "analyzePositionBtn", cls := "btn btn-outline-info")(
                        i(cls := "bi bi-eye me-1"), I18n.t("viewgame.analyzePositionBtn")
                      ),
                      button(id := "analyzePosSettingsBtn", cls := "btn btn-outline-secondary", title := "Position analysis settings")(
                        i(cls := "bi bi-gear")
                      )
                    ) else (),
                    button(id := "cevalToggleBtn", cls := "btn btn-outline-success", title := "Local engine analysis (browser)")(
                      i(cls := "bi bi-cpu-fill me-1"), "Local"
                    ),
                    button(id := "cevalSettingsBtn", cls := "btn btn-outline-secondary", title := "Local analysis settings")(
                      i(cls := "bi bi-gear")
                    )
                  ),
                  a(id := "openInLishogiBtn", href := "#", target := "_blank", rel := "noopener", cls := "btn btn-sm btn-outline-success")(
                    i(cls := "bi bi-box-arrow-up-right me-1"), I18n.t("viewgame.openInLishogi")
                  )
                ),
                // Reanalysis settings panel (collapsible)
                div(id := "reanalyze-panel", cls := "vg-rp", style := "display:none")(
                  div(cls := "vg-rp-inputs")(
                    div(cls := "vg-rp-field")(
                      label(cls := "vg-rp-label", `for` := "shallowLimitInput")(I18n.t("config.shallowLimit")),
                      input(`type` := "number", id := "shallowLimitInput", cls := "vg-rp-input",
                        attr("min") := "1", attr("max") := "60", attr("step") := "1")
                    ),
                    div(cls := "vg-rp-field")(
                      label(cls := "vg-rp-label", `for` := "deepLimitInput")(I18n.t("config.deepLimit")),
                      input(`type` := "number", id := "deepLimitInput", cls := "vg-rp-input",
                        attr("min") := "1", attr("max") := "120", attr("step") := "1")
                    ),
                    div(cls := "vg-rp-field")(
                      label(cls := "vg-rp-label", `for` := "winChanceDropInput")(I18n.t("viewgame.winChanceDrop")),
                      input(`type` := "number", id := "winChanceDropInput", cls := "vg-rp-input",
                        attr("min") := "0.01", attr("max") := "1.0", attr("step") := "0.01")
                    )
                  ),
                  button(id := "runReanalyzeBtn", cls := "vg-rp-btn btn btn-primary btn-sm")(
                    i(cls := "bi bi-cpu me-1"), I18n.t("viewgame.runAnalysis")
                  )
                ),
                // Position analysis settings panel (collapsible)
                div(id := "analyze-pos-panel", cls := "vg-rp", style := "display:none")(
                  div(cls := "vg-rp-inputs")(
                    div(cls := "vg-rp-field")(
                      label(cls := "vg-rp-label", `for` := "posCandidatesInput")(I18n.t("viewgame.posCandidates")),
                      input(`type` := "number", id := "posCandidatesInput", cls := "vg-rp-input",
                        attr("min") := "1", attr("max") := "5", attr("step") := "1", value := settings.posAnalysisCandidates.toString)
                    ),
                    div(cls := "vg-rp-field")(
                      label(cls := "vg-rp-label", `for` := "posDepthInput")(I18n.t("viewgame.posDepth")),
                      input(`type` := "number", id := "posDepthInput", cls := "vg-rp-input",
                        attr("min") := "5", attr("max") := "40", attr("step") := "1", value := settings.posAnalysisDepth.toString)
                    ),
                    div(cls := "vg-rp-field")(
                      label(cls := "vg-rp-label", `for` := "posSecondsInput")(I18n.t("viewgame.posSeconds")),
                      input(`type` := "number", id := "posSecondsInput", cls := "vg-rp-input",
                        attr("min") := "1", attr("max") := "60", attr("step") := "1", value := settings.posAnalysisSeconds.toString)
                    ),
                    div(cls := "vg-rp-field")(
                      label(cls := "vg-rp-label", `for` := "posSequencesInput")(I18n.t("viewgame.posSequences")),
                      input(`type` := "number", id := "posSequencesInput", cls := "vg-rp-input",
                        attr("min") := "1", attr("max") := "20", attr("step") := "1", value := settings.posAnalysisSequences.toString)
                    )
                  )
                ),
                // Local analysis (ceval) settings panel (collapsible)
                div(id := "ceval-settings-panel", cls := "vg-rp", style := "display:none")(
                  div(cls := "vg-rp-inputs")(
                    div(cls := "vg-rp-field")(
                      label(cls := "vg-rp-label", `for` := "cevalCandidatesInput")(I18n.t("viewgame.posCandidates")),
                      input(`type` := "number", id := "cevalCandidatesInput", cls := "vg-rp-input",
                        attr("min") := "1", attr("max") := "5", attr("step") := "1", value := settings.posAnalysisCandidates.toString)
                    ),
                    div(cls := "vg-rp-field")(
                      label(cls := "vg-rp-label", `for` := "cevalDepthInput")(I18n.t("viewgame.posDepth")),
                      input(`type` := "number", id := "cevalDepthInput", cls := "vg-rp-input",
                        attr("min") := "5", attr("max") := "40", attr("step") := "1", value := settings.posAnalysisDepth.toString)
                    ),
                    div(cls := "vg-rp-field")(
                      label(cls := "vg-rp-label", `for` := "cevalMovetimeInput")(I18n.t("viewgame.posSeconds")),
                      input(`type` := "number", id := "cevalMovetimeInput", cls := "vg-rp-input",
                        attr("min") := "1", attr("max") := "120", attr("step") := "1", value := settings.posAnalysisSeconds.toString)
                    ),
                    div(cls := "vg-rp-field")(
                      label(cls := "vg-rp-label", `for` := "cevalSequencesInput")(I18n.t("viewgame.posSequences")),
                      input(`type` := "number", id := "cevalSequencesInput", cls := "vg-rp-input",
                        attr("min") := "1", attr("max") := "20", attr("step") := "1", value := settings.posAnalysisSequences.toString)
                    )
                  )
                ),
                // EXPERIMENTAL (disabled): lga-settings-panel removed from UI intentionally
                // Local game analysis progress panel
                div(id := "lga-progress", style := "display:none;padding:10px 14px;border-top:1px solid rgba(255,255,255,.06)")(
                  div(cls := "d-flex justify-content-between align-items-center mb-2")(
                    scalatags.Text.all.span(id := "lga-phase", cls := "small text-muted")(""),
                    button(id := "lga-cancel-btn", cls := "btn btn-outline-danger btn-sm py-0 px-2", style := "font-size:.72rem")(
                      i(cls := "bi bi-x-lg me-1"), "Cancel"
                    )
                  ),
                  div(cls := "progress", style := "height:5px;background:#333;border-radius:3px")(
                    div(id := "lga-bar", cls := "progress-bar progress-bar-striped progress-bar-animated bg-info",
                      style := "width:0%;transition:width .3s")()
                  ),
                  div(id := "lga-stats", cls := "text-muted mt-1", style := "font-size:.72rem")()
                ),
                // Analysis progress / result area
                div(id := "analysis-progress", cls := "vg-ap", style := "display:none")(
                  div(cls := "vg-ap-bar")(div(cls := "vg-ap-bar-inner")),
                  p(id := "analysis-progress-msg", cls := "vg-ap-msg")(""),
                  div(id := "analysis-result", cls := "vg-ap-result")()
                ),
                // Position analysis candidates panel
                div(id := "candidates-panel", cls := "vg-cp", style := "display:none")(
                  p(cls := "vg-cp-title")(
                    i(cls := "bi bi-graph-up-arrow me-1"), I18n.t("viewgame.candidatesTitle")
                  ),
                  div(id := "candidates-list")(),
                  div(id := "cand-replay-wrap", style := "display:none")()
                ),
                // Puzzle-at-ply widget (shown when position has a linked puzzle or candidates available)
                div(id := "puzzle-at-ply", cls := "vg-pw", style := "display:none")()
              ),
              // Analysis chart card (shown by JS when scores are available)
              div(id := "analysis-section", style := "display:none")(
                div(cls := "vg-gc-chart")(
                  div(cls := "vg-gc-chart__hdr")(
                    scalatags.Text.all.span(cls := "small text-muted")(
                      i(cls := "bi bi-graph-up me-1"), I18n.t("maintenance.graphTitle")
                    ),
                    scalatags.Text.all.span(cls := "small text-muted vg-chart-hint")(
                      i(cls := "bi bi-mouse me-1"), "Click to jump to position"
                    )
                  ),
                  div(cls := "p-2 vg-chart-body", style := "height:200px")(
                    canvas(id := "analysisChart")
                  )
                )
              )
            )
          )
        ),

        // Hidden metadata for JS
        input(`type` := "hidden", id := "gameHash",        value := kifHash),
        input(`type` := "hidden", id := "gameSente",       value := sente),
        input(`type` := "hidden", id := "gameGote",        value := gote),
        input(`type` := "hidden", id := "gameIsAnalyzed",  value := isAnalyzed.toString),
        input(`type` := "hidden", id := "canAnalyze",      value := canAnalyze.toString),
        input(`type` := "hidden", id := "cfgShallowLimit",        value := settings.shallowLimit.toString),
        input(`type` := "hidden", id := "cfgDeepLimit",           value := settings.deepLimit.toString),
        input(`type` := "hidden", id := "cfgWinChanceDrop",       value := settings.winChanceDropThreshold.toString),
        input(`type` := "hidden", id := "cfgPosCandidates",       value := settings.posAnalysisCandidates.toString),
        input(`type` := "hidden", id := "cfgPosDepth",            value := settings.posAnalysisDepth.toString),
        input(`type` := "hidden", id := "cfgPosSeconds",          value := settings.posAnalysisSeconds.toString),
        input(`type` := "hidden", id := "cfgPosSequences",        value := settings.posAnalysisSequences.toString),

        script(src := "/js/shogiground.js"),
        script(src := "/js/shogiops.js"),
        script(src := "/js/ceval.js"),
        script(src := "/js/view-game.js")
      )
    )
  }

  @cask.get("/view-game-data/:hash")
  def viewGameData(hash: String, request: cask.Request): cask.Response[Value] = {
    Await.result(GameRepository.getGameByHash(hash), 10.seconds) match {
        case None =>
          cask.Response(ujson.Obj("error" -> "Game not found"), statusCode = 404,
            headers = Seq("Content-Type" -> "application/json"))
        case Some(doc) =>
          val kif: String  = doc.get("kif").map(_.asString().getValue).getOrElse("")
          val sente: String = doc.get("sente").map(_.asString().getValue).getOrElse("")
          val gote:  String = doc.get("gote").map(_.asString().getValue).getOrElse("")
          val date:  String = doc.get("date").map(_.asString().getValue).getOrElse("")
          val site:  String = doc.get("site").map(_.asString().getValue).getOrElse("")
          val isAnalyzed: Boolean = doc.get("is_analyzed").exists {
            case b: org.bson.BsonBoolean => b.getValue
            case n: org.bson.BsonInt32   => n.getValue != 0
            case _                       => false
          }

          // Parse KIF → sequence of (SFEN, last-USI-move)
          val (sfens, moves) = try {
            val parsed = GameLoader.parseKif(kif)
            val positions = parsed.allPositions
            val sfenList: Seq[String] = positions.map(_._2.value)
            val moveList: Seq[String] = positions.map {
              case (usis, _) => if (usis.isEmpty) "" else usis.last.usi
            }
            (sfenList, moveList)
          } catch {
            case e: Exception =>
              logger.warn(s"[VIEW-GAME] KIF parse failed for $hash: ${e.getMessage}")
              (Seq.empty[String], Seq.empty[String])
          }

          // Per-move saved analyses
          val moveAnalysisMap: Map[String, String] = {
            import scala.jdk.CollectionConverters._
            doc.get("move_analysis").flatMap { bv =>
              scala.util.Try {
                bv.asDocument().entrySet().asScala.map { e =>
                  e.getKey -> e.getValue.asString().getValue
                }.toMap
              }.toOption
            }.getOrElse(Map.empty)
          }

          // Per-move text comments
          val moveCommentsMap: Map[String, String] = {
            import scala.jdk.CollectionConverters._
            doc.get("move_comments").flatMap { bv =>
              scala.util.Try {
                bv.asDocument().entrySet().asScala.map { e =>
                  e.getKey -> e.getValue.asString().getValue
                }.toMap
              }.toOption
            }.getOrElse(Map.empty)
          }

          // Scores (if analyzed)
          val scoreSeq: Seq[Int] = if (isAnalyzed) {
            doc.get("scores").map { bv =>
              bv.asArray().getValues.asScala.map { v =>
                if (v.isInt32) v.asInt32().getValue
                else if (v.isInt64) v.asInt64().getValue.toInt
                else if (v.isDouble) v.asDouble().getValue.toInt
                else 0
              }.toSeq
            }.getOrElse(Seq.empty)
          } else Seq.empty

          // Puzzles (if analyzed)
          val puzzlesJson: Seq[ujson.Obj] = if (isAnalyzed) {
            import scala.concurrent.ExecutionContext.Implicits.global
            val legPuzzles  = Await.result(PuzzleRepository.getLegacyPuzzlesForGame(hash), 10.seconds)
            val custPuzzles = Await.result(PuzzleRepository.getPuzzlesForGame(hash), 10.seconds)
            val fromLeg = legPuzzles.map { p =>
              val mn  = p.get("move_number").flatMap(v => scala.util.Try(v.asInt32().getValue).toOption).getOrElse(1)
              val pid = p.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
              ujson.Obj("ply" -> (mn - 1).toDouble, "id" -> pid, "comment" -> "", "source" -> "legacy", "status" -> "accepted")
            }
            val fromCust = custPuzzles.flatMap { p =>
              val mn = p.get("move_number").flatMap(v => scala.util.Try(v.asInt32().getValue).toOption).getOrElse(0)
              if (mn > 0) {
                val pid = p.get("_id").map(_.asObjectId().getValue.toString).getOrElse("")
                val cmt = p.get("comments").map(_.asString().getValue).getOrElse("")
                val st  = p.get("status").map(_.asString().getValue).getOrElse("accepted")
                Some(ujson.Obj("ply" -> (mn - 1).toDouble, "id" -> pid, "comment" -> cmt, "source" -> "custom", "status" -> st))
              } else None
            }
            fromLeg ++ fromCust
          } else Seq.empty

          cask.Response(
            ujson.Obj(
              "sente"      -> sente,
              "gote"       -> gote,
              "date"       -> date,
              "site"       -> site,
              "kifHash"    -> hash,
              "isAnalyzed" -> isAnalyzed,
              "sfens"      -> ujson.Arr(sfens.map(ujson.Str(_)): _*),
              "moves"      -> ujson.Arr(moves.map(ujson.Str(_)): _*),
              "scores"       -> ujson.Arr(scoreSeq.map(s => ujson.Num(s.toDouble)): _*),
              "puzzles"      -> ujson.Arr(puzzlesJson: _*),
              "moveAnalysis" -> ujson.read(
                if (moveAnalysisMap.isEmpty) "{}"
                else "{" + moveAnalysisMap.map { case (k, v) =>
                  s""""$k":${ujson.write(ujson.Str(v))}"""
                }.mkString(",") + "}"
              ),
              "moveComments" -> ujson.read(
                if (moveCommentsMap.isEmpty) "{}"
                else "{" + moveCommentsMap.map { case (k, v) =>
                  s""""$k":${ujson.write(ujson.Str(v))}"""
                }.mkString(",") + "}"
              )
            ),
            headers = Seq("Content-Type" -> "application/json")
          )
      }
  }

  // -------------------------------------------------------------------------
  // Database page
  // -------------------------------------------------------------------------

  @cask.get("/database")
  def databasePage(request: cask.Request): cask.Response[String] = {
    withOptionalAuth(request) { userEmailOpt =>
      import scala.concurrent.ExecutionContext.Implicits.global
      val canAnalyze    = userEmailOpt.exists(email => canAccessPage(email, "my-games"))
      val settings      = if (canAnalyze) Await.result(SettingsRepository.getAppSettings(userEmailOpt), 10.seconds)
                          else AppSettings.default
      val pageLang      = getLang(request)
      val totalGamesFut    = GameRepository.countGames()
      val analyzedGamesFut = GameRepository.countAnalyzedGames()
      val totalPuzzlesFut  = if (userEmailOpt.isDefined) PuzzleRepository.countTotalPuzzles()
                             else PuzzleRepository.countTotalPublicPuzzles()
      val totalGames    = Await.result(totalGamesFut, 5.seconds)
      val analyzedGames = Await.result(analyzedGamesFut, 5.seconds)
      val totalPuzzles  = Await.result(totalPuzzlesFut, 5.seconds)
      cask.Response(
        renderDatabasePage(userEmailOpt, settings, totalGames, analyzedGames, totalPuzzles, pageLang, canAnalyze).render,
        headers = Seq("Content-Type" -> "text/html; charset=utf-8")
      )
    }
  }

  def renderDatabasePage(userEmail: Option[String] = None, settings: AppSettings, totalGames: Long = 0L, analyzedGames: Long = 0L, totalPuzzles: Long = 0L, pageLang: String = I18n.defaultLang, canAnalyze: Boolean = true)(implicit lang: String = pageLang) = {
    Components.layout(
      I18n.t("database.pageTitle"),
      userEmail,
      settings,
      appVersion,
      lang = pageLang,
      scripts = Seq(script(src := "/js/database.js"))
    )(
      // Nickname values for JS "My Games" filter
      input(`type` := "hidden", id := "myLishogiNickname",   value := settings.lishogiNickname),
      input(`type` := "hidden", id := "myShogiwarsNickname", value := settings.shogiwarsNickname),
      input(`type` := "hidden", id := "myDojo81Nickname",    value := settings.dojo81Nickname),
      input(`type` := "hidden", id := "canAnalyze",          value := canAnalyze.toString),
      input(`type` := "hidden", id := "isLoggedIn",          value := userEmail.isDefined.toString),

      // Page header
      div(cls := "d-flex flex-wrap justify-content-between align-items-center gap-3 mb-4")(
        h1(cls := "mb-0")(i(cls := "bi bi-database me-2"), I18n.t("database.pageTitle")),
        if (canAnalyze) a(href := "/fetch-games", cls := "btn btn-outline-secondary btn-sm")(
          i(cls := "bi bi-cloud-download me-1"), I18n.t("games.pageTitle")
        ) else ()
      ),

      // Stats row — total/analyzed/pending rendered server-side; puzzles updated by JS
      div(cls := "row g-3 mb-4")(
        div(cls := "col-6 col-lg-3")(
          div(cls := "card bg-dark border-secondary text-center py-3 h-100")(
            div(cls := "fs-1 fw-bold text-white", id := "statTotal")(totalGames.toString),
            div(cls := "text-muted small mt-1")(I18n.t("database.totalGames"))
          )
        ),
        div(cls := "col-6 col-lg-3")(
          div(cls := "card bg-dark border-secondary text-center py-3 h-100")(
            div(cls := "fs-1 fw-bold text-success", id := "statAnalyzed")(analyzedGames.toString),
            div(cls := "text-muted small mt-1")(I18n.t("database.analyzed"))
          )
        ),
        div(cls := "col-6 col-lg-3")(
          div(cls := "card bg-dark border-secondary text-center py-3 h-100")(
            div(cls := "fs-1 fw-bold text-warning", id := "statPending")((totalGames - analyzedGames).toString),
            div(cls := "text-muted small mt-1")(I18n.t("database.pending"))
          )
        ),
        div(cls := "col-6 col-lg-3")(
          div(cls := "card bg-dark border-secondary text-center py-3 h-100")(
            div(cls := "fs-1 fw-bold text-info", id := "statPuzzles")(totalPuzzles.toString),
            div(cls := "text-muted small mt-1")(I18n.t("database.totalPuzzles"))
          )
        )
      ),

      // Filter bar
      div(cls := "card bg-dark border-secondary mb-3")(
        div(cls := "card-body py-2 d-flex flex-wrap align-items-center gap-3")(
          // My games toggle — only shown to authenticated users
          if (userEmail.isDefined) div(cls := "form-check form-switch mb-0")(
            input(cls := "form-check-input", `type` := "checkbox", id := "myGamesToggle"),
            label(cls := "form-check-label user-select-none", `for` := "myGamesToggle")(I18n.t("database.myGames"))
          ) else (),
          div(cls := "vr d-none d-md-block"),
          // Source filter
          div(cls := "d-flex align-items-center gap-2")(
            scalatags.Text.all.span(cls := "text-secondary small text-nowrap")(I18n.t("database.filterSource")),
            div(cls := "btn-group btn-group-sm", role := "group")(
              input(`type` := "radio", cls := "btn-check", name := "sourceFilter", id := "srcAll",       value := "all",       checked := true),
              label(cls := "btn btn-outline-secondary", `for` := "srcAll")(I18n.t("database.filterAll")),
              input(`type` := "radio", cls := "btn-check", name := "sourceFilter", id := "srcLishogi",   value := "lishogi"),
              label(cls := "btn btn-outline-secondary", `for` := "srcLishogi")("Lishogi"),
              input(`type` := "radio", cls := "btn-check", name := "sourceFilter", id := "srcShogiwars", value := "shogiwars"),
              label(cls := "btn btn-outline-secondary", `for` := "srcShogiwars")("ShogiWars"),
              input(`type` := "radio", cls := "btn-check", name := "sourceFilter", id := "srcDojo81",    value := "dojo81"),
              label(cls := "btn btn-outline-secondary", `for` := "srcDojo81")("81Dojo")
            )
          ),
          // Status filter
          div(cls := "d-flex align-items-center gap-2")(
            scalatags.Text.all.span(cls := "text-secondary small text-nowrap")(I18n.t("database.filterStatus")),
            div(cls := "btn-group btn-group-sm", role := "group")(
              input(`type` := "radio", cls := "btn-check", name := "statusFilter", id := "stAll",      value := "all",      checked := true),
              label(cls := "btn btn-outline-secondary", `for` := "stAll")(I18n.t("database.filterAll")),
              input(`type` := "radio", cls := "btn-check", name := "statusFilter", id := "stAnalyzed", value := "analyzed"),
              label(cls := "btn btn-outline-secondary", `for` := "stAnalyzed")(I18n.t("database.filterAnalyzed")),
              input(`type` := "radio", cls := "btn-check", name := "statusFilter", id := "stPending",  value := "pending"),
              label(cls := "btn btn-outline-secondary", `for` := "stPending")(I18n.t("database.filterNotAnalyzed"))
            )
          ),
          // Player search
          div(cls := "flex-grow-1")(
            input(`type` := "text", id := "searchPlayer",
              cls := "form-control form-control-sm bg-dark text-light border-secondary",
              placeholder := I18n.t("database.searchPlayer"))
          ),
          // Page size selector
          div(cls := "d-flex align-items-center gap-2 ms-auto")(
            scalatags.Text.all.span(cls := "text-secondary small text-nowrap")(I18n.t("database.perPage")),
            tag("select")(id := "pageSizeSelector",
              cls := "form-select form-select-sm bg-dark text-light border-secondary",
              style := "width:auto")(
              tag("option")(value := "25")("25"),
              tag("option")(value := "50")("50"),
              tag("option")(value := "100")("100")
            )
          )
        )
      ),

      // Games table card
      div(cls := "card bg-dark border-secondary")(
        div(cls := "card-header border-secondary d-flex justify-content-between align-items-center py-2")(
          scalatags.Text.all.span(cls := "text-muted small", id := "showingCount")(""),
          button(cls := "btn btn-outline-secondary btn-sm", id := "refreshBtn")(
            i(cls := "bi bi-arrow-clockwise me-1"), I18n.t("maintenance.refresh")
          )
        ),
        div(cls := "table-responsive")(
          tag("table")(cls := "table table-dark table-hover table-sm mb-0", id := "gamesTable")(
            tag("thead")(cls := "table-dark")(
              tag("tr")(
                tag("th")(cls := "text-nowrap", id := "colDate", style := "cursor:pointer;user-select:none")(
                  I18n.t("database.colDate"), " ", i(cls := "bi bi-sort-down", id := "sortDateIcon", style := "font-size:.8em;opacity:.5")
                ),
                tag("th")(I18n.t("database.colPlayers")),
                tag("th")(cls := "d-none d-md-table-cell")(I18n.t("database.colSource")),
                tag("th")(I18n.t("database.colStatus")),
                if (userEmail.isDefined) tag("th")(cls := "d-none d-md-table-cell")(I18n.t("database.colPuzzles")) else (),
                tag("th")(I18n.t("database.colActions"))
              )
            ),
            tbody(id := "gamesTableBody")()
          )
        )
      ),
      div(id := "paginationContainer", cls := "d-flex justify-content-center mt-3")()
    )
  }

  @cask.get("/database-games")
  def databaseGames(
      page: Int = 1,
      pageSize: Int = 25,
      source: String = "all",
      status: String = "all",
      search: String = "",
      myGames: String = "false",
      sort: String = "desc",
      request: cask.Request
  ): cask.Response[Value] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val isLoggedIn     = getSessionUserEmail(request).isDefined
    val safePageSize   = pageSize.max(10).min(100)
    val safePage       = page.max(1)
    val myGamesEnabled = myGames == "true"
    val safeSort       = if (sort == "asc") "asc" else "desc"

    val nicknames: Seq[String] = if (myGamesEnabled && isLoggedIn) {
      val s = Await.result(SettingsRepository.getAppSettings(getSessionUserEmail(request)), 5.seconds)
      Seq(s.lishogiNickname, s.shogiwarsNickname, s.dojo81Nickname)
        .filter(n => n.nonEmpty && n != "lishogi_user" && n != "swars_user" && n != "dojo81_user")
    } else Seq.empty

    // Start all futures concurrently
    val pagedFut      = GameRepository.getGamesPaged(safePage, safePageSize, source, status, search, nicknames, safeSort)
    val totalGamesFut = GameRepository.countGames()
    val analyzedFut   = GameRepository.countAnalyzedGames()
    val puzzlesFut    = if (isLoggedIn) PuzzleRepository.countTotalPuzzles()
                        else            PuzzleRepository.countTotalPublicPuzzles()

    val (docs, filteredTotal) = Await.result(pagedFut, 15.seconds)
    val statsTotal    = Await.result(totalGamesFut, 5.seconds)
    val statsAnalyzed = Await.result(analyzedFut, 5.seconds)
    val statsPuzzles  = Await.result(puzzlesFut, 5.seconds)

    // Fetch puzzle counts only for the current page (fast — max pageSize queries in parallel)
    val withCounts = Await.result(
      Future.sequence(docs.map { doc =>
        val kifHash = doc.get("kif_hash").map(_.asString().getValue).getOrElse("")
        val countFut = if (kifHash.nonEmpty)
          if (isLoggedIn) PuzzleRepository.countPuzzlesForGame(kifHash)
          else            PuzzleRepository.countPublicPuzzlesForGame(kifHash)
        else
          Future.successful(0L)
        countFut.map(count => (doc, count))
      }),
      15.seconds
    )

    val gamesArr = withCounts.map { case (doc, puzzleCount) =>
      val kifHash    = doc.get("kif_hash").map(_.asString().getValue).getOrElse("")
      val sente      = doc.get("sente").map(_.asString().getValue).getOrElse("")
      val gote       = doc.get("gote").map(_.asString().getValue).getOrElse("")
      val date       = doc.get("date").map(_.asString().getValue).getOrElse("")
      val site       = doc.get("site").map(_.asString().getValue).getOrElse("")
      val isAnalyzed = doc.get("is_analyzed").exists {
        case bv: org.bson.BsonBoolean => bv.getValue
        case bv: org.bson.BsonInt32   => bv.getValue != 0
        case _                        => false
      }
      ujson.Obj(
        "kifHash"     -> kifHash,
        "sente"       -> sente,
        "gote"        -> gote,
        "date"        -> date,
        "site"        -> site,
        "isAnalyzed"  -> isAnalyzed,
        "puzzleCount" -> puzzleCount.toInt
      )
    }

    cask.Response(
      ujson.Obj(
        "games"         -> ujson.Arr(gamesArr: _*),
        "total"         -> filteredTotal,
        "page"          -> safePage,
        "pageSize"      -> safePageSize,
        "statsTotal"    -> statsTotal,
        "statsAnalyzed" -> statsAnalyzed,
        "statsPuzzles"  -> statsPuzzles
      ),
      headers = Seq("Content-Type" -> "application/json")
    )
  }

  @cask.post("/database-analyze")
  def databaseAnalyze(request: cask.Request): cask.Response[Value] = {
    withAuthJson(request, "my-games") { userEmailStr =>
      val userEmail = if (oauthEnabled) Some(userEmailStr) else getSessionUserEmail(request)
      val body = try { Right(ujson.read(request.text())) } catch {
        case e: Exception => Left(cask.Response(ujson.Obj("error" -> s"Invalid JSON: ${e.getMessage}"), statusCode = 400))
      }
      body match {
        case Left(err) => err
        case Right(json) =>
          val kifHash = json.obj.get("kifHash").map(_.str).getOrElse("")
          if (kifHash.isEmpty) {
            cask.Response(ujson.Obj("error" -> "kifHash is required"), statusCode = 400)
          } else {
            Await.result(GameRepository.getGameByHash(kifHash), 10.seconds) match {
              case None =>
                cask.Response(ujson.Obj("error" -> "Game not found"), statusCode = 404)
              case Some(doc) =>
                val kif    = doc.get("kif").map(_.asString().getValue).getOrElse("")
                val player = doc.get("sente").map(_.asString().getValue).getOrElse("unknown")
                val site   = doc.get("site").map(_.asString().getValue).getOrElse("unknown")
                if (kif.isEmpty) {
                  cask.Response(ujson.Obj("error" -> "Game KIF content is missing"), statusCode = 500)
                } else {
                  val taskId = TaskManager.createTask(Some(kifHash))
                  AnalysisQueue.enqueue(taskId, kif, player, site, userEmail)
                  cask.Response(ujson.Obj("taskId" -> taskId), headers = Seq("Content-Type" -> "application/json"))
                }
            }
          }
      }
    }
  }

  // -------------------------------------------------------------------------
  // Reanalyze game with custom settings
  // -------------------------------------------------------------------------

  @cask.post("/reanalyze-game")
  def reanalyzeGame(request: cask.Request): cask.Response[Value] = {
    withAuthJson(request, "my-games") { userEmailStr =>
      val userEmail = if (oauthEnabled) Some(userEmailStr) else getSessionUserEmail(request)
      val body = try { Right(ujson.read(request.text())) } catch {
        case e: Exception => Left(cask.Response(ujson.Obj("error" -> s"Invalid JSON: ${e.getMessage}"), statusCode = 400))
      }
      body match {
        case Left(err) => err
        case Right(json) =>
          val kifHash          = json.obj.get("kifHash").map(_.str).getOrElse("")
          val shallowOverride  = json.obj.get("shallowLimit").flatMap(v => scala.util.Try(v.num.toInt).toOption)
          val deepOverride     = json.obj.get("deepLimit").flatMap(v => scala.util.Try(v.num.toInt).toOption)
          val wcdOverride      = json.obj.get("winChanceDropThreshold").flatMap(v => scala.util.Try(v.num).toOption)
          if (kifHash.isEmpty) {
            cask.Response(ujson.Obj("error" -> "kifHash is required"), statusCode = 400)
          } else {
            Await.result(GameRepository.getGameByHash(kifHash), 10.seconds) match {
              case None =>
                cask.Response(ujson.Obj("error" -> "Game not found"), statusCode = 404)
              case Some(doc) =>
                val kif    = doc.get("kif").map(_.asString().getValue).getOrElse("")
                val player = doc.get("sente").map(_.asString().getValue).getOrElse("unknown")
                val site   = doc.get("site").map(_.asString().getValue).getOrElse("unknown")
                if (kif.isEmpty) {
                  cask.Response(ujson.Obj("error" -> "Game KIF content is missing"), statusCode = 500)
                } else {
                  val taskId = TaskManager.createTask(Some(kifHash))
                  AnalysisQueue.enqueue(taskId, kif, player, site, userEmail, shallowOverride, deepOverride, wcdOverride)
                  cask.Response(ujson.Obj("taskId" -> taskId), headers = Seq("Content-Type" -> "application/json"))
                }
            }
          }
      }
    }
  }

  // -------------------------------------------------------------------------
  // Analyze a single board position (returns candidate moves with arrows data)
  // -------------------------------------------------------------------------

  @cask.post("/analyze-position")
  def analyzePositionPost(request: cask.Request): cask.Response[Value] = {
    withAuthJson(request, "my-games") { userEmailStr =>
      val userEmail = if (oauthEnabled) Some(userEmailStr) else getSessionUserEmail(request)
      val body = try { Right(ujson.read(request.text())) } catch {
        case e: Exception => Left(cask.Response(ujson.Obj("error" -> s"Invalid JSON: ${e.getMessage}"), statusCode = 400))
      }
      body match {
        case Left(err) => err
        case Right(json) =>
          val sfen      = json.obj.get("sfen").map(_.str).getOrElse("")
          val seconds   = json.obj.get("seconds").flatMap(v => scala.util.Try(v.num.toInt).toOption).getOrElse(5)
          val multiPv   = json.obj.get("multiPv").flatMap(v => scala.util.Try(v.num.toInt).toOption).getOrElse(3)
          val depth     = json.obj.get("depth").flatMap(v => scala.util.Try(v.num.toInt).toOption).getOrElse(20)
          if (sfen.isEmpty) {
            cask.Response(ujson.Obj("error" -> "sfen is required"), statusCode = 400)
          } else {
            val taskId = TaskManager.createTask()
            Future {
              try {
                TaskManager.updateProgress(taskId, s"Analyzing position (${seconds}s, depth ${depth}, ${multiPv} candidates)…")
                val settings      = Await.result(SettingsRepository.getAppSettings(userEmail), 10.seconds)
                val engineManager = getEngineManager(settings.enginePath)
                val playerColor   = SfenUtils.sfenTurnToColor(sfen)
                val results       = AnalysisService.analyzePosition(
                  engineManager, sfen, depth = depth, multiPv = multiPv,
                  time = Some(seconds), playerColor = playerColor
                )
                val jsonResult = ujson.write(ujson.Arr(results: _*))
                TaskManager.completeWithJson(taskId, "Position analysis complete.", jsonResult)
              } catch {
                case e: Exception =>
                  logger.error(s"[VIEW-GAME] Position analysis failed", e)
                  TaskManager.fail(taskId, e.getMessage)
              }
            }
            cask.Response(ujson.Obj("taskId" -> taskId), headers = Seq("Content-Type" -> "application/json"))
          }
      }
    }
  }

  @cask.post("/save-local-game-analysis")
  def saveLocalGameAnalysis(request: cask.Request): cask.Response[Value] = {
    withAuthJson(request, "my-games") { userEmailStr =>
      val userEmail = if (oauthEnabled) userEmailStr else getSessionUserEmail(request).getOrElse(userEmailStr)
      val body = try { Right(ujson.read(request.text())) } catch {
        case e: Exception => Left(cask.Response(ujson.Obj("error" -> e.getMessage), statusCode = 400))
      }
      body match {
        case Left(err) => err
        case Right(json) =>
          val kifHash = json.obj.get("kifHash").map(_.str).getOrElse("")
          if (kifHash.isEmpty) {
            cask.Response(ujson.Obj("error" -> "kifHash required"), statusCode = 400)
          } else {
            import scala.concurrent.ExecutionContext.Implicits.global

            // Save scores and mark game as analyzed
            val scores: Seq[Int] = json.obj.get("scores")
              .map(_.arr.map(v => scala.util.Try(v.num.toInt).getOrElse(0)).toSeq)
              .getOrElse(Seq.empty)
            if (scores.nonEmpty) Await.result(GameRepository.saveScores(kifHash, scores), 30.seconds)
            Await.result(GameRepository.markAsAnalyzed(kifHash), 5.seconds)

            // Look up game metadata once for puzzle names
            val gameDoc = Await.result(GameRepository.getGameByHash(kifHash), 5.seconds)
            val sente   = gameDoc.flatMap(_.get("sente").map(_.asString().getValue)).getOrElse("Sente")
            val gote    = gameDoc.flatMap(_.get("gote").map(_.asString().getValue)).getOrElse("Gote")

            // Save move analyses + create review puzzles for each blunder
            val blunders = json.obj.get("blunders").map(_.arr.toSeq).getOrElse(Seq.empty)
            val puzzleResults: Seq[ujson.Obj] = blunders.flatMap { b =>
              val moveIdx   = b.obj.get("idx").flatMap(v => scala.util.Try(v.num.toInt).toOption).getOrElse(-1)
              val sfen      = b.obj.get("sfen").map(_.str).getOrElse("")
              val candidates = b.obj.getOrElse("candidates", ujson.Arr())
              val orientStr  = b.obj.get("orientation").map(_.str).getOrElse("sente")
              if (moveIdx < 0 || sfen.isEmpty) None
              else {
                // Save move analysis for this blunder position
                val analysisJson = ujson.write(ujson.Obj(
                  "candidates"  -> candidates,
                  "orientation" -> ujson.Str(orientStr),
                  "source"      -> ujson.Str("local")
                ))
                Await.result(GameRepository.saveMoveAnalysis(kifHash, moveIdx, analysisJson), 5.seconds)

                // Create a review puzzle
                val name   = s"Move $moveIdx \u2014 $sente vs $gote"
                val result = Await.result(
                  PuzzleRepository.savePuzzle(
                    name         = name,
                    sfen         = sfen,
                    userEmail    = userEmail,
                    status       = "review",
                    source       = "game",
                    gameKifHash  = Some(kifHash),
                    moveNumber   = Some(moveIdx)
                  ),
                  5.seconds
                )
                val id = result.getInsertedId.asObjectId().getValue.toString
                Some(ujson.Obj("ply" -> moveIdx.toDouble, "id" -> id, "comment" -> "", "source" -> "custom", "status" -> "review"))
              }
            }

            cask.Response(
              ujson.Obj("success" -> true, "puzzles" -> ujson.Arr(puzzleResults: _*)),
              headers = Seq("Content-Type" -> "application/json")
            )
          }
      }
    }
  }

  @cask.post("/save-move-analysis")
  def saveMoveAnalysisPost(request: cask.Request): cask.Response[Value] = {
    withAuthJson(request, "my-games") { _ =>
      val body = try { Right(ujson.read(request.text())) } catch {
        case e: Exception => Left(cask.Response(ujson.Obj("error" -> s"Invalid JSON: ${e.getMessage}"), statusCode = 400))
      }
      body match {
        case Left(err) => err
        case Right(json) =>
          val kifHash     = json.obj.get("kifHash").map(_.str).getOrElse("")
          val moveIdx     = json.obj.get("moveIdx").flatMap(v => scala.util.Try(v.num.toInt).toOption).getOrElse(-1)
          val orientation = json.obj.get("orientation").map(_.str).getOrElse("sente")
          val source      = json.obj.get("source").map(_.str).getOrElse("server")
          val analysisJson = ujson.write(ujson.Obj(
            "candidates"  -> json.obj.getOrElse("candidates", ujson.Arr()),
            "orientation" -> ujson.Str(orientation),
            "source"      -> ujson.Str(source)
          ))
          if (kifHash.isEmpty || moveIdx < 0) {
            cask.Response(ujson.Obj("error" -> "kifHash and moveIdx are required"), statusCode = 400)
          } else {
            Await.result(GameRepository.saveMoveAnalysis(kifHash, moveIdx, analysisJson), 5.seconds)
            cask.Response(ujson.Obj("success" -> true), headers = Seq("Content-Type" -> "application/json"))
          }
      }
    }
  }

  @cask.post("/save-move-comment")
  def saveMoveComment(request: cask.Request): cask.Response[Value] = {
    withAuthJson(request, "my-games") { _ =>
      val body = try { Right(ujson.read(request.text())) } catch {
        case e: Exception => Left(cask.Response(ujson.Obj("error" -> s"Invalid JSON: ${e.getMessage}"), statusCode = 400))
      }
      body match {
        case Left(err) => err
        case Right(json) =>
          val kifHash = json.obj.get("kifHash").map(_.str).getOrElse("")
          val moveIdx = json.obj.get("moveIdx").flatMap(v => scala.util.Try(v.num.toInt).toOption).getOrElse(-1)
          val comment = json.obj.get("comment").map(_.str).getOrElse("")
          if (kifHash.isEmpty || moveIdx < 0) {
            cask.Response(ujson.Obj("error" -> "kifHash and moveIdx are required"), statusCode = 400,
              headers = Seq("Content-Type" -> "application/json"))
          } else {
            Await.result(GameRepository.saveMoveComment(kifHash, moveIdx, comment), 5.seconds)
            cask.Response(ujson.Obj("success" -> true, "deleted" -> comment.trim.isEmpty),
              headers = Seq("Content-Type" -> "application/json"))
          }
      }
    }
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
            cask.Response(ujson.Obj("error" -> "KIF is required"), statusCode = 400)
          } else {
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
            "resultJson" -> ujson.Str(task.resultJson.getOrElse("")),
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

      if (targetPlayer.trim.isEmpty) {
        cask.Response(ujson.Obj("error" -> s"No nickname configured or provided for source '$sourceId'."), headers = Seq("Content-Type" -> "application/json"))
      } else {
        val taskId = TaskManager.createTask()
        
        val effectiveLimit = if (force) limit else 100
        
        Future {
          try {
            val games = if (!force) {
              val dbGames = Await.result(GameRepository.findByPlayerAndSource(targetPlayer, sourceId, effectiveLimit), 10.seconds)
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
              mapped
            } else {
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
                  } catch {
                    case _: Exception => // game might already exist
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

            val filteredGames = gamesWithDbStatus

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
                                          attr("data-site") := g.site.getOrElse(""))("Analyze game")
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
    val games = try {
      ujson.read(request.text()).arr.toSeq
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
          case _: com.mongodb.MongoWriteException =>
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
      val hash = ujson.read(request.text())("hash").str
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
      try {
        if (hash.isEmpty) {
          cask.Response("Missing hash parameter", statusCode = 400)
        } else {
          getAnnotatedKif(hash) match {
            case Right(annotated) =>
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

              if (response.statusCode() == 303) {
                val location = response.headers().firstValue("Location").orElse("")
                val fullUrl = if (location.startsWith("http")) location else "https://lishogi.org" + location
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
          if (comment.nonEmpty) Some(moveNumber -> comment)
          else None
        }.toMap

        val annotated = KifAnnotator.annotate(kif, comments)
        Right(annotated)
      case None =>
        Left("Game not found")
    }
  }

  initialize()
}
