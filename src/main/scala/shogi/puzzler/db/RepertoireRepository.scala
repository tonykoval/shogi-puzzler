package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates._
import org.mongodb.scala.model.Filters.exists
import org.mongodb.scala.model.Projections._
import org.mongodb.scala.bson.BsonArray
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import org.bson.types.ObjectId
import scala.jdk.CollectionConverters._
import org.slf4j.LoggerFactory

object RepertoireRepository {
  private val logger = LoggerFactory.getLogger(getClass)
  private val collection = MongoDBConnection.repertoireCollection

  private def sanitizeNodeKey(sfen: String): String =
    sfen.replace(".", "_").replace("/", "-").replace(" ", "_")

  def getRepertoires(ownerEmail: Option[String]): Future[Seq[Document]] = {
    val filter = ownerEmail match {
      case Some(email) => equal("ownerEmail", email)
      case None => or(equal("ownerEmail", null), equal("ownerEmail", ""), exists("ownerEmail", false))
    }
    logger.debug(s"Fetching repertoires for ownerEmail: $ownerEmail")
    collection.find(filter).toFuture()
  }

  def getRepertoire(id: String): Future[Option[Document]] = {
    collection.find(equal("_id", new ObjectId(id))).headOption()
  }

  def createRepertoire(name: String, ownerEmail: Option[String], isAutoReload: Boolean = false, reloadThreshold: Int = 200, reloadColor: Option[String] = None, rootSfen: Option[String] = None): Future[String] = {
    logger.info(s"Creating repertoire: name=$name, ownerEmail=$ownerEmail, isAutoReload=$isAutoReload, reloadThreshold=$reloadThreshold, reloadColor=$reloadColor")
    val sfen = rootSfen.getOrElse(
      scala.util.Try(shogi.variant.Standard.initialSfen.value).getOrElse("lnsgkgsnl/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL b - 1")
    )
    val doc = Document(
      "name" -> name,
      "rootSfen" -> sfen,
      "isAutoReload" -> isAutoReload,
      "reloadThreshold" -> reloadThreshold,
      "nodes" -> Document()
    ) ++ ownerEmail.map(email => Document("ownerEmail" -> email)).getOrElse(Document()) ++ 
      reloadColor.map(color => Document("reloadColor" -> color)).getOrElse(Document())
    collection.insertOne(doc).toFuture().map { res => 
      val id = res.getInsertedId.asObjectId().getValue.toString
      logger.info(s"Inserted repertoire with id: $id")
      id
    }.recover {
      case e: Exception =>
        logger.error(s"Error inserting repertoire: ${e.getMessage}", e)
        throw e
    }
  }

  def addMove(repertoireId: String, parentSfen: String, usi: String, nextSfen: String, comment: Option[String] = None, isPuzzle: Option[Boolean] = None): Future[Unit] = {
    val nodeKey = sanitizeNodeKey(parentSfen)
    logger.debug(s"Adding move to repertoire $repertoireId: $usi (nodeKey: $nodeKey)")
    
    getRepertoire(repertoireId).flatMap {
      case Some(doc) =>
        val nodes = if (doc.containsKey("nodes")) doc.get("nodes").getOrElse(new org.bson.BsonDocument()).asDocument() else new org.bson.BsonDocument()
        
        val nodeData = if (nodes.containsKey(nodeKey)) {
          nodes.get(nodeKey).asDocument()
        } else {
          new org.bson.BsonDocument()
            .append("sfen", new org.bson.BsonString(parentSfen))
            .append("moves", new org.bson.BsonArray())
        }
        
        val moves = nodeData.getArray("moves")
        val moveExists = moves.asScala.exists { m =>
          m.isDocument && m.asDocument().getString("usi").getValue == usi
        }
        
        if (!moveExists) {
          val moveDoc = new org.bson.BsonDocument()
            .append("usi", new org.bson.BsonString(usi))
            .append("nextSfen", new org.bson.BsonString(nextSfen))
            .append("isMain", new org.bson.BsonBoolean(true))

          comment.foreach(c => moveDoc.append("comment", new org.bson.BsonString(c)))
          isPuzzle.foreach(p => moveDoc.append("isPuzzle", new org.bson.BsonBoolean(p)))

          moves.add(moveDoc)
          
          logger.debug(s"Updating repertoire $repertoireId with new move $usi")
          collection.updateOne(
            equal("_id", new ObjectId(repertoireId)),
            set(s"nodes.$nodeKey", nodeData)
          ).toFuture().map(_ => ())
        } else {
          logger.debug(s"Move $usi already exists in node $nodeKey")
          Future.successful(())
        }
      case None =>
        logger.warn(s"Repertoire $repertoireId not found")
        Future.failed(new Exception("Repertoire not found"))
    }
  }

  def updateMove(repertoireId: String, parentSfen: String, usi: String, comment: Option[String], isPuzzle: Option[Boolean]): Future[Unit] = {
    val nodeKey = sanitizeNodeKey(parentSfen)
    logger.debug(s"Updating move in repertoire $repertoireId: $usi (nodeKey: $nodeKey)")
    
    getRepertoire(repertoireId).flatMap {
      case Some(doc) =>
        val nodes = if (doc.containsKey("nodes")) doc.get("nodes").getOrElse(new org.bson.BsonDocument()).asDocument() else new org.bson.BsonDocument()
        
        if (nodes.containsKey(nodeKey)) {
          val nodeData = nodes.get(nodeKey).asDocument()
          val moves = nodeData.getArray("moves")
          
          moves.asScala.zipWithIndex.find { case (m, _) =>
            m.isDocument && m.asDocument().getString("usi").getValue == usi
          }.foreach { case (m, idx) =>
            val moveDoc = m.asDocument()
            comment match {
              case Some(c) => moveDoc.put("comment", new org.bson.BsonString(c))
              case None => moveDoc.remove("comment")
            }
            isPuzzle match {
              case Some(p) => moveDoc.put("isPuzzle", new org.bson.BsonBoolean(p))
              case None => moveDoc.remove("isPuzzle")
            }
          }
          
          collection.updateOne(
            equal("_id", new ObjectId(repertoireId)),
            set(s"nodes.$nodeKey", nodeData)
          ).toFuture().map(_ => ())
        } else {
          Future.failed(new Exception("Node not found"))
        }
      case None => 
        Future.failed(new Exception("Repertoire not found"))
    }
  }

  def deleteMove(repertoireId: String, parentSfen: String, usi: String): Future[Unit] = {
    val nodeKey = sanitizeNodeKey(parentSfen)
    logger.debug(s"Deleting move from repertoire $repertoireId: $usi (nodeKey: $nodeKey)")
    
    getRepertoire(repertoireId).flatMap {
      case Some(doc) =>
        val nodes = if (doc.containsKey("nodes")) doc.get("nodes").getOrElse(new org.bson.BsonDocument()).asDocument() else new org.bson.BsonDocument()
        
        if (nodes.containsKey(nodeKey)) {
          val nodeData = nodes.get(nodeKey).asDocument()
          val moves = nodeData.getArray("moves")
          
          val newMoves = new org.bson.BsonArray()
          moves.asScala.foreach { m =>
            if (!(m.isDocument && m.asDocument().getString("usi").getValue == usi)) {
              newMoves.add(m)
            }
          }
          
          if (newMoves.size() != moves.size()) {
            nodeData.put("moves", newMoves)
            logger.debug(s"Updating repertoire $repertoireId after deleting move $usi")
            collection.updateOne(
              equal("_id", new ObjectId(repertoireId)),
              set(s"nodes.$nodeKey", nodeData)
            ).toFuture().map(_ => ())
          } else {
            Future.successful(())
          }
        } else {
          Future.successful(())
        }
      case None =>
        logger.warn(s"Repertoire $repertoireId not found")
        Future.failed(new Exception("Repertoire not found"))
    }
  }

  def deleteRepertoire(id: String): Future[Unit] = {
    logger.info(s"Deleting repertoire with id: $id")
    collection.deleteOne(equal("_id", new ObjectId(id))).toFuture().map(_ => logger.info(s"Deleted repertoire $id"))
  }

  def clearRepertoireNodes(id: String): Future[Unit] = {
    logger.info(s"Clearing nodes for repertoire with id: $id")
    collection.updateOne(
      equal("_id", new ObjectId(id)),
      set("nodes", Document())
    ).toFuture().map(_ => ())
  }

  def toggleRepertoirePublic(id: String, isPublic: Boolean): Future[Unit] = {
    logger.info(s"Setting repertoire $id is_public=$isPublic")
    collection.updateOne(
      equal("_id", new ObjectId(id)),
      set("is_public", isPublic)
    ).toFuture().map(_ => ())
  }

  def getPublicRepertoires(): Future[Seq[Document]] = {
    collection.find(equal("is_public", true))
      .projection(exclude("nodes"))
      .toFuture()
  }

  def getPublicRepertoire(id: String): Future[Option[Document]] = {
    collection.find(and(equal("_id", new ObjectId(id)), equal("is_public", true))).headOption()
  }

  def addMoves(repertoireId: String, parentSfen: String, moves: Seq[(String, String)]): Future[Unit] = {
    // moves: Seq[(usi, nextSfen)]
    if (moves.isEmpty) return Future.successful(())

    val (usi, nextSfen) = moves.head
    addMove(repertoireId, parentSfen, usi, nextSfen).flatMap { _ =>
      addMoves(repertoireId, nextSfen, moves.tail)
    }
  }
}
