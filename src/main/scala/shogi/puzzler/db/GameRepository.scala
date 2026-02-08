package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.result.{InsertOneResult, UpdateResult}
import shogi.puzzler.domain.ParsedGame
import scala.concurrent.Future

trait GameRepositoryTrait {
  def saveGame(kif: String, gameDetails: Map[String, String]): Future[InsertOneResult]
  def markAsAnalyzed(kifHash: String): Future[UpdateResult]
  def isAnalyzed(kifHash: String): Future[Boolean]
  def savePuzzle(json: String, gameKifHash: String): Future[InsertOneResult]
  def getAllPuzzles(): Future[Seq[Document]]
  def getPuzzlesForGame(gameKifHash: String): Future[Seq[Document]]
  def getPublicPuzzles(): Future[Seq[Document]]
  def togglePuzzlePublic(puzzleId: String, isPublic: Boolean): Future[UpdateResult]
  def getAllGames(): Future[Seq[Document]]
  def exists(kif: String): Future[Boolean]
  def findByMetadata(sente: String, gote: String, date: String): Future[Option[Document]]
  def findByPlayerAndSource(playerName: String, source: String, limit: Int = 100): Future[Seq[Document]]
  def countPuzzlesForGame(gameKifHash: String): Future[Long]
  def deleteAnalysis(kifHash: String): Future[UpdateResult]
  def saveScores(kifHash: String, scores: Seq[Int]): Future[UpdateResult]
  def getGameByKif(kif: String): Future[Option[Document]]
  def getGameByHash(kifHash: String): Future[Option[Document]]
  def saveCustomPuzzle(name: String, sfen: String, userEmail: String, isPublic: Boolean = false, comments: Option[String] = None, selectedSequence: Option[Seq[String]] = None, moveComments: Option[Map[String, String]] = None): Future[InsertOneResult]
  def getCustomPuzzles(userEmail: String): Future[Seq[Document]]
  def getCustomPuzzle(id: String, userEmail: String): Future[Option[Document]]
  def updateCustomPuzzle(id: String, name: String, sfen: String, userEmail: String, isPublic: Boolean = false, comments: Option[String] = None, selectedSequence: Option[Seq[String]] = None, moveComments: Option[Map[String, String]] = None): Future[UpdateResult]
  def deleteCustomPuzzle(id: String, userEmail: String): Future[org.mongodb.scala.result.DeleteResult]
}

object GameRepository extends GameRepositoryTrait {
  private val collection = MongoDBConnection.gamesCollection
  private val puzzlesCollection = MongoDBConnection.puzzlesCollection
  private val customPuzzlesCollection = MongoDBConnection.customPuzzlesCollection

  def saveGame(kif: String, gameDetails: Map[String, String]): Future[org.mongodb.scala.result.InsertOneResult] = {
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

  def markAsAnalyzed(kifHash: String): Future[org.mongodb.scala.result.UpdateResult] = {
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

  def savePuzzle(json: String, gameKifHash: String): Future[org.mongodb.scala.result.InsertOneResult] = {
    val doc = Document(org.bson.BsonDocument.parse(json)) ++ Document("game_kif_hash" -> gameKifHash)
    puzzlesCollection.insertOne(doc).toFuture()
  }

  def getAllPuzzles(): Future[Seq[Document]] = {
    puzzlesCollection.find().toFuture()
  }

  def getPuzzlesForGame(gameKifHash: String): Future[Seq[Document]] = {
    puzzlesCollection.find(org.mongodb.scala.model.Filters.equal("game_kif_hash", gameKifHash)).toFuture()
  }

  def getPublicPuzzles(): Future[Seq[Document]] = {
    puzzlesCollection.find(org.mongodb.scala.model.Filters.equal("is_public", true)).toFuture()
  }

  def togglePuzzlePublic(puzzleId: String, isPublic: Boolean): Future[org.mongodb.scala.result.UpdateResult] = {
    puzzlesCollection.updateOne(
      org.mongodb.scala.model.Filters.equal("_id", new org.bson.types.ObjectId(puzzleId)),
      org.mongodb.scala.model.Updates.set("is_public", isPublic)
    ).toFuture()
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

  def countPuzzlesForGame(gameKifHash: String): Future[Long] = {
    puzzlesCollection.countDocuments(org.mongodb.scala.model.Filters.equal("game_kif_hash", gameKifHash)).toFuture()
  }

  def deleteAnalysis(kifHash: String): Future[org.mongodb.scala.result.UpdateResult] = {
    // 1. Delete puzzles
    puzzlesCollection.deleteMany(org.mongodb.scala.model.Filters.equal("game_kif_hash", kifHash)).toFuture()
      .flatMap { _ =>
        // 2. Reset is_analyzed and scores
        collection.updateOne(
          org.mongodb.scala.model.Filters.equal("kif_hash", kifHash),
          org.mongodb.scala.model.Updates.combine(
            org.mongodb.scala.model.Updates.set("is_analyzed", false),
            org.mongodb.scala.model.Updates.unset("scores")
          )
        ).toFuture()
      }(scala.concurrent.ExecutionContext.global)
  }

  def saveScores(kifHash: String, scores: Seq[Int]): Future[org.mongodb.scala.result.UpdateResult] = {
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

  // Custom puzzle methods
  def saveCustomPuzzle(name: String, sfen: String, userEmail: String, isPublic: Boolean = false, comments: Option[String] = None, selectedSequence: Option[Seq[String]] = None, moveComments: Option[Map[String, String]] = None): Future[InsertOneResult] = {
    import org.mongodb.scala.bson.BsonString
    import org.mongodb.scala.bson.collection.immutable.Document
    
    // Convert Map[String, String] to Document
    val moveCommentsDoc = Document(moveComments.getOrElse(Map.empty).map { case (k, v) => k -> BsonString(v) })
    
    val doc = Document(
      "name" -> name,
      "sfen" -> sfen,
      "user_email" -> userEmail,
      "is_public" -> isPublic,
      "comments" -> comments.getOrElse(""),
      "selected_sequence" -> selectedSequence.getOrElse(Seq.empty),
      "move_comments" -> moveCommentsDoc,
      "created_at" -> System.currentTimeMillis(),
      "updated_at" -> System.currentTimeMillis()
    )
    customPuzzlesCollection.insertOne(doc).toFuture()
  }

  def getCustomPuzzles(userEmail: String): Future[Seq[Document]] = {
    customPuzzlesCollection
      .find(org.mongodb.scala.model.Filters.equal("user_email", userEmail))
      .sort(org.mongodb.scala.model.Sorts.descending("created_at"))
      .toFuture()
  }

  def getCustomPuzzle(id: String, userEmail: String): Future[Option[Document]] = {
    customPuzzlesCollection
      .find(org.mongodb.scala.model.Filters.and(
        org.mongodb.scala.model.Filters.equal("_id", new org.bson.types.ObjectId(id)),
        org.mongodb.scala.model.Filters.equal("user_email", userEmail)
      ))
      .first()
      .toFutureOption()
  }

  def updateCustomPuzzle(id: String, name: String, sfen: String, userEmail: String, isPublic: Boolean = false, comments: Option[String] = None, selectedSequence: Option[Seq[String]] = None, moveComments: Option[Map[String, String]] = None): Future[UpdateResult] = {
    import org.mongodb.scala.bson.BsonString
    import org.mongodb.scala.bson.collection.immutable.Document
    
    val updates = scala.collection.mutable.ListBuffer[org.bson.conversions.Bson]()
    
    // Convert Map[String, String] to Document
    val moveCommentsDoc = Document(moveComments.getOrElse(Map.empty).map { case (k, v) => k -> BsonString(v) })

    updates += org.mongodb.scala.model.Updates.set("name", name)
    updates += org.mongodb.scala.model.Updates.set("sfen", sfen)
    updates += org.mongodb.scala.model.Updates.set("is_public", isPublic)
    updates += org.mongodb.scala.model.Updates.set("comments", comments.getOrElse(""))
    updates += org.mongodb.scala.model.Updates.set("selected_sequence", selectedSequence.getOrElse(Seq.empty))
    updates += org.mongodb.scala.model.Updates.set("move_comments", moveCommentsDoc)
    updates += org.mongodb.scala.model.Updates.set("updated_at", System.currentTimeMillis())
    
    customPuzzlesCollection.updateOne(
      org.mongodb.scala.model.Filters.and(
        org.mongodb.scala.model.Filters.equal("_id", new org.bson.types.ObjectId(id)),
        org.mongodb.scala.model.Filters.equal("user_email", userEmail)
      ),
      org.mongodb.scala.model.Updates.combine(updates.toList: _*)
    ).toFuture()
  }

  def deleteCustomPuzzle(id: String, userEmail: String): Future[org.mongodb.scala.result.DeleteResult] = {
    customPuzzlesCollection.deleteOne(
      org.mongodb.scala.model.Filters.and(
        org.mongodb.scala.model.Filters.equal("_id", new org.bson.types.ObjectId(id)),
        org.mongodb.scala.model.Filters.equal("user_email", userEmail)
      )
    ).toFuture()
  }
}
