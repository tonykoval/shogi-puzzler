package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.bson.{BsonDateTime, Document, ObjectId}
import org.mongodb.scala.model.{Filters, Updates, Sorts, IndexOptions}
import org.mongodb.scala.result.{InsertOneResult, UpdateResult, DeleteResult}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.Date

object SRSRepository {
  private val cardsCollection = MongoDBConnection.srsCardsCollection
  private val attemptsCollection = MongoDBConnection.srsAttemptsCollection
  private val puzzlesCollection = MongoDBConnection.puzzlesCollection
  private val legacyPuzzlesCollection = MongoDBConnection.legacyPuzzlesCollection

  def addCard(email: String, puzzleOid: String, source: String): Future[InsertOneResult] = {
    val now = new Date()
    val doc = Document(
      "user_email" -> email,
      "puzzle_object_id" -> new ObjectId(puzzleOid),
      "puzzle_source" -> source,
      "ease_factor" -> 2.5,
      "interval" -> 1,
      "repetitions" -> 0,
      "next_review" -> new BsonDateTime(now.getTime),
      "total_attempts" -> 0,
      "correct_attempts" -> 0,
      "last_result" -> "",
      "last_reviewed" -> new BsonDateTime(now.getTime),
      "added_at" -> new BsonDateTime(now.getTime)
    )
    cardsCollection.insertOne(doc).toFuture()
  }

  def removeCard(email: String, puzzleOid: String): Future[DeleteResult] = {
    cardsCollection.deleteOne(Filters.and(
      Filters.equal("user_email", email),
      Filters.equal("puzzle_object_id", new ObjectId(puzzleOid))
    )).toFuture()
  }

  def isInDeck(email: String, puzzleOid: String): Future[Boolean] = {
    cardsCollection.countDocuments(Filters.and(
      Filters.equal("user_email", email),
      Filters.equal("puzzle_object_id", new ObjectId(puzzleOid))
    )).toFuture().map(_ > 0)
  }

  def getCardsForPuzzles(email: String, puzzleOids: Seq[String]): Future[Map[String, Boolean]] = {
    if (puzzleOids.isEmpty) return Future.successful(Map.empty)
    val oids = puzzleOids.map(id => new ObjectId(id))
    cardsCollection.find(Filters.and(
      Filters.equal("user_email", email),
      Filters.in("puzzle_object_id", oids: _*)
    )).toFuture().map { docs =>
      docs.map { doc =>
        doc.get("puzzle_object_id").map(_.asObjectId().getValue.toHexString).getOrElse("") -> true
      }.toMap
    }
  }

  def getNextDueCard(email: String): Future[Option[Document]] = {
    val now = new BsonDateTime(System.currentTimeMillis())
    cardsCollection.find(Filters.and(
      Filters.equal("user_email", email),
      Filters.lte("next_review", now)
    )).sort(Sorts.ascending("next_review"))
      .limit(1)
      .toFuture()
      .map(_.headOption)
  }

  def getDueCount(email: String): Future[Long] = {
    val now = new BsonDateTime(System.currentTimeMillis())
    cardsCollection.countDocuments(Filters.and(
      Filters.equal("user_email", email),
      Filters.lte("next_review", now)
    )).toFuture()
  }

  def getNextReviewTime(email: String): Future[Option[Long]] = {
    val now = new BsonDateTime(System.currentTimeMillis())
    cardsCollection.find(Filters.equal("user_email", email))
      .sort(Sorts.ascending("next_review"))
      .limit(1)
      .toFuture()
      .map(_.headOption.flatMap(card => {
        card.get("next_review").map(_.asDateTime().getValue)
      }))
  }

  def getTotalCount(email: String): Future[Long] = {
    cardsCollection.countDocuments(
      Filters.equal("user_email", email)
    ).toFuture()
  }

  def recordReview(email: String, cardId: String, result: String, quality: Int, timeMs: Long): Future[UpdateResult] = {
    val cardOid = new ObjectId(cardId)
    val now = new Date()

    // Record attempt
    val attempt = Document(
      "user_email" -> email,
      "card_id" -> cardOid,
      "result" -> result,
      "quality" -> quality,
      "time_spent_ms" -> timeMs,
      "reviewed_at" -> new BsonDateTime(now.getTime)
    )
    val attemptFuture = attemptsCollection.insertOne(attempt).toFuture()

    // Get current card and apply SM-2
    val cardFuture = cardsCollection.find(Filters.equal("_id", cardOid)).first().toFuture()

    for {
      _ <- attemptFuture
      card <- cardFuture
      result <- {
        val oldEF = card.getDouble("ease_factor")
        val oldInterval = card.getInteger("interval")
        val oldReps = card.getInteger("repetitions")

        val (newEF, newInterval, newReps) = sm2(oldEF, oldInterval, oldReps, quality)
        val nextReview = Instant.now().plusSeconds(newInterval.toLong * 86400)

        val isCorrect = quality >= 3
        val updates = Updates.combine(
          Updates.set("ease_factor", newEF),
          Updates.set("interval", newInterval),
          Updates.set("repetitions", newReps),
          Updates.set("next_review", new BsonDateTime(nextReview.toEpochMilli)),
          Updates.set("last_result", result),
          Updates.set("last_reviewed", new BsonDateTime(now.getTime)),
          Updates.inc("total_attempts", 1),
          if (isCorrect) Updates.inc("correct_attempts", 1) else Updates.inc("correct_attempts", 0)
        )
        cardsCollection.updateOne(Filters.equal("_id", cardOid), updates).toFuture()
      }
    } yield result
  }

  /** SM-2 algorithm */
  private def sm2(easeFactor: Double, interval: Int, repetitions: Int, quality: Int): (Double, Int, Int) = {
    // Update ease factor
    val newEF = math.max(1.3, easeFactor + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02)))

    if (quality >= 3) {
      // Correct response
      val newReps = repetitions + 1
      val newInterval = newReps match {
        case 1 => 1
        case 2 => 6
        case _ => math.round(interval * newEF).toInt
      }
      (newEF, newInterval, newReps)
    } else {
      // Incorrect - reset
      (newEF, 1, 0)
    }
  }

  def getStats(email: String): Future[Map[String, Any]] = {
    for {
      total <- getTotalCount(email)
      due <- getDueCount(email)
      cards <- cardsCollection.find(Filters.equal("user_email", email)).toFuture()
    } yield {
      val totalAttempts = cards.map(c => c.getInteger("total_attempts", 0).toLong).sum
      val correctAttempts = cards.map(c => c.getInteger("correct_attempts", 0).toLong).sum
      val successRate = if (totalAttempts > 0) math.round(correctAttempts.toDouble / totalAttempts * 100) else 0L
      val learned = cards.count(c => c.getInteger("repetitions", 0) >= 3)

      // Current streak: count cards with last_result being a correct answer, from most recent
      val streak = cards.count(c => {
        val lr = c.getString("last_result")
        lr == "best_move" || lr == "second"
      })

      Map(
        "total" -> total,
        "due" -> due,
        "learned" -> learned,
        "success_rate" -> successRate,
        "streak" -> streak,
        "total_attempts" -> totalAttempts,
        "correct_attempts" -> correctAttempts
      )
    }
  }

  def getPuzzleData(puzzleOid: String, source: String): Future[Option[Document]] = {
    val oid = new ObjectId(puzzleOid)
    puzzlesCollection.find(Filters.equal("_id", oid)).first().toFutureOption().flatMap {
      case some @ Some(_) => Future.successful(some)
      case None => legacyPuzzlesCollection.find(Filters.equal("_id", oid)).first().toFutureOption()
    }
  }
}
