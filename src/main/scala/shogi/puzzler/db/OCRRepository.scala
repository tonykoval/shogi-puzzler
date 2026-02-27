package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.model.Filters
import org.mongodb.scala.model.ReplaceOptions
import org.mongodb.scala.model.Sorts
import org.mongodb.scala.model.Updates
import org.mongodb.scala.bson.Document
import scala.concurrent.Future
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

import shogi.puzzler.maintenance.Rect
import scala.jdk.CollectionConverters._

case class OCRHistoryEntry(
  id: String,
  userEmail: String,
  image_data: String,
  boardRect: Map[String, Int],
  senteRect: Option[Map[String, Int]],
  goteRect: Option[Map[String, Int]],
  sfen: String,
  manualAdjustments: Map[String, String],
  comment: String,
  timestamp: Long,
  pieceReferences: Map[String, String] = Map.empty
)

object OCRRepository {
  private val collection = MongoDBConnection.ocrHistoryCollection

  def getOCRHistory(userEmail: String): Future[Seq[OCRHistoryEntry]] = {
    collection.find(Filters.equal("userEmail", userEmail))
      .sort(Sorts.descending("timestamp"))
      .toFuture()
      .map(_.map(docToEntry))
  }

  def getOCRById(id: String): Future[Option[OCRHistoryEntry]] = {
    collection.find(Filters.equal("_id", id)).toFuture().map(_.headOption.map(docToEntry))
  }

  def saveOCRHistory(
    userEmail: String,
    image_data: String,
    boardRect: Map[String, Int],
    senteRect: Option[Map[String, Int]],
    goteRect: Option[Map[String, Int]],
    sfen: String,
    manualAdjustments: Map[String, String],
    comment: String,
    id: Option[String],
    pieceReferences: Map[String, String] = Map.empty
  ): Future[Option[String]] = {
    val finalId = id.getOrElse(UUID.randomUUID().toString)
    val entry = OCRHistoryEntry(
      finalId,
      userEmail,
      image_data,
      boardRect,
      senteRect,
      goteRect,
      sfen,
      manualAdjustments,
      comment,
      System.currentTimeMillis(),
      pieceReferences
    )

    collection.replaceOne(
      Filters.equal("_id", finalId),
      entryToDoc(entry),
      ReplaceOptions().upsert(true)
    ).toFuture().map(result => if (result.wasAcknowledged()) Some(finalId) else None)
  }

  def deleteOCR(id: String, userEmail: String): Future[Boolean] = {
    collection.deleteOne(Filters.and(
      Filters.equal("_id", id),
      Filters.equal("userEmail", userEmail)
    )).toFuture().map(_.getDeletedCount > 0)
  }

  def updateAllHistoryRegions(
    boardRect: Map[String, Int],
    senteRect: Option[Map[String, Int]],
    goteRect: Option[Map[String, Int]]
  ): Future[Long] = {
    val updates = Updates.combine(
      Updates.set("boardRect", mapToDoc(boardRect)),
      Updates.set("senteRect", senteRect.map(mapToDoc).getOrElse(Document())),
      Updates.set("goteRect", goteRect.map(mapToDoc).getOrElse(Document()))
    )
    collection.updateMany(Filters.empty(), updates).toFuture().map(_.getModifiedCount)
  }

  private def docToEntry(doc: Document): OCRHistoryEntry = {
    OCRHistoryEntry(
      id = doc.getString("_id"),
      userEmail = doc.getString("userEmail"),
      image_data = if (doc.containsKey("image_data")) doc.getString("image_data") else doc.getString("imageData"),
      boardRect = docToMap(doc.get("boardRect").get.asDocument()),
      senteRect = if (doc.containsKey("senteRect")) Some(docToMap(doc.get("senteRect").get.asDocument())) else None,
      goteRect = if (doc.containsKey("goteRect")) Some(docToMap(doc.get("goteRect").get.asDocument())) else None,
      sfen = doc.getString("sfen"),
      manualAdjustments = docToMapStr(doc.get("manualAdjustments").get.asDocument()),
      comment = doc.getString("comment"),
      timestamp = doc.getLong("timestamp"),
      pieceReferences = if (doc.containsKey("pieceReferences")) docToMapStr(doc.get("pieceReferences").get.asDocument()) else Map.empty
    )
  }

  private def entryToDoc(entry: OCRHistoryEntry): Document = {
    Document(
      "_id" -> entry.id,
      "userEmail" -> entry.userEmail,
      "image_data" -> entry.image_data,
      "boardRect" -> mapToDoc(entry.boardRect),
      "senteRect" -> entry.senteRect.map(mapToDoc).getOrElse(Document()),
      "goteRect" -> entry.goteRect.map(mapToDoc).getOrElse(Document()),
      "sfen" -> entry.sfen,
      "manualAdjustments" -> mapToDocStr(entry.manualAdjustments),
      "comment" -> entry.comment,
      "timestamp" -> entry.timestamp,
      "pieceReferences" -> mapToDocStr(entry.pieceReferences)
    )
  }

  private def docToMap(doc: org.mongodb.scala.bson.BsonDocument): Map[String, Int] = {
    doc.keySet().asScala.map(k => k -> doc.getInt32(k).getValue).toMap
  }

  private def docToMapStr(doc: org.mongodb.scala.bson.BsonDocument): Map[String, String] = {
    doc.keySet().asScala.map(k => k -> doc.getString(k).getValue).toMap
  }

  private def mapToDoc(map: Map[String, Int]): Document = {
    Document(map)
  }

  private def mapToDocStr(map: Map[String, String]): Document = {
    Document(map)
  }
}
