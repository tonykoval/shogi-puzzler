package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.model.Filters
import org.mongodb.scala.model.ReplaceOptions
import scala.concurrent.Future
import com.typesafe.config.ConfigFactory

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

  // Helper to get all relevant app settings with defaults from config
  def getAppSettings(userEmail: Option[String] = None): Future[AppSettings] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    def fetchFromIndividualKeys(): Future[AppSettings] = {
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
      } yield AppSettings(lishogiNick, swarsNick, dojoNick, engine, shallow, deep, threshold, isConfigured = false)
    }

    def fetchFromDoc(doc: Document): AppSettings = {
      val threshold: Double = try {
        doc.getDouble("win_chance_drop_threshold")
      } catch {
        case _: Exception =>
          try { doc.getInteger("win_chance_drop_threshold").toDouble }
          catch { case _: Exception => config.getDouble("app.analysis.win-chance-drop-threshold") }
      }

      AppSettings(
        lishogiNickname = Option(doc.getString("lishogi_nickname")).getOrElse(config.getString("app.fetcher.lishogi.nickname")),
        shogiwarsNickname = Option(doc.getString("shogiwars_nickname")).getOrElse(config.getString("app.fetcher.shogiwars.nickname")),
        dojo81Nickname = Option(doc.getString("dojo81_nickname")).getOrElse(config.getString("app.fetcher.dojo81.nickname")),
        enginePath = Option(doc.getString("engine_path")).getOrElse(config.getString("app.engine.path")),
        shallowLimit = Option(doc.getInteger("shallow_limit")).map(_.toInt).getOrElse(config.getInt("app.analysis.shallow-limit")),
        deepLimit = Option(doc.getInteger("deep_limit")).map(_.toInt).getOrElse(config.getInt("app.analysis.deep-limit")),
        winChanceDropThreshold = threshold,
        isConfigured = true
      )
    }

    val id = userEmail.getOrElse("global")
    collection.find(Filters.equal("_id", id)).toFuture().flatMap { docs =>
      if (docs.nonEmpty) {
        Future.successful(fetchFromDoc(docs.head))
      } else if (userEmail.isDefined && id != "global") {
        // Fallback to "global" doc
        collection.find(Filters.equal("_id", "global")).toFuture().flatMap { globalDocs =>
          if (globalDocs.nonEmpty) {
            val globalSettings = fetchFromDoc(globalDocs.head)
            // Even if we found global settings, they were not specifically configured for THIS user,
            // so if this is a new user, we might still want to treat them as not configured?
            // Actually, the requirement says "first time login (there is no config in DB), reroute to config page".
            // If there's a global config but no user-specific config, it IS their first time.
            Future.successful(globalSettings.copy(isConfigured = false))
          } else {
            fetchFromIndividualKeys()
          }
        }
      } else {
        fetchFromIndividualKeys()
      }
    }
  }

  def saveAppSettings(userEmail: String, settings: AppSettings): Future[org.mongodb.scala.result.UpdateResult] = {
    val doc = Document(
      "_id" -> userEmail,
      "lishogi_nickname" -> settings.lishogiNickname,
      "shogiwars_nickname" -> settings.shogiwarsNickname,
      "dojo81_nickname" -> settings.dojo81Nickname,
      "engine_path" -> settings.enginePath,
      "shallow_limit" -> settings.shallowLimit,
      "deep_limit" -> settings.deepLimit,
      "win_chance_drop_threshold" -> settings.winChanceDropThreshold
    )
    collection.replaceOne(Filters.equal("_id", userEmail), doc, ReplaceOptions().upsert(true)).toFuture()
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
  isConfigured: Boolean = false
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
      isConfigured = false
    )
  }
}
