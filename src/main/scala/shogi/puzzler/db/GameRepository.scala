package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.result.{InsertOneResult, UpdateResult}
import scala.concurrent.Future

object GameRepository {
  private val collection = MongoDBConnection.gamesCollection
  private val puzzlesCollection = MongoDBConnection.puzzlesCollection

  def saveGame(kif: String, gameDetails: Map[String, String]): Future[InsertOneResult] = {
    val doc = Document(
      "kif" -> kif,
      "sente" -> gameDetails.getOrElse("sente", ""),
      "gote" -> gameDetails.getOrElse("gote", ""),
      "date" -> gameDetails.getOrElse("date", "").replace("/", "-"),
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

  /** One-time migration: replaces '/' with '-' in all date fields (idempotent). */
  def normalizeDates(): Future[Int] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    collection.find(
      org.mongodb.scala.model.Filters.regex("date", "/")
    ).toFuture().flatMap { docs =>
      Future.sequence(docs.map { doc =>
        val kifHash = doc.get("kif_hash").map(_.asString().getValue).getOrElse("")
        val date    = doc.get("date").map(_.asString().getValue).getOrElse("").replace("/", "-")
        if (kifHash.nonEmpty)
          collection.updateOne(
            org.mongodb.scala.model.Filters.equal("kif_hash", kifHash),
            org.mongodb.scala.model.Updates.set("date", date)
          ).toFuture().map(_ => 1)
        else
          Future.successful(0)
      })
    }.map(_.sum)
  }

  def getAllGames(): Future[Seq[Document]] = {
    collection.find().toFuture()
  }

  def getGamesPaged(
      page: Int,
      pageSize: Int,
      source: String = "all",
      status: String = "all",
      playerSearch: String = "",
      myNicknames: Seq[String] = Seq.empty,
      sortDir: String = "desc"
  ): Future[(Seq[Document], Long)] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    import java.util.regex.Pattern

    val filterList = scala.collection.mutable.ListBuffer[org.bson.conversions.Bson]()

    source match {
      case "lishogi" =>
        filterList += org.mongodb.scala.model.Filters.regex("site", ".*lishogi.*", "i")
      case "shogiwars" =>
        filterList += org.mongodb.scala.model.Filters.or(
          org.mongodb.scala.model.Filters.regex("site", ".*wars.*", "i"),
          org.mongodb.scala.model.Filters.equal("site", ""),
          org.mongodb.scala.model.Filters.exists("site", false)
        )
      case "dojo81" =>
        filterList += org.mongodb.scala.model.Filters.or(
          org.mongodb.scala.model.Filters.regex("site", ".*81dojo.*", "i"),
          org.mongodb.scala.model.Filters.regex("site", ".*dojo81.*", "i")
        )
      case _ => // all — no filter
    }

    status match {
      case "analyzed" =>
        filterList += org.mongodb.scala.model.Filters.equal("is_analyzed", true)
      case "pending" =>
        filterList += org.mongodb.scala.model.Filters.not(
          org.mongodb.scala.model.Filters.equal("is_analyzed", true)
        )
      case _ => // all
    }

    val trimmed = playerSearch.trim
    if (trimmed.nonEmpty) {
      val escaped = Pattern.quote(trimmed)
      filterList += org.mongodb.scala.model.Filters.or(
        org.mongodb.scala.model.Filters.regex("sente", s".*$escaped.*", "i"),
        org.mongodb.scala.model.Filters.regex("gote",  s".*$escaped.*", "i")
      )
    }

    if (myNicknames.nonEmpty) {
      val nicknameFilters = myNicknames.flatMap { n =>
        val e = Pattern.quote(n)
        Seq(
          org.mongodb.scala.model.Filters.regex("sente", s".*$e.*", "i"),
          org.mongodb.scala.model.Filters.regex("gote",  s".*$e.*", "i")
        )
      }
      filterList += org.mongodb.scala.model.Filters.or(nicknameFilters: _*)
    }

    val combinedFilter = filterList.toSeq match {
      case Seq()  => new org.bson.Document()
      case Seq(f) => f
      case fs     => org.mongodb.scala.model.Filters.and(fs: _*)
    }

    val sort = if (sortDir == "asc")
      org.mongodb.scala.model.Sorts.ascending("date", "timestamp")
    else
      org.mongodb.scala.model.Sorts.descending("date", "timestamp")
    val skip = (page - 1) * pageSize

    val totalFut = collection.countDocuments(combinedFilter).toFuture()
    val docsFut  = collection.find(combinedFilter).sort(sort).skip(skip).limit(pageSize).toFuture()

    for {
      total <- totalFut
      docs  <- docsFut
    } yield (docs, total)
  }

  def countGames(): Future[Long] =
    collection.countDocuments().toFuture()

  def countAnalyzedGames(): Future[Long] =
    collection.countDocuments(org.mongodb.scala.model.Filters.equal("is_analyzed", true)).toFuture()

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
    puzzlesCollection.deleteMany(org.mongodb.scala.model.Filters.and(
        org.mongodb.scala.model.Filters.equal("game_kif_hash", kifHash),
        org.mongodb.scala.model.Filters.equal("status", "review")
      )).toFuture()
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

  def saveMoveComment(kifHash: String, moveIdx: Int, comment: String): Future[UpdateResult] = {
    if (comment.trim.isEmpty)
      collection.updateOne(
        org.mongodb.scala.model.Filters.equal("kif_hash", kifHash),
        org.mongodb.scala.model.Updates.unset(s"move_comments.$moveIdx")
      ).toFuture()
    else
      collection.updateOne(
        org.mongodb.scala.model.Filters.equal("kif_hash", kifHash),
        org.mongodb.scala.model.Updates.set(s"move_comments.$moveIdx", comment.trim)
      ).toFuture()
  }

  def saveMoveAnalysis(kifHash: String, moveIdx: Int, candidatesJson: String): Future[UpdateResult] = {
    collection.updateOne(
      org.mongodb.scala.model.Filters.equal("kif_hash", kifHash),
      org.mongodb.scala.model.Updates.set(s"move_analysis.$moveIdx", candidatesJson)
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
