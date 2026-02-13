package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.result.{InsertOneResult, UpdateResult}
import scala.concurrent.Future

object GameRepository {
  private val collection = MongoDBConnection.gamesCollection
  private val puzzlesCollection = MongoDBConnection.puzzlesCollection
  private val customPuzzlesCollection = MongoDBConnection.customPuzzlesCollection

  def saveGame(kif: String, gameDetails: Map[String, String]): Future[InsertOneResult] = {
    val doc = Document(
      "kif" -> kif,
      "sente" -> gameDetails.getOrElse("sente", ""),
      "gote" -> gameDetails.getOrElse("gote", ""),
      "date" -> gameDetails.getOrElse("date", ""),
      "site" -> gameDetails.getOrElse("site", ""),
      "kif_hash" -> md5Hash(kif),
      "timestamp" -> System.currentTimeMillis(),
      "is_analyzed" -> false
    )
    collection.insertOne(doc).toFuture()
  }

  def markAsAnalyzed(kifHash: String): Future[UpdateResult] = {
    collection.updateOne(
      org.mongodb.scala.model.Filters.equal("kif_hash", kifHash),
      org.mongodb.scala.model.Updates.set("is_analyzed", true)
    ).toFuture()
  }

  def isAnalyzed(kifHash: String): Future[Boolean] = {
    collection.find(org.mongodb.scala.model.Filters.equal("kif_hash", kifHash))
      .toFuture()
      .map(_.headOption.flatMap(_.get("is_analyzed")).exists(_.asBoolean().getValue))(scala.concurrent.ExecutionContext.global)
  }

  def getAllGames(): Future[Seq[Document]] = {
    collection.find().toFuture()
  }

  def exists(kif: String): Future[Boolean] = {
    collection.find(org.mongodb.scala.model.Filters.equal("kif_hash", md5Hash(kif))).toFuture().map(_.nonEmpty)(scala.concurrent.ExecutionContext.global)
  }

  def findByMetadata(sente: String, gote: String, date: String): Future[Option[Document]] = {
    import java.time.LocalDate
    import java.time.format.DateTimeFormatter
    import scala.util.Try

    val normalizedDate = date.replace("-", "/")

    val dateVariants = if (normalizedDate.length >= 10) {
      val baseDateStr = normalizedDate.substring(0, 10)
      val format = DateTimeFormatter.ofPattern("yyyy/MM/dd")
      Try(LocalDate.parse(baseDateStr, format)).map { d =>
        Seq(
          d.format(format),
          d.plusDays(1).format(format),
          d.minusDays(1).format(format)
        )
      }.getOrElse(Seq(normalizedDate))
    } else {
      Seq(normalizedDate)
    }

    val dateFilters = dateVariants.flatMap { d =>
      Seq(
        org.mongodb.scala.model.Filters.regex("date", s"^${java.util.regex.Pattern.quote(d)}.*"),
        org.mongodb.scala.model.Filters.regex("date", s"^${java.util.regex.Pattern.quote(d.replace("/", "-"))}.*")
      )
    }

    collection.find(org.mongodb.scala.model.Filters.and(
      org.mongodb.scala.model.Filters.equal("sente", sente),
      org.mongodb.scala.model.Filters.equal("gote", gote),
      org.mongodb.scala.model.Filters.or(dateFilters: _*)
    )).toFuture().map(_.headOption)(scala.concurrent.ExecutionContext.global)
  }

  def findByPlayerAndSource(playerName: String, source: String, limit: Int = 100): Future[Seq[Document]] = {
    val siteFilter = if (source == "lishogi") "lishogi" else if (source == "dojo81") "81dojo" else source

    val baseFilter = org.mongodb.scala.model.Filters.or(
      org.mongodb.scala.model.Filters.regex("sente", s".*${java.util.regex.Pattern.quote(playerName)}.*", "i"),
      org.mongodb.scala.model.Filters.regex("gote", s".*${java.util.regex.Pattern.quote(playerName)}.*", "i")
    )

    val filter = if (siteFilter == "shogiwars") {
      org.mongodb.scala.model.Filters.and(
        baseFilter,
        org.mongodb.scala.model.Filters.or(
          org.mongodb.scala.model.Filters.regex("site", s".*$siteFilter.*", "i"),
          org.mongodb.scala.model.Filters.equal("site", ""),
          org.mongodb.scala.model.Filters.exists("site", false)
        )
      )
    } else if (siteFilter == "lishogi") {
      org.mongodb.scala.model.Filters.and(
        baseFilter,
        org.mongodb.scala.model.Filters.or(
          org.mongodb.scala.model.Filters.regex("site", s".*$siteFilter.*", "i"),
          org.mongodb.scala.model.Filters.regex("site", ".*lishogi\\.org.*", "i")
        )
      )
    } else {
      org.mongodb.scala.model.Filters.and(
        baseFilter,
        org.mongodb.scala.model.Filters.regex("site", s".*$siteFilter.*", "i")
      )
    }

    collection.find(filter)
      .sort(org.mongodb.scala.model.Sorts.descending("date", "timestamp"))
      .limit(limit)
      .toFuture()
  }

  def deleteAnalysis(kifHash: String): Future[UpdateResult] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    puzzlesCollection.deleteMany(org.mongodb.scala.model.Filters.equal("game_kif_hash", kifHash)).toFuture()
      .flatMap { _ =>
        customPuzzlesCollection.deleteMany(org.mongodb.scala.model.Filters.equal("game_kif_hash", kifHash)).toFuture()
      }
      .flatMap { _ =>
        collection.updateOne(
          org.mongodb.scala.model.Filters.equal("kif_hash", kifHash),
          org.mongodb.scala.model.Updates.combine(
            org.mongodb.scala.model.Updates.set("is_analyzed", false),
            org.mongodb.scala.model.Updates.unset("scores")
          )
        ).toFuture()
      }
  }

  def deleteAnalysisKeepAccepted(kifHash: String): Future[UpdateResult] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    puzzlesCollection.deleteMany(org.mongodb.scala.model.Filters.equal("game_kif_hash", kifHash)).toFuture()
      .flatMap { _ =>
        customPuzzlesCollection.deleteMany(org.mongodb.scala.model.Filters.and(
          org.mongodb.scala.model.Filters.equal("game_kif_hash", kifHash),
          org.mongodb.scala.model.Filters.equal("status", "review")
        )).toFuture()
      }
      .flatMap { _ =>
        collection.updateOne(
          org.mongodb.scala.model.Filters.equal("kif_hash", kifHash),
          org.mongodb.scala.model.Updates.combine(
            org.mongodb.scala.model.Updates.set("is_analyzed", false),
            org.mongodb.scala.model.Updates.unset("scores")
          )
        ).toFuture()
      }
  }

  def saveScores(kifHash: String, scores: Seq[Int]): Future[UpdateResult] = {
    collection.updateOne(
      org.mongodb.scala.model.Filters.equal("kif_hash", kifHash),
      org.mongodb.scala.model.Updates.set("scores", scores)
    ).toFuture()
  }

  def getGameByKif(kif: String): Future[Option[Document]] = {
    getGameByHash(md5Hash(kif))
  }

  def getGameByHash(kifHash: String): Future[Option[Document]] = {
    collection.find(org.mongodb.scala.model.Filters.equal("kif_hash", kifHash)).toFuture().map(_.headOption)(scala.concurrent.ExecutionContext.global)
  }

  def md5Hash(s: String): String = {
    import java.security.MessageDigest
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(s.getBytes)
    digest.map("%02x".format(_)).mkString
  }
}
