package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.model.Filters
import org.mongodb.scala.model.ReplaceOptions
import org.mongodb.scala.model.Updates
import org.mongodb.scala.model.UpdateOptions
import shogi.puzzler.maintenance.Rect
import scala.concurrent.Future
import com.typesafe.config.ConfigFactory
import scala.collection.mutable

object SettingsRepository {
  private val collection = MongoDBConnection.settingsCollection
  private val config = ConfigFactory.load()

  def getSetting(key: String, defaultValue: String): Future[String] = {
    collection.find(Filters.equal("_id", key)).toFuture().map { docs =>
      docs.headOption.map(_.getString("value")).getOrElse(defaultValue)
    }(scala.concurrent.ExecutionContext.global)
  }

  def getIntSetting(key: String, defaultValue: Int): Future[Int] = {
    collection.find(Filters.equal("_id", key)).toFuture().map { docs =>
      docs.headOption.map(_.getInteger("value").toInt).getOrElse(defaultValue)
    }(scala.concurrent.ExecutionContext.global)
  }

  def saveSetting(key: String, value: String): Future[org.mongodb.scala.result.UpdateResult] = {
    val doc = Document("_id" -> key, "value" -> value)
    collection.replaceOne(Filters.equal("_id", key), doc, ReplaceOptions().upsert(true)).toFuture()
  }

  def saveIntSetting(key: String, value: Int): Future[org.mongodb.scala.result.UpdateResult] = {
    val doc = Document("_id" -> key, "value" -> value)
    collection.replaceOne(Filters.equal("_id", key), doc, ReplaceOptions().upsert(true)).toFuture()
  }

  def getDoubleSetting(key: String, defaultValue: Double): Future[Double] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    collection.find(Filters.equal("_id", key)).toFuture().map { docs =>
      if (docs.isEmpty) defaultValue
      else {
        val d = docs.head
        try {
          val res: Double = d.getDouble("value")
          res
        } catch {
          case _: Exception =>
            try { 
              val res: Double = d.getInteger("value").toDouble
              res
            }
            catch { case _: Exception => defaultValue }
        }
      }
    }
  }

  def saveDoubleSetting(key: String, value: Double): Future[org.mongodb.scala.result.UpdateResult] = {
    val doc = Document("_id" -> key, "value" -> value)
    collection.replaceOne(Filters.equal("_id", key), doc, ReplaceOptions().upsert(true)).toFuture()
  }

  def getAllSettings(): Future[Map[String, Any]] = {
    collection.find().toFuture().map { docs =>
      docs.map(d => d.getString("_id") -> d.get("value")).toMap
    }(scala.concurrent.ExecutionContext.global)
  }

  private def fetchFromIndividualKeys(): Future[AppSettings] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val lishogiNickF = getSetting("lishogi_nickname", config.getString("app.fetcher.lishogi.nickname"))
    val swarsNickF = getSetting("shogiwars_nickname", config.getString("app.fetcher.shogiwars.nickname"))
    val dojoNickF = getSetting("dojo81_nickname", config.getString("app.fetcher.dojo81.nickname"))
    val engineF = getSetting("engine_path", config.getString("app.engine.path"))
    val shallowF = getIntSetting("shallow_limit", config.getInt("app.analysis.shallow-limit"))
    val deepF = getIntSetting("deep_limit", config.getInt("app.analysis.deep-limit"))
    val thresholdF = getDoubleSetting("win_chance_drop_threshold", config.getDouble("app.analysis.win-chance-drop-threshold"))

    for {
      lishogiNick <- lishogiNickF
      swarsNick <- swarsNickF
      dojoNick <- dojoNickF
      engine <- engineF
      shallow <- shallowF
      deep <- deepF
      threshold <- thresholdF
      workers <- getIntSetting("analysis_workers", config.getInt("app.analysis.workers"))
    } yield {
      val defaultRegions = shogi.puzzler.maintenance.ShogiOCR.detectDefaultRegions()
      AppSettings(
        lishogiNick,
        swarsNick,
        dojoNick,
        engine,
        shallow,
        deep,
        threshold,
        Map("Default" -> defaultRegions),
        activeOcrProfile = Some("Default"),
        analysisWorkers = workers,
        isConfigured = false
      )
    }
  }

  private def fetchFromDoc(doc: Document): AppSettings = {
    val threshold: Double = try {
      doc.getDouble("win_chance_drop_threshold")
    } catch {
      case _: Exception =>
        try { doc.getInteger("win_chance_drop_threshold").toDouble }
        catch { case _: Exception => config.getDouble("app.analysis.win-chance-drop-threshold") }
    }

    val ocrProfiles: Map[String, Map[String, Rect]] = if (doc.containsKey("ocr_profiles")) {
      try {
        val json = ujson.read(doc.toJson())
        if (json.obj.contains("ocr_profiles")) {
          val profiles = json("ocr_profiles").obj.map { case (profileName, regionsJson) =>
            profileName -> regionsJson.obj.map { case (k, v) =>
              k -> Rect(v("x").num.toInt, v("y").num.toInt, v("w").num.toInt, v("h").num.toInt)
            }.toMap
          }.toMap
          if (profiles.isEmpty) Map("Default" -> shogi.puzzler.maintenance.ShogiOCR.detectDefaultRegions())
          else profiles
        } else Map("Default" -> shogi.puzzler.maintenance.ShogiOCR.detectDefaultRegions())
      } catch {
        case e: Exception =>
          println(s"[ERROR] Failed to parse ocr_profiles: ${e.getMessage}")
          Map("Default" -> shogi.puzzler.maintenance.ShogiOCR.detectDefaultRegions())
      }
    } else {
      // Migration from old ocr_regions if exists
      if (doc.containsKey("ocr_regions")) {
        try {
          val json = ujson.read(doc.toJson())
          if (json.obj.contains("ocr_regions")) {
            val regions = json("ocr_regions").obj.map { case (k, v) =>
              k -> Rect(v("x").num.toInt, v("y").num.toInt, v("w").num.toInt, v("h").num.toInt)
            }.toMap
            Map("Default" -> regions)
          } else Map("Default" -> shogi.puzzler.maintenance.ShogiOCR.detectDefaultRegions())
        } catch {
          case _: Exception => Map("Default" -> shogi.puzzler.maintenance.ShogiOCR.detectDefaultRegions())
        }
      } else Map("Default" -> shogi.puzzler.maintenance.ShogiOCR.detectDefaultRegions())
    }

    val activeOcrProfile = if (doc.containsKey("active_ocr_profile")) {
      Option(doc.getString("active_ocr_profile"))
    } else if (ocrProfiles.nonEmpty) {
      Some(ocrProfiles.keys.head)
    } else None

    AppSettings(
      lishogiNickname = Option(doc.getString("lishogi_nickname")).getOrElse(config.getString("app.fetcher.lishogi.nickname")),
      shogiwarsNickname = Option(doc.getString("shogiwars_nickname")).getOrElse(config.getString("app.fetcher.shogiwars.nickname")),
      dojo81Nickname = Option(doc.getString("dojo81_nickname")).getOrElse(config.getString("app.fetcher.dojo81.nickname")),
      enginePath = Option(doc.getString("engine_path")).getOrElse(config.getString("app.engine.path")),
      shallowLimit = Option(doc.getInteger("shallow_limit")).map(_.toInt).getOrElse(config.getInt("app.analysis.shallow-limit")),
      deepLimit = Option(doc.getInteger("deep_limit")).map(_.toInt).getOrElse(config.getInt("app.analysis.deep-limit")),
      winChanceDropThreshold = threshold,
      ocrProfiles = ocrProfiles,
      activeOcrProfile = activeOcrProfile,
      analysisWorkers = Option(doc.getInteger("analysis_workers")).map(_.toInt).getOrElse(config.getInt("app.analysis.workers")),
      isConfigured = true,
      posAnalysisCandidates = Option(doc.getInteger("pos_analysis_candidates")).map(_.toInt).getOrElse(3),
      posAnalysisDepth = Option(doc.getInteger("pos_analysis_depth")).map(_.toInt).getOrElse(20),
      posAnalysisSeconds = Option(doc.getInteger("pos_analysis_seconds")).map(_.toInt).getOrElse(5),
      posAnalysisSequences = Option(doc.getInteger("pos_analysis_sequences")).map(_.toInt).getOrElse(5)
    )
  }

  // Helper to get all relevant app settings with defaults from config
  def getAppSettings(userEmail: Option[String] = None): Future[AppSettings] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val id = userEmail.getOrElse("global")

    for {
      globalDocOpt <- collection.find(Filters.equal("_id", "global")).toFuture().map(_.headOption)
      globalSettings <- globalDocOpt match {
        case Some(doc) => Future.successful(fetchFromDoc(doc))
        case None => fetchFromIndividualKeys()
      }
      result <- if (id == "global") Future.successful(globalSettings)
      else {
        collection.find(Filters.equal("_id", id)).toFuture().flatMap { userDocs =>
          if (userDocs.nonEmpty) {
            val userSettings = fetchFromDoc(userDocs.head)
            Future.successful(userSettings.copy(ocrProfiles = globalSettings.ocrProfiles))
          } else {
            Future.successful(globalSettings.copy(isConfigured = false))
          }
        }
      }
    } yield result
  }

  def saveAppSettings(userEmail: String, settings: AppSettings): Future[org.mongodb.scala.result.UpdateResult] = {
    val ocrProfilesDoc = Document(settings.ocrProfiles.map { case (profileName, regions) =>
      profileName -> Document(regions.map { case (k, v) =>
        k -> Document("x" -> v.x, "y" -> v.y, "w" -> v.width, "h" -> v.height)
      })
    })

    var finalDoc = Document(
      "_id" -> userEmail,
      "lishogi_nickname" -> settings.lishogiNickname,
      "shogiwars_nickname" -> settings.shogiwarsNickname,
      "dojo81_nickname" -> settings.dojo81Nickname,
      "engine_path" -> settings.enginePath,
      "shallow_limit" -> settings.shallowLimit,
      "deep_limit" -> settings.deepLimit,
      "win_chance_drop_threshold" -> settings.winChanceDropThreshold,
      "active_ocr_profile" -> settings.activeOcrProfile.getOrElse(""),
      "pos_analysis_candidates" -> settings.posAnalysisCandidates,
      "pos_analysis_depth" -> settings.posAnalysisDepth,
      "pos_analysis_seconds" -> settings.posAnalysisSeconds,
      "pos_analysis_sequences" -> settings.posAnalysisSequences
    )

    if (userEmail == "global") {
      finalDoc = finalDoc ++ Document(
        "analysis_workers" -> settings.analysisWorkers,
        "ocr_profiles" -> ocrProfilesDoc
      )
    }

    collection.replaceOne(Filters.equal("_id", userEmail), finalDoc, ReplaceOptions().upsert(true)).toFuture()
  }

  def updateGlobalOcrProfiles(profiles: Map[String, Map[String, Rect]]): Future[org.mongodb.scala.result.UpdateResult] = {
    val ocrProfilesDoc = Document(profiles.map { case (profileName, regions) =>
      profileName -> Document(regions.map { case (k, v) =>
        k -> Document("x" -> v.x, "y" -> v.y, "w" -> v.width, "h" -> v.height)
      })
    })
    collection.updateOne(
      Filters.equal("_id", "global"),
      Updates.set("ocr_profiles", ocrProfilesDoc),
      UpdateOptions().upsert(true)
    ).toFuture()
  }
}

case class AppSettings(
  lishogiNickname: String,
  shogiwarsNickname: String,
  dojo81Nickname: String,
  enginePath: String,
  shallowLimit: Int,
  deepLimit: Int,
  winChanceDropThreshold: Double,
  ocrProfiles: Map[String, Map[String, Rect]] = Map.empty,
  activeOcrProfile: Option[String] = None,
  analysisWorkers: Int = 1,
  isConfigured: Boolean = false,
  posAnalysisCandidates: Int = 3,
  posAnalysisDepth: Int = 20,
  posAnalysisSeconds: Int = 5,
  posAnalysisSequences: Int = 5
)

object AppSettings {
  def default: AppSettings = {
    val config = com.typesafe.config.ConfigFactory.load()
    AppSettings(
      lishogiNickname = config.getString("app.fetcher.lishogi.nickname"),
      shogiwarsNickname = config.getString("app.fetcher.shogiwars.nickname"),
      dojo81Nickname = config.getString("app.fetcher.dojo81.nickname"),
      enginePath = config.getString("app.engine.path"),
      shallowLimit = config.getInt("app.analysis.shallow-limit"),
      deepLimit = config.getInt("app.analysis.deep-limit"),
      winChanceDropThreshold = config.getDouble("app.analysis.win-chance-drop-threshold"),
      ocrProfiles = Map("Default" -> shogi.puzzler.maintenance.ShogiOCR.detectDefaultRegions()),
      activeOcrProfile = Some("Default"),
      analysisWorkers = config.getInt("app.analysis.workers"),
      isConfigured = false
    )
  }
}
