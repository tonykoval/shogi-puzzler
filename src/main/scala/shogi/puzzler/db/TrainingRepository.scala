package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.bson.Document
import org.mongodb.scala.model.Filters._
import scala.concurrent.Future
import java.util.Base64
import org.slf4j.LoggerFactory

case class TrainingPiece(
  id: String,
  label: String, // e.g., "P", "k", "G", etc.
  image_data: String, // base64 encoded image or key
  isGote: Boolean = false,
  verified: Boolean = true,
  timestamp: Long = System.currentTimeMillis()
)

object TrainingRepository {
  private val logger = LoggerFactory.getLogger(getClass)
  private val collection = MongoDBConnection.trainingPiecesCollection
  private val handsCollection = MongoDBConnection.trainingHandsCollection

  def saveTrainingPiece(label: String, image_data: String, isGote: Boolean = false): Future[Option[String]] = {
    implicit val ec = scala.concurrent.ExecutionContext.global
    
    // Check for existing image_data
    collection.find(equal("image_data", image_data)).headOption().flatMap {
      case Some(existing) =>
        logger.info(s"Training piece with same image already exists, skipping. ID: ${existing.getObjectId("_id")}")
        Future.successful(Some(existing.getObjectId("_id").toString))
      case None =>
        val doc = Document(
          "label" -> label,
          "image_data" -> image_data,
          "is_gote" -> isGote,
          "verified" -> true,
          "timestamp" -> System.currentTimeMillis()
        )
        
        collection.insertOne(doc).toFuture().map(result => 
          Option(result.getInsertedId).map(_.asObjectId().getValue.toString)
        ).recover {
          case e: Exception =>
            logger.error(s"Failed to save training piece: ${e.getMessage}")
            None
        }
    }
  }

  def saveTrainingHand(label: String, count: Int, image_data: String, isGote: Boolean = false): Future[Option[String]] = {
    implicit val ec = scala.concurrent.ExecutionContext.global

    // Check for existing image_data
    handsCollection.find(equal("image_data", image_data)).headOption().flatMap {
      case Some(existing) =>
        logger.info(s"Training hand with same image already exists, skipping. ID: ${existing.getObjectId("_id")}")
        Future.successful(Some(existing.getObjectId("_id").toString))
      case None =>
        val doc = Document(
          "label" -> label,
          "count" -> count,
          "image_data" -> image_data,
          "is_gote" -> isGote,
          "verified" -> true,
          "timestamp" -> System.currentTimeMillis()
        )
        
        handsCollection.insertOne(doc).toFuture().map(result => 
          Option(result.getInsertedId).map(_.asObjectId().getValue.toString)
        ).recover {
          case e: Exception =>
            logger.error(s"Failed to save training hand: ${e.getMessage}")
            None
        }
    }
  }

  def countPieces(): Future[Long] = {
    collection.countDocuments().toFuture()
  }

  def getAllPieces(): Future[Seq[TrainingPiece]] = {
    collection.find().toFuture().map { docs =>
      docs.map { doc =>
        TrainingPiece(
          id = doc.getObjectId("_id").toString,
          label = doc.getString("label"),
          image_data = doc.getString("image_data"),
          isGote = doc.get("is_gote").map(_.asBoolean().getValue).getOrElse(false),
          verified = doc.getBoolean("verified"),
          timestamp = doc.getLong("timestamp")
        )
      }
    }(scala.concurrent.ExecutionContext.global)
  }
}
