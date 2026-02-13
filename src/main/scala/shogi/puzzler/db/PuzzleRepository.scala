package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.model.{Filters, Updates}
import org.mongodb.scala.result.{InsertOneResult, UpdateResult}
import scala.concurrent.Future

object PuzzleRepository {
  private val puzzlesCollection = MongoDBConnection.puzzlesCollection
  private val customPuzzlesCollection = MongoDBConnection.customPuzzlesCollection

  def savePuzzle(json: String, gameKifHash: String, userEmail: Option[String] = None): Future[InsertOneResult] = {
    val baseDoc = Document(org.bson.BsonDocument.parse(json)) ++ Document("game_kif_hash" -> gameKifHash)
    val doc = userEmail match {
      case Some(email) => baseDoc ++ Document("user_email" -> email)
      case None => baseDoc
    }
    puzzlesCollection.insertOne(doc).toFuture()
  }

  def getAllPuzzles(): Future[Seq[Document]] = {
    puzzlesCollection.find().toFuture()
  }

  def getPuzzlesForUser(userEmail: String): Future[Seq[Document]] = {
    puzzlesCollection.find(
      org.mongodb.scala.model.Filters.or(
        org.mongodb.scala.model.Filters.equal("user_email", userEmail),
        org.mongodb.scala.model.Filters.exists("user_email", false)
      )
    ).toFuture()
  }

  def getPuzzlesForGame(gameKifHash: String): Future[Seq[Document]] = {
    puzzlesCollection.find(org.mongodb.scala.model.Filters.equal("game_kif_hash", gameKifHash))
      .sort(org.mongodb.scala.model.Sorts.ascending("move_number"))
      .toFuture()
  }

  def getPublicPuzzles(): Future[Seq[Document]] = {
    puzzlesCollection.find(
      org.mongodb.scala.model.Filters.or(
        org.mongodb.scala.model.Filters.equal("is_public", true),
        org.mongodb.scala.model.Filters.exists("is_public", false)
      )
    ).toFuture()
  }

  def togglePuzzlePublic(puzzleId: String, isPublic: Boolean): Future[UpdateResult] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val objectId = new org.bson.types.ObjectId(puzzleId)
    val filter = org.mongodb.scala.model.Filters.equal("_id", objectId)
    val update = org.mongodb.scala.model.Updates.set("is_public", isPublic)
    puzzlesCollection.updateOne(filter, update).toFuture().flatMap { result =>
      if (result.getMatchedCount > 0) Future.successful(result)
      else customPuzzlesCollection.updateOne(filter, update).toFuture()
    }
  }

  def countPuzzlesForGame(gameKifHash: String): Future[Long] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    for {
      regularCount <- puzzlesCollection.countDocuments(org.mongodb.scala.model.Filters.equal("game_kif_hash", gameKifHash)).toFuture()
      customCount <- customPuzzlesCollection.countDocuments(org.mongodb.scala.model.Filters.equal("game_kif_hash", gameKifHash)).toFuture()
    } yield regularCount + customCount
  }

  private def sanitizeEmail(email: String): String =
    email.replace(".", "_").replace("@", "_at_")

  def incrementPlayCount(puzzleId: String): Future[UpdateResult] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val objectId = new org.bson.types.ObjectId(puzzleId)
    val filter = Filters.equal("_id", objectId)
    val update = Updates.inc("play_count", 1)
    puzzlesCollection.updateOne(filter, update).toFuture().flatMap { result =>
      if (result.getMatchedCount > 0) Future.successful(result)
      else customPuzzlesCollection.updateOne(filter, update).toFuture()
    }
  }

  def ratePuzzle(puzzleId: String, userEmail: String, stars: Int): Future[UpdateResult] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val objectId = new org.bson.types.ObjectId(puzzleId)
    val filter = Filters.equal("_id", objectId)
    val key = s"ratings.${sanitizeEmail(userEmail)}"
    val update = Updates.set(key, stars)

    def updateAndRecompute(collection: org.mongodb.scala.MongoCollection[Document]): Future[UpdateResult] = {
      for {
        setResult <- collection.updateOne(filter, update).toFuture()
        if setResult.getMatchedCount > 0
        docOpt <- collection.find(filter).first().toFutureOption()
        avgResult <- docOpt match {
          case Some(doc) =>
            val ratings = doc.get("ratings") match {
              case Some(v) if v.isDocument =>
                import scala.collection.JavaConverters._
                v.asDocument().entrySet().asScala.map { e =>
                  e.getValue match {
                    case n: org.bson.BsonInt32 => n.getValue.toDouble
                    case n: org.bson.BsonInt64 => n.getValue.toDouble
                    case n: org.bson.BsonDouble => n.getValue
                    case _ => 0.0
                  }
                }.toSeq
              case _ => Seq.empty
            }
            val avg = if (ratings.nonEmpty) ratings.sum / ratings.size else 0.0
            collection.updateOne(filter, Updates.set("avg_rating", avg)).toFuture()
          case None => Future.successful(setResult)
        }
      } yield avgResult
    }

    updateAndRecompute(puzzlesCollection).recoverWith {
      case _: NoSuchElementException => updateAndRecompute(customPuzzlesCollection)
    }
  }

  def getPuzzleStatsForGame(kifHash: String): Future[Map[String, Long]] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    for {
      regularCount <- puzzlesCollection.countDocuments(org.mongodb.scala.model.Filters.equal("game_kif_hash", kifHash)).toFuture()
      acceptedCount <- customPuzzlesCollection.countDocuments(org.mongodb.scala.model.Filters.and(
        org.mongodb.scala.model.Filters.equal("game_kif_hash", kifHash),
        org.mongodb.scala.model.Filters.equal("status", "accepted")
      )).toFuture()
      reviewCount <- customPuzzlesCollection.countDocuments(org.mongodb.scala.model.Filters.and(
        org.mongodb.scala.model.Filters.equal("game_kif_hash", kifHash),
        org.mongodb.scala.model.Filters.equal("status", "review")
      )).toFuture()
    } yield {
      val total = regularCount + acceptedCount + reviewCount
      Map("regular" -> regularCount, "accepted" -> acceptedCount, "review" -> reviewCount, "total" -> total)
    }
  }
}
