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
  def saveCustomPuzzle(name: String, sfen: String, userEmail: String, isPublic: Boolean = false, comments: Option[String] = None, selectedSequence: Option[Seq[String]] = None, moveComments: Option[Map[String, String]] = None, analysisData: Option[String] = None, selectedCandidates: Option[Seq[Int]] = None): Future[InsertOneResult]
  def getCustomPuzzles(userEmail: String): Future[Seq[Document]]
  def getCustomPuzzle(id: String, userEmail: String): Future[Option[Document]]
  def updateCustomPuzzle(id: String, name: String, sfen: String, userEmail: String, isPublic: Boolean = false, comments: Option[String] = None, selectedSequence: Option[Seq[String]] = None, moveComments: Option[Map[String, String]] = None, analysisData: Option[String] = None, selectedCandidates: Option[Seq[Int]] = None): Future[UpdateResult]
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
    puzzlesCollection.find(org.mongodb.scala.model.Filters.equal("game_kif_hash", gameKifHash))
      .sort(org.mongodb.scala.model.Sorts.ascending("move_number"))
      .toFuture()
  }

  def getPublicPuzzles(): Future[Seq[Document]] = {
    // Return puzzles where is_public is true OR is_public field doesn't exist (backward compatibility)
    puzzlesCollection.find(
      org.mongodb.scala.model.Filters.or(
        org.mongodb.scala.model.Filters.equal("is_public", true),
        org.mongodb.scala.model.Filters.exists("is_public", false)
      )
    ).toFuture()
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
  def saveCustomPuzzle(name: String, sfen: String, userEmail: String, isPublic: Boolean = false, comments: Option[String] = None, selectedSequence: Option[Seq[String]] = None, moveComments: Option[Map[String, String]] = None, analysisData: Option[String] = None, selectedCandidates: Option[Seq[Int]] = None): Future[InsertOneResult] = {
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
      "analysis_data" -> analysisData.getOrElse(""),
      "selected_candidates" -> selectedCandidates.getOrElse(Seq.empty),
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

  def updateCustomPuzzle(id: String, name: String, sfen: String, userEmail: String, isPublic: Boolean = false, comments: Option[String] = None, selectedSequence: Option[Seq[String]] = None, moveComments: Option[Map[String, String]] = None, analysisData: Option[String] = None, selectedCandidates: Option[Seq[Int]] = None): Future[UpdateResult] = {
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
    updates += org.mongodb.scala.model.Updates.set("analysis_data", analysisData.getOrElse(""))
    updates += org.mongodb.scala.model.Updates.set("selected_candidates", selectedCandidates.getOrElse(Seq.empty))
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
      .find(org.mongodb.scala.model.Filters.equal("is_public", true))
      .sort(org.mongodb.scala.model.Sorts.descending("created_at"))
      .toFuture()
  }

  def getAllPublicPuzzles(): Future[Seq[Document]] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    for {
      viewerPuzzles <- puzzlesCollection.find(org.mongodb.scala.model.Filters.equal("is_public", true)).toFuture()
      customPuzzles <- getPublicCustomPuzzles()
    } yield {
      // Convert custom puzzles to viewer puzzle format
      val convertedCustomPuzzles = customPuzzles.map(convertCustomPuzzleToViewerFormat)
      viewerPuzzles ++ convertedCustomPuzzles
    }
  }

  /**
   * Converts a USI string into a BsonDocument matching the structure expected by puzzle.js
   * for move validation and arrow hints.
   * Regular move USI: "7g7f" or "7g7f+" (with promotion)
   * Drop move USI: "P*5e"
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
    val description = ranking match {
      case 1 => "1st"
      case 2 => "2nd"
      case 3 => "3rd"
      case _ => "your"
    }

    if (usi.contains("*")) {
      // Drop move: e.g. "P*5e"
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
            .append("brush", new BsonString(brush))
            .append("description", new BsonString(description))))
        .append("move", BsonNull.VALUE)
        .append("score", BsonNull.VALUE)
    } else {
      // Regular move: e.g. "7g7f" or "7g7f+"
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
            .append("brush", new BsonString(brush))
            .append("description", new BsonString(description))))
        .append("score", BsonNull.VALUE)
    }
  }

  def convertCustomPuzzleToViewerFormat(customPuzzle: Document): Document = {
    val id = customPuzzle.getObjectId("_id")
    val name = customPuzzle.getString("name")
    val sfen = customPuzzle.getString("sfen")
    val comments = customPuzzle.getString("comments")
    
    // Extract selected_sequence properly
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
    
    // Extract move_comments properly
    val moveComments = customPuzzle.get("move_comments") match {
      case Some(doc) if doc.isDocument => doc.asDocument()
      case _ => new org.bson.BsonDocument()
    }
    
    val analysisDataStr = customPuzzle.getString("analysis_data")
    val createdAt = customPuzzle.getLong("created_at")

    // Use initial SFEN if custom puzzle SFEN is empty
    val finalSfen = if (sfen != null && sfen.nonEmpty) sfen else "lnsgkgsnl/1r5b1/ppppppppp/9/9/PPPPPPPPP/1B5R1/LNSGKGSNL b - 1"

    // Parse SFEN to extract components
    // SFEN format: <board> <turn> <hands> <moveNumber>
    val sfenParts = finalSfen.split(" ")
    val boardSfen = sfenParts(0)  // Board position
    val turnColor = if (sfenParts.length > 1) sfenParts(1) else "b"
    val handsSfen = if (sfenParts.length > 2) sfenParts(2) else "-"

    val playerColorName = if (turnColor == "b") "sente" else "gote"

    // Create a viewer puzzle format document using BsonDocument
    import org.bson.BsonDocument
    import org.bson.BsonString
    import org.bson.BsonInt32
    import org.bson.BsonBoolean
    import org.bson.BsonArray
    import org.bson.BsonDateTime
    import org.bson.BsonNull
    import scala.collection.JavaConverters._
    
    // Convert selectedSequence to Java List of BsonString
    val selectedSequenceBson = new java.util.ArrayList[org.bson.BsonValue]()
    for (s <- selectedSequence) {
      selectedSequenceBson.add(new BsonString(s))
    }
    
    // Extract selected_candidates from document (or fall back to 0,1,2 for backward compat)
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

    // Parse analysis_data to extract best, second, third moves based on selected candidates
    // analysis_data format: [[{moveNum, usi, score: {kind, value}, depth, sfenBefore, pv}, ...], ...]
    val rankLabels = Seq("best", "second", "third")
    val rankNames = Seq("Best", "Second", "Third")
    // Mutable arrays for up to 3 ranked moves + details
    val rankedMoves = Array.fill[org.bson.BsonValue](3)(BsonNull.VALUE)
    val rankedMoveDetails = Array.fill[org.bson.BsonValue](3)(BsonNull.VALUE)
    // Initialize best move with empty default
    rankedMoves(0) = new BsonDocument().append("usi", new BsonString("")).append("score", new BsonDocument())
    // Comment lines for analysis summary
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
          println(s"[GameRepository] Failed to parse analysis_data: ${e.getMessage}")
      }
    }

    val bestMove = rankedMoves(0)
    val secondMove = rankedMoves(1)
    val thirdMove = rankedMoves(2)
    val bestMoveDetail = rankedMoveDetails(0)
    val secondMoveDetail = rankedMoveDetails(1)
    val thirdMoveDetail = rankedMoveDetails(2)

    // Extract move_comments to enrich comment lines
    // Keys are "candidateIndex_moveIndex", e.g. "0_0" = best move comment, "1_0" = second, "2_0" = third
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

    // Build final comment lines, preferring move_comments over auto-generated lines
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

    // Build comment: user's manual comment + analysis lines (with move_comments)
    val analysisComment = enrichedLines.mkString("\n")
    val finalComment = if (comments != null && comments.nonEmpty) {
      if (analysisComment.nonEmpty) s"$comments\n$analysisComment" else comments
    } else {
      analysisComment
    }

    val bsonDoc = new BsonDocument()
      .append("_id", id)
      .append("id", new BsonString(id.toHexString))
      .append("move_number", new BsonInt32(1))
      .append("sfen", new BsonString(boardSfen))  // Just the board part
      .append("hands", new BsonString(handsSfen))  // Hands part separately
      .append("player", new BsonString(playerColorName))
      .append("your_move_usi", new BsonString(""))
      .append("opponent_last_move_usi", new BsonString(""))
      .append("score", new BsonDocument())
      .append("prev_score", new BsonDocument())
      .append("best", bestMove)
      .append("second", secondMove)
      .append("third", thirdMove)
      .append("opponent_last_move_usi_positions", BsonNull.VALUE)
      .append("your_move", BsonNull.VALUE)
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
      .append("created_at", new BsonDateTime(createdAt))
    
    // Convert BsonDocument to Document by parsing JSON
    Document(org.bson.BsonDocument.parse(bsonDoc.toJson))
  }
}
