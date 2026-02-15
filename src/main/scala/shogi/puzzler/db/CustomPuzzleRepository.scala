package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.result.{InsertOneResult, UpdateResult}
import scala.concurrent.Future

object CustomPuzzleRepository {
  private val customPuzzlesCollection = MongoDBConnection.customPuzzlesCollection
  private val puzzlesCollection = MongoDBConnection.puzzlesCollection

  def saveCustomPuzzle(name: String, sfen: String, userEmail: String, isPublic: Boolean = false, comments: Option[String] = None, selectedSequence: Option[Seq[String]] = None, moveComments: Option[Map[String, String]] = None, analysisData: Option[String] = None, selectedCandidates: Option[Seq[Int]] = None, gameKifHash: Option[String] = None, blunderMoves: Option[Seq[String]] = None, status: String = "accepted", tags: Option[Seq[String]] = None, moveNumber: Option[Int] = None, blunderAnalyses: Option[String] = None): Future[InsertOneResult] = {
    import org.mongodb.scala.bson.BsonString
    import org.mongodb.scala.bson.collection.immutable.Document

    val moveCommentsDoc = Document(moveComments.getOrElse(Map.empty).map { case (k, v) => k -> BsonString(v) })

    val baseDoc = Document(
      "name" -> name,
      "sfen" -> sfen,
      "user_email" -> userEmail,
      "is_public" -> isPublic,
      "status" -> status,
      "comments" -> comments.getOrElse(""),
      "selected_sequence" -> selectedSequence.getOrElse(Seq.empty),
      "move_comments" -> moveCommentsDoc,
      "analysis_data" -> analysisData.getOrElse(""),
      "selected_candidates" -> selectedCandidates.getOrElse(Seq.empty),
      "blunder_moves" -> blunderMoves.getOrElse(Seq.empty),
      "tags" -> tags.getOrElse(Seq.empty),
      "blunder_analyses" -> blunderAnalyses.getOrElse(""),
      "move_number" -> moveNumber.getOrElse(0),
      "created_at" -> System.currentTimeMillis(),
      "updated_at" -> System.currentTimeMillis()
    )
    val doc = gameKifHash match {
      case Some(hash) => baseDoc ++ Document("game_kif_hash" -> hash)
      case None => baseDoc
    }
    customPuzzlesCollection.insertOne(doc).toFuture()
  }

  def getCustomPuzzles(userEmail: String): Future[Seq[Document]] = {
    customPuzzlesCollection
      .find(org.mongodb.scala.model.Filters.equal("user_email", userEmail))
      .sort(org.mongodb.scala.model.Sorts.descending("created_at"))
      .toFuture()
  }

  def getAcceptedCustomPuzzles(userEmail: String): Future[Seq[Document]] = {
    customPuzzlesCollection
      .find(org.mongodb.scala.model.Filters.and(
        org.mongodb.scala.model.Filters.equal("user_email", userEmail),
        org.mongodb.scala.model.Filters.or(
          org.mongodb.scala.model.Filters.equal("status", "accepted"),
          org.mongodb.scala.model.Filters.exists("status", false)
        )
      ))
      .sort(org.mongodb.scala.model.Sorts.descending("created_at"))
      .toFuture()
  }

  def getCustomPuzzlesByStatus(userEmail: String, status: String): Future[Seq[Document]] = {
    customPuzzlesCollection
      .find(org.mongodb.scala.model.Filters.and(
        org.mongodb.scala.model.Filters.equal("user_email", userEmail),
        org.mongodb.scala.model.Filters.equal("status", status)
      ))
      .sort(org.mongodb.scala.model.Sorts.descending("created_at"))
      .toFuture()
  }

  def updateCustomPuzzleStatus(id: String, userEmail: String, status: String): Future[UpdateResult] = {
    customPuzzlesCollection.updateOne(
      org.mongodb.scala.model.Filters.and(
        org.mongodb.scala.model.Filters.equal("_id", new org.bson.types.ObjectId(id)),
        org.mongodb.scala.model.Filters.equal("user_email", userEmail)
      ),
      org.mongodb.scala.model.Updates.combine(
        org.mongodb.scala.model.Updates.set("status", status),
        org.mongodb.scala.model.Updates.set("updated_at", System.currentTimeMillis())
      )
    ).toFuture()
  }

  def getCustomPuzzlesForGame(gameKifHash: String): Future[Seq[Document]] = {
    customPuzzlesCollection
      .find(org.mongodb.scala.model.Filters.equal("game_kif_hash", gameKifHash))
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

  def updateCustomPuzzle(id: String, name: String, sfen: String, userEmail: String, isPublic: Boolean = false, comments: Option[String] = None, selectedSequence: Option[Seq[String]] = None, moveComments: Option[Map[String, String]] = None, analysisData: Option[String] = None, selectedCandidates: Option[Seq[Int]] = None, blunderMoves: Option[Seq[String]] = None, tags: Option[Seq[String]] = None, blunderAnalyses: Option[String] = None): Future[UpdateResult] = {
    import org.mongodb.scala.bson.BsonString
    import org.mongodb.scala.bson.collection.immutable.Document

    val updates = scala.collection.mutable.ListBuffer[org.bson.conversions.Bson]()

    val moveCommentsDoc = Document(moveComments.getOrElse(Map.empty).map { case (k, v) => k -> BsonString(v) })

    updates += org.mongodb.scala.model.Updates.set("name", name)
    updates += org.mongodb.scala.model.Updates.set("sfen", sfen)
    updates += org.mongodb.scala.model.Updates.set("is_public", isPublic)
    updates += org.mongodb.scala.model.Updates.set("comments", comments.getOrElse(""))
    updates += org.mongodb.scala.model.Updates.set("selected_sequence", selectedSequence.getOrElse(Seq.empty))
    updates += org.mongodb.scala.model.Updates.set("move_comments", moveCommentsDoc)
    updates += org.mongodb.scala.model.Updates.set("analysis_data", analysisData.getOrElse(""))
    updates += org.mongodb.scala.model.Updates.set("selected_candidates", selectedCandidates.getOrElse(Seq.empty))
    updates += org.mongodb.scala.model.Updates.set("blunder_moves", blunderMoves.getOrElse(Seq.empty))
    updates += org.mongodb.scala.model.Updates.set("tags", tags.getOrElse(Seq.empty))
    updates += org.mongodb.scala.model.Updates.set("blunder_analyses", blunderAnalyses.getOrElse(""))
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

  def getPublicCustomPuzzles(): Future[Seq[Document]] = {
    customPuzzlesCollection
      .find(org.mongodb.scala.model.Filters.and(
        org.mongodb.scala.model.Filters.equal("is_public", true),
        org.mongodb.scala.model.Filters.or(
          org.mongodb.scala.model.Filters.equal("status", "accepted"),
          org.mongodb.scala.model.Filters.exists("status", false)
        )
      ))
      .sort(org.mongodb.scala.model.Sorts.descending("created_at"))
      .toFuture()
  }

  def getAllPublicPuzzles(): Future[Seq[Document]] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    for {
      viewerPuzzles <- puzzlesCollection.find(org.mongodb.scala.model.Filters.equal("is_public", true)).toFuture()
      customPuzzles <- getPublicCustomPuzzles()
    } yield {
      val convertedCustomPuzzles = customPuzzles.map(convertCustomPuzzleToViewerFormat)
      viewerPuzzles ++ convertedCustomPuzzles
    }
  }

  /**
   * Converts a USI string into a BsonDocument matching the structure expected by puzzle.js
   * for move validation and arrow hints.
   */
  private def usiToMoveDetail(usi: String, playerColor: String, ranking: Int): org.bson.BsonDocument = {
    import org.bson.BsonDocument
    import org.bson.BsonString
    import org.bson.BsonBoolean
    import org.bson.BsonNull

    val brush = ranking match {
      case 1 => "primary"
      case 2 => "alternative1"
      case 3 => "alternative2"
      case _ => "alternative0"
    }
    if (usi.contains("*")) {
      val pieceChar = usi.substring(0, 1).toUpperCase
      val dest = usi.substring(2)
      val roleMap = Map(
        "P" -> "pawn", "L" -> "lance", "N" -> "knight", "S" -> "silver",
        "G" -> "gold", "B" -> "bishop", "R" -> "rook"
      )
      val role = roleMap.getOrElse(pieceChar, pieceChar.toLowerCase)

      new BsonDocument()
        .append("drop", new BsonDocument()
          .append("drop", new BsonDocument()
            .append("role", new BsonString(role))
            .append("pos", new BsonString(dest)))
          .append("hint", new BsonDocument()
            .append("orig", new BsonDocument()
              .append("role", new BsonString(role))
              .append("color", new BsonString(playerColor)))
            .append("dest", new BsonString(dest))
            .append("brush", new BsonString(brush))))
        .append("move", BsonNull.VALUE)
        .append("score", BsonNull.VALUE)
    } else {
      val orig = usi.substring(0, 2)
      val dest = usi.substring(2, 4)
      val promotion = usi.endsWith("+")

      new BsonDocument()
        .append("drop", BsonNull.VALUE)
        .append("move", new BsonDocument()
          .append("move", new BsonDocument()
            .append("orig", new BsonString(orig))
            .append("dest", new BsonString(dest))
            .append("promotion", new BsonBoolean(promotion)))
          .append("hint", new BsonDocument()
            .append("orig", new BsonString(orig))
            .append("dest", new BsonString(dest))
            .append("brush", new BsonString(brush))))
        .append("score", BsonNull.VALUE)
    }
  }

  def convertCustomPuzzleToViewerFormat(customPuzzle: Document): Document = {
    val id = customPuzzle.getObjectId("_id")
    val name = customPuzzle.getString("name")
    val sfen = customPuzzle.getString("sfen")
    val comments = customPuzzle.getString("comments")

    val selectedSequence: Seq[String] = customPuzzle.get("selected_sequence") match {
      case Some(arr) if arr.isArray =>
        import scala.collection.JavaConverters._
        arr.asArray().getValues.asScala.map { v =>
          v match {
            case bs: org.bson.BsonString => bs.getValue
            case _ => ""
          }
        }.toSeq
      case _ => Seq.empty[String]
    }

    val moveComments = customPuzzle.get("move_comments") match {
      case Some(doc) if doc.isDocument => doc.asDocument()
      case _ => new org.bson.BsonDocument()
    }

    val analysisDataStr = customPuzzle.getString("analysis_data")
    val createdAt = customPuzzle.getLong("created_at")

    val finalSfen = if (sfen != null && sfen.nonEmpty) sfen else "lnsgkgsnl/1r5b1/ppppppppp/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL b - 1"

    val sfenParts = finalSfen.split(" ")
    val boardSfen = sfenParts(0)
    val turnColor = if (sfenParts.length > 1) sfenParts(1) else "b"
    val handsSfen = if (sfenParts.length > 2) sfenParts(2) else "-"

    val playerColorName = if (turnColor == "b") "sente" else "gote"

    import org.bson.BsonDocument
    import org.bson.BsonString
    import org.bson.BsonInt32
    import org.bson.BsonBoolean
    import org.bson.BsonArray
    import org.bson.BsonDateTime
    import org.bson.BsonNull
    import scala.collection.JavaConverters._

    val selectedSequenceBson = new java.util.ArrayList[org.bson.BsonValue]()
    for (s <- selectedSequence) {
      selectedSequenceBson.add(new BsonString(s))
    }

    val targetIndices: Seq[Int] = customPuzzle.get("selected_candidates") match {
      case Some(arr) if arr.isArray =>
        import scala.collection.JavaConverters._
        val indices = arr.asArray().getValues.asScala.map {
          case n: org.bson.BsonInt32 => n.getValue
          case n: org.bson.BsonInt64 => n.getValue.toInt
          case n: org.bson.BsonDouble => n.getValue.toInt
          case _ => 0
        }.toSeq
        if (indices.nonEmpty) indices.take(3) else Seq(0, 1, 2)
      case _ => Seq(0, 1, 2)
    }

    val rankLabels = Seq("best", "second", "third")
    val rankNames = Seq("Best", "Second", "Third")
    val rankedMoves = Array.fill[org.bson.BsonValue](3)(BsonNull.VALUE)
    val rankedMoveDetails = Array.fill[org.bson.BsonValue](3)(BsonNull.VALUE)
    rankedMoves(0) = new BsonDocument().append("usi", new BsonString("")).append("score", new BsonDocument())
    val commentLines = scala.collection.mutable.ListBuffer[String]()

    def formatScore(scoreData: ujson.Value): String = {
      val kind = scoreData.obj.get("kind").map(_.str).getOrElse("cp")
      val value = scoreData.obj.get("value").map(_.num.toInt).getOrElse(0)
      if (kind == "mate") s"mate $value" else {
        val sign = if (value >= 0) "+" else ""
        s"$sign$value"
      }
    }

    if (analysisDataStr != null && analysisDataStr.nonEmpty) {
      try {
        val analysisJson = ujson.read(analysisDataStr)
        analysisJson.arr match {
          case sequences if sequences.nonEmpty =>
            for ((candidateIdx, rank) <- targetIndices.take(3).zipWithIndex) {
              if (candidateIdx < sequences.length) {
                val seq = sequences(candidateIdx).arr
                if (seq.nonEmpty) {
                  val fullUsi = seq.map(_.obj.get("usi").map(_.str).getOrElse("")).mkString(" ")
                  val firstMoveData = seq(0)
                  val firstUsi = firstMoveData.obj.get("usi").map(_.str).getOrElse("")
                  val scoreObj = firstMoveData.obj.get("score") match {
                    case Some(s) =>
                      val kind = s.obj.get("kind").map(_.str).getOrElse("cp")
                      val value = s.obj.get("value").map(_.num.toInt).getOrElse(0)
                      new BsonDocument().append(kind, new BsonInt32(value))
                    case None => new BsonDocument()
                  }
                  rankedMoves(rank) = new BsonDocument()
                    .append("usi", new BsonString(fullUsi))
                    .append("score", scoreObj)
                  if (firstUsi.nonEmpty) {
                    rankedMoveDetails(rank) = usiToMoveDetail(firstUsi, playerColorName, rank + 1)
                    val seqStr = seq.map { m =>
                      val u = m.obj.get("usi").map(_.str).getOrElse("")
                      val s = m.obj.get("score").map(formatScore).getOrElse("")
                      if (s.nonEmpty) s"$u ($s)" else u
                    }.mkString(" -> ")
                    commentLines += s"${rankNames(rank)} [$seqStr]"
                  }
                }
              }
            }
          case _ =>
        }
      } catch {
        case e: Exception =>
          println(s"[CustomPuzzleRepository] Failed to parse analysis_data: ${e.getMessage}")
      }
    }

    val bestMove = rankedMoves(0)
    val secondMove = rankedMoves(1)
    val thirdMove = rankedMoves(2)
    val bestMoveDetail = rankedMoveDetails(0)
    val secondMoveDetail = rankedMoveDetails(1)
    val thirdMoveDetail = rankedMoveDetails(2)

    val moveCommentMap: Map[String, String] = {
      if (moveComments != null && !moveComments.isEmpty) {
        moveComments.entrySet().asScala.map { entry =>
          entry.getKey -> (entry.getValue match {
            case bs: org.bson.BsonString => bs.getValue
            case other => other.toString
          })
        }.toMap
      } else Map.empty
    }

    val enrichedLines = scala.collection.mutable.ListBuffer[String]()
    for ((candidateIdx, rank) <- targetIndices.take(3).zipWithIndex) {
      val prefix = rankNames(rank)
      val moveCommentKey = s"${candidateIdx}_0"
      val userComment = moveCommentMap.get(moveCommentKey).filter(_.nonEmpty)
      val autoLine = commentLines.lift(rank)

      (userComment, autoLine) match {
        case (Some(uc), _) => enrichedLines += s"$prefix [$uc]"
        case (_, Some(al)) => enrichedLines += al
        case _ =>
      }
    }

    val tagsList: Seq[String] = customPuzzle.get("tags") match {
      case Some(arr) if arr.isArray =>
        import scala.collection.JavaConverters._
        arr.asArray().getValues.asScala.map {
          case bs: org.bson.BsonString => bs.getValue
          case _ => ""
        }.filter(_.nonEmpty).toSeq
      case _ => Seq.empty[String]
    }

    val blunderMovesUsi: Seq[String] = customPuzzle.get("blunder_moves") match {
      case Some(arr) if arr.isArray =>
        import scala.collection.JavaConverters._
        arr.asArray().getValues.asScala.map {
          case bs: org.bson.BsonString => bs.getValue
          case _ => ""
        }.filter(_.nonEmpty).toSeq
      case _ => Seq.empty[String]
    }

    // Build blunder continuations and comment lines BEFORE computing finalComment
    val blunderAnalysesRawStr = customPuzzle.get("blunder_analyses") match {
      case Some(str) if str.isString => str.asString.getValue
      case _ => null
    }
    val hasBlunderAnalyses = blunderAnalysesRawStr != null && blunderAnalysesRawStr.nonEmpty

    val blunderContinuationsBson = new java.util.ArrayList[org.bson.BsonValue]()
    if (hasBlunderAnalyses) {
      try {
        // Count best sequences in analysis_data to calculate comment key offset
        val bestCount = if (analysisDataStr != null && analysisDataStr.nonEmpty) {
          try { ujson.read(analysisDataStr).arr.length } catch { case _: Exception => 0 }
        } else 0

        val blunderArr = ujson.read(blunderAnalysesRawStr)
        for ((ba, bIdx) <- blunderArr.arr.zipWithIndex) {
          val blunderMove = ba.obj.get("blunder").map(_.str).getOrElse("")
          val seq = ba.obj.get("sequence").map(_.arr).getOrElse(Seq.empty)
          if (blunderMove.nonEmpty && seq.nonEmpty) {
            val fullUsi = seq.map(_.obj.get("usi").map(_.str).getOrElse("")).mkString(" ")
            val firstMoveScore = seq(0).obj.get("score") match {
              case Some(s) =>
                val kind = s.obj.get("kind").map(_.str).getOrElse("cp")
                val value = s.obj.get("value").map(_.num.toInt).getOrElse(0)
                new BsonDocument().append(kind, new BsonInt32(value))
              case None => new BsonDocument()
            }
            val seqStr = seq.map { m =>
              val u = m.obj.get("usi").map(_.str).getOrElse("")
              val s = m.obj.get("score").map(formatScore).getOrElse("")
              if (s.nonEmpty) s"$u ($s)" else u
            }.mkString(" -> ")
            // Check for user-edited comment (key = bestCount + blunderIndex + "_0")
            val blunderCommentKey = s"${bestCount + bIdx}_0"
            val userComment = moveCommentMap.get(blunderCommentKey).filter(_.nonEmpty)
            val blunderLine = userComment match {
              case Some(uc) => s"Blunder [$uc]"
              case None     => s"Blunder [$seqStr]"
            }
            enrichedLines += blunderLine
            blunderContinuationsBson.add(
              new BsonDocument()
                .append("blunder_move", new BsonString(blunderMove))
                .append("usi", new BsonString(fullUsi))
                .append("score", firstMoveScore)
            )
          }
        }
      } catch {
        case e: Exception =>
          println(s"[CustomPuzzleRepository] Failed to parse blunder_analyses for continuations: ${e.getMessage}")
      }
    } else if (blunderMovesUsi.nonEmpty) {
      // Fallback: no blunder_analyses, just show blunder move USI
      val blunderUsi = blunderMovesUsi.head
      enrichedLines += s"Blunder [$blunderUsi]"
    }

    // Compute finalComment AFTER all enrichedLines (best + blunder) are populated
    val analysisComment = enrichedLines.mkString("\n")
    val commentAlreadyHasAnalysis = comments != null && comments.nonEmpty &&
      Seq("Best ", "Second ", "Third ", "Blunder ").exists(prefix => comments.split('\n').exists(_.startsWith(prefix)))
    val finalComment = if (comments != null && comments.nonEmpty) {
      if (analysisComment.nonEmpty && !commentAlreadyHasAnalysis) s"$comments\n$analysisComment" else comments
    } else {
      analysisComment
    }

    val yourMoveDetail: org.bson.BsonValue = blunderMovesUsi.headOption match {
      case Some(usi) if usi.nonEmpty => usiToMoveDetail(usi, playerColorName, 0)
      case _ => BsonNull.VALUE
    }

    val blunderMoveDetailsBson = new java.util.ArrayList[org.bson.BsonValue]()
    blunderMovesUsi.foreach { usi =>
      blunderMoveDetailsBson.add(usiToMoveDetail(usi, playerColorName, 0))
    }

    val bsonDoc = new BsonDocument()
      .append("_id", id)
      .append("id", new BsonString(id.toHexString))
      .append("move_number", new BsonInt32(1))
      .append("sfen", new BsonString(boardSfen))
      .append("hands", new BsonString(handsSfen))
      .append("player", new BsonString(playerColorName))
      .append("your_move_usi", new BsonString(""))
      .append("opponent_last_move_usi", new BsonString(""))
      .append("score", new BsonDocument())
      .append("prev_score", new BsonDocument())
      .append("best", bestMove)
      .append("second", secondMove)
      .append("third", thirdMove)
      .append("opponent_last_move_usi_positions", BsonNull.VALUE)
      .append("your_move", yourMoveDetail)
      .append("blunder_moves", new BsonArray(blunderMoveDetailsBson))
      .append("blunder_analyses", new BsonString(if (customPuzzle.getString("blunder_analyses") != null) customPuzzle.getString("blunder_analyses") else ""))
      .append("blunder_continuations", new BsonArray(blunderContinuationsBson))
      .append("best_move", bestMoveDetail)
      .append("second_move", secondMoveDetail)
      .append("third_move", thirdMoveDetail)
      .append("comment", new BsonString(finalComment))
      .append("sente", new BsonString("Custom"))
      .append("gote", new BsonString("Puzzle"))
      .append("timeControl", BsonNull.VALUE)
      .append("site", new BsonString("Custom Puzzle"))
      .append("date", new BsonString(new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(createdAt))))
      .append("is_public", new BsonBoolean(true))
      .append("is_custom_puzzle", new BsonBoolean(true))
      .append("custom_puzzle_name", new BsonString(if (name != null) name else ""))
      .append("selected_sequence", new BsonArray(selectedSequenceBson))
      .append("move_comments", moveComments)
      .append("analysis_data", new BsonString(if (analysisDataStr != null) analysisDataStr else ""))
      .append("tags", {
        val tagsBson = new java.util.ArrayList[org.bson.BsonValue]()
        tagsList.foreach(t => tagsBson.add(new BsonString(t)))
        new BsonArray(tagsBson)
      })
      .append("created_at", new BsonDateTime(createdAt))

    // Pass through rating fields
    val playCount = customPuzzle.get("play_count") match {
      case Some(v) if v.isInt32 => v.asInt32()
      case Some(v) if v.isInt64 => new BsonInt32(v.asInt64().getValue.toInt)
      case _ => new BsonInt32(0)
    }
    bsonDoc.append("play_count", playCount)

    val avgRating = customPuzzle.get("avg_rating") match {
      case Some(v) if v.isDouble => v.asDouble()
      case Some(v) if v.isInt32 => new org.bson.BsonDouble(v.asInt32().getValue.toDouble)
      case _ => new org.bson.BsonDouble(0.0)
    }
    bsonDoc.append("avg_rating", avgRating)

    customPuzzle.get("ratings") match {
      case Some(v) if v.isDocument => bsonDoc.append("ratings", v.asDocument())
      case _ =>
    }

    Document(org.bson.BsonDocument.parse(bsonDoc.toJson))
  }
}
