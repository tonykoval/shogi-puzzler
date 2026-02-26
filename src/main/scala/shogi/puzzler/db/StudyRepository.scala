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

object StudyRepository {
  private val logger = LoggerFactory.getLogger(getClass)
  private val collection = MongoDBConnection.studyCollection

  private def sanitizeNodeKey(sfen: String): String =
    sfen.replace(".", "_").replace("/", "-").replace(" ", "_")

  def getStudies(ownerEmail: Option[String]): Future[Seq[Document]] = {
    val filter = ownerEmail match {
      case Some(email) => equal("ownerEmail", email)
      case None => or(equal("ownerEmail", null), equal("ownerEmail", ""), exists("ownerEmail", false))
    }
    logger.debug(s"Fetching studies for ownerEmail: $ownerEmail")
    collection.find(filter).projection(exclude("nodes")).toFuture()
  }

  def getStudy(id: String): Future[Option[Document]] = {
    collection.find(equal("_id", new ObjectId(id))).headOption()
  }

  def createStudy(name: String, ownerEmail: Option[String], isAutoReload: Boolean = false, reloadThreshold: Int = 200, reloadColor: Option[String] = None, rootSfen: Option[String] = None, rootComment: Option[String] = None, sourceUrl: Option[String] = None, sourceAuthor: Option[String] = None, studyUrl: Option[String] = None, studyName: Option[String] = None): Future[String] = {
    logger.info(s"Creating study: name=$name, ownerEmail=$ownerEmail, isAutoReload=$isAutoReload, reloadThreshold=$reloadThreshold, reloadColor=$reloadColor")
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
      reloadColor.map(color => Document("reloadColor" -> color)).getOrElse(Document()) ++
      rootComment.map(c => Document("rootComment" -> c)).getOrElse(Document()) ++
      sourceUrl.map(u => Document("sourceUrl" -> u)).getOrElse(Document()) ++
      sourceAuthor.map(a => Document("sourceAuthor" -> a)).getOrElse(Document()) ++
      studyUrl.map(u => Document("studyUrl" -> u)).getOrElse(Document()) ++
      studyName.map(n => Document("studyName" -> n)).getOrElse(Document())
    collection.insertOne(doc).toFuture().map { res =>
      val id = res.getInsertedId.asObjectId().getValue.toString
      logger.info(s"Inserted study with id: $id")
      id
    }.recover {
      case e: Exception =>
        logger.error(s"Error inserting study: ${e.getMessage}", e)
        throw e
    }
  }

  def addMove(studyId: String, parentSfen: String, usi: String, nextSfen: String, comment: Option[String] = None, isPuzzle: Option[Boolean] = None): Future[Unit] = {
    val nodeKey = sanitizeNodeKey(parentSfen)
    logger.debug(s"Adding move to study $studyId: $usi (nodeKey: $nodeKey)")

    getStudy(studyId).flatMap {
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

          logger.debug(s"Updating study $studyId with new move $usi")
          collection.updateOne(
            equal("_id", new ObjectId(studyId)),
            set(s"nodes.$nodeKey", nodeData)
          ).toFuture().map(_ => ())
        } else {
          logger.debug(s"Move $usi already exists in node $nodeKey")
          Future.successful(())
        }
      case None =>
        logger.warn(s"Study $studyId not found")
        Future.failed(new Exception("Study not found"))
    }
  }

  def updateMove(studyId: String, parentSfen: String, usi: String, comment: Option[String], isPuzzle: Option[Boolean]): Future[Unit] = {
    val nodeKey = sanitizeNodeKey(parentSfen)
    logger.debug(s"Updating move in study $studyId: $usi (nodeKey: $nodeKey)")

    getStudy(studyId).flatMap {
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
            equal("_id", new ObjectId(studyId)),
            set(s"nodes.$nodeKey", nodeData)
          ).toFuture().map(_ => ())
        } else {
          Future.failed(new Exception("Node not found"))
        }
      case None =>
        Future.failed(new Exception("Study not found"))
    }
  }

  def deleteMove(studyId: String, parentSfen: String, usi: String): Future[Unit] = {
    val nodeKey = sanitizeNodeKey(parentSfen)
    logger.debug(s"Deleting move from study $studyId: $usi (nodeKey: $nodeKey)")

    getStudy(studyId).flatMap {
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
            logger.debug(s"Updating study $studyId after deleting move $usi")
            collection.updateOne(
              equal("_id", new ObjectId(studyId)),
              set(s"nodes.$nodeKey", nodeData)
            ).toFuture().map(_ => ())
          } else {
            Future.successful(())
          }
        } else {
          Future.successful(())
        }
      case None =>
        logger.warn(s"Study $studyId not found")
        Future.failed(new Exception("Study not found"))
    }
  }

  def deleteStudy(id: String): Future[Unit] = {
    logger.info(s"Deleting study with id: $id")
    collection.deleteOne(equal("_id", new ObjectId(id))).toFuture().map(_ => logger.info(s"Deleted study $id"))
  }

  def clearStudyNodes(id: String): Future[Unit] = {
    logger.info(s"Clearing nodes for study with id: $id")
    collection.updateOne(
      equal("_id", new ObjectId(id)),
      set("nodes", Document())
    ).toFuture().map(_ => ())
  }

  def updateRootComment(id: String, comment: String): Future[Unit] = {
    collection.updateOne(
      equal("_id", new ObjectId(id)),
      set("rootComment", comment)
    ).toFuture().map(_ => ())
  }

  def toggleStudyPublic(id: String, isPublic: Boolean): Future[Unit] = {
    logger.info(s"Setting study $id is_public=$isPublic")
    collection.updateOne(
      equal("_id", new ObjectId(id)),
      set("is_public", isPublic)
    ).toFuture().map(_ => ())
  }

  def getPublicStudies(): Future[Seq[Document]] = {
    collection.find(equal("is_public", true))
      .projection(exclude("nodes"))
      .toFuture()
  }

  def getPublicStudy(id: String): Future[Option[Document]] = {
    collection.find(and(equal("_id", new ObjectId(id)), equal("is_public", true))).headOption()
  }

  /** Returns public studies + the authenticated user's own studies (including private). */
  def getStudiesForViewer(ownerEmail: Option[String]): Future[Seq[Document]] = {
    val filter = ownerEmail match {
      case Some(email) => or(equal("is_public", true), equal("ownerEmail", email))
      case None        => equal("is_public", true)
    }
    collection.find(filter).projection(exclude("nodes")).toFuture()
  }

  /** Returns a study by id if it is public OR belongs to the given owner. */
  def getStudyForViewer(id: String, ownerEmail: Option[String]): Future[Option[Document]] = {
    val filter = ownerEmail match {
      case Some(email) => and(equal("_id", new ObjectId(id)), or(equal("is_public", true), equal("ownerEmail", email)))
      case None        => and(equal("_id", new ObjectId(id)), equal("is_public", true))
    }
    collection.find(filter).headOption()
  }

  def saveMovei18nComment(studyId: String, parentSfen: String, usi: String, lang: String, comment: String): Future[Unit] = {
    val nodeKey = sanitizeNodeKey(parentSfen)
    logger.debug(s"Saving i18n comment for move $usi in study $studyId (nodeKey: $nodeKey, lang: $lang)")

    getStudy(studyId).flatMap {
      case Some(doc) =>
        val nodes = if (doc.containsKey("nodes")) doc.get("nodes").getOrElse(new org.bson.BsonDocument()).asDocument() else new org.bson.BsonDocument()

        if (nodes.containsKey(nodeKey)) {
          val nodeData = nodes.get(nodeKey).asDocument()
          val moves = nodeData.getArray("moves")

          moves.asScala.find { m =>
            m.isDocument && m.asDocument().getString("usi").getValue == usi
          }.foreach { m =>
            val moveDoc = m.asDocument()
            val i18nDoc = if (moveDoc.containsKey("comment_i18n")) {
              moveDoc.get("comment_i18n").asDocument()
            } else {
              val newDoc = new org.bson.BsonDocument()
              moveDoc.put("comment_i18n", newDoc)
              newDoc
            }
            i18nDoc.put(lang, new org.bson.BsonString(comment))
          }

          collection.updateOne(
            equal("_id", new ObjectId(studyId)),
            set(s"nodes.$nodeKey", nodeData)
          ).toFuture().map(_ => ())
        } else {
          Future.failed(new Exception("Node not found"))
        }
      case None =>
        Future.failed(new Exception("Study not found"))
    }
  }

  def addMoves(studyId: String, parentSfen: String, moves: Seq[(String, String)]): Future[Unit] = {
    // moves: Seq[(usi, nextSfen)]
    if (moves.isEmpty) return Future.successful(())

    val (usi, nextSfen) = moves.head
    addMove(studyId, parentSfen, usi, nextSfen).flatMap { _ =>
      addMoves(studyId, nextSfen, moves.tail)
    }
  }
}
