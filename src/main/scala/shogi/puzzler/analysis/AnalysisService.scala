package shogi.puzzler.analysis

import shogi.Color
import shogi.puzzler.domain.{CpScore, MateScore, Score}
import shogi.puzzler.engine.{EngineManager, Limit}
import org.slf4j.LoggerFactory

object AnalysisService {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Analyze a single position and return results as ujson objects.
   *
   * @param engineManager Engine to use for analysis
   * @param sfen Position to analyze
   * @param depth Search depth
   * @param multiPv Number of candidate moves
   * @param time Optional time limit in milliseconds
   * @param playerColor Color of the player (for score perspective)
   * @return Sequence of analysis result objects
   */
  def analyzePosition(
    engineManager: EngineManager,
    sfen: String,
    depth: Int,
    multiPv: Int,
    time: Option[Int] = None,
    playerColor: Color
  ): Seq[ujson.Obj] = {
    val limit = Limit(depth = Some(depth), time = time)
    val results = engineManager.analyze(sfen, limit, multiPv)
    val colorToMove = SfenUtils.sfenTurnToColor(sfen)

    results.map { result =>
      val usiMove = SfenUtils.extractUsiFromResult(result)
      val povScore = Score.fromEngine(result.get("score"), colorToMove)
      val playerScore = povScore.forPlayer(playerColor)

      val (scoreKind, scoreValue) = playerScore match {
        case CpScore(cp) => ("cp", cp)
        case MateScore(moves) => ("mate", moves)
        case _ => ("cp", 0)
      }

      ujson.Obj(
        "usi" -> usiMove,
        "score" -> ujson.Obj("kind" -> scoreKind, "value" -> scoreValue),
        "depth" -> ujson.Num(result.getOrElse("depth", depth).toString.toInt),
        "pv" -> SfenUtils.extractPvString(result)
      )
    }
  }

  /**
   * Analyze move sequences starting from a position, exploring multiple candidate lines.
   *
   * @param engineManager Engine to use
   * @param initialSfen Starting position
   * @param numMoves Number of moves to explore per line
   * @param depth Search depth
   * @param multiPv Number of candidate first moves
   * @param timeSec Optional time per move in seconds
   * @return Vector of move sequences, each being a vector of move objects
   */
  def analyzeMoveSequences(
    engineManager: EngineManager,
    initialSfen: String,
    numMoves: Int,
    depth: Int,
    multiPv: Int,
    timeSec: Option[Int] = None
  ): Vector[Vector[ujson.Obj]] = {
    val limit = Limit(depth = Some(depth), time = timeSec.map(_ * 1000))

    val searchMultiPv = math.max(multiPv * 3, 10)
    val initialResults = engineManager.analyze(initialSfen, limit, searchMultiPv)
    val initialColorToMove = SfenUtils.sfenTurnToColor(initialSfen)

    val uniqueFirstMoves = scala.collection.mutable.LinkedHashMap[String, (String, Score)]()

    initialResults.foreach { result =>
      val usiMove = SfenUtils.extractUsiFromResult(result)
      if (!uniqueFirstMoves.contains(usiMove) && usiMove.nonEmpty) {
        val povScore = Score.fromEngine(result.get("score"), initialColorToMove)
        val senteScore = povScore.forPlayer(Color.Sente)
        uniqueFirstMoves(usiMove) = (usiMove, senteScore)
      }
    }

    logger.info(s"[AnalysisService] Found ${uniqueFirstMoves.size} unique first moves out of ${initialResults.length} candidates")

    val sequences = Vector.newBuilder[Vector[ujson.Obj]]
    var candidateCount = 0

    uniqueFirstMoves.keys.take(multiPv).foreach { firstMove =>
      if (candidateCount < multiPv) {
        val moves = Vector.newBuilder[ujson.Obj]
        val moveHistory = scala.collection.mutable.ArrayBuffer[String](firstMove)

        val firstScore = uniqueFirstMoves(firstMove)._2
        val (firstScoreKind, firstScoreValue) = firstScore match {
          case CpScore(cp) => ("cp", cp)
          case MateScore(m) => ("mate", m)
          case _ => ("cp", 0)
        }

        moves += ujson.Obj(
          "moveNum" -> 1,
          "usi" -> firstMove,
          "score" -> ujson.Obj("kind" -> firstScoreKind, "value" -> firstScoreValue),
          "depth" -> depth,
          "sfenBefore" -> initialSfen,
          "pv" -> firstMove
        )

        for (moveNum <- 1 until numMoves) {
          val results = engineManager.analyzeWithMoves(initialSfen, moveHistory.toSeq, limit, 1)

          if (results.nonEmpty && results.head.contains("pv")) {
            val pv = results.head("pv").asInstanceOf[List[String]]

            if (pv.nonEmpty) {
              val bestMove = pv.head

              val isCurrentSente = SfenUtils.isSenteTurn(moveNum + 1, initialSfen)
              val currentColorToMove = if (isCurrentSente) Color.Sente else Color.Gote

              val povScore = Score.fromEngine(results.head.get("score"), currentColorToMove)
              val senteScore = povScore.forPlayer(Color.Sente)

              val (scoreKind, scoreValue) = senteScore match {
                case CpScore(cp) => ("cp", cp)
                case MateScore(m) => ("mate", m)
                case _ => ("cp", 0)
              }

              moves += ujson.Obj(
                "moveNum" -> (moveNum + 1),
                "usi" -> bestMove,
                "score" -> ujson.Obj("kind" -> scoreKind, "value" -> scoreValue),
                "depth" -> depth,
                "sfenBefore" -> initialSfen,
                "pv" -> pv.mkString(" ")
              )

              moveHistory += bestMove
            }
          }
        }

        sequences += moves.result()
        candidateCount += 1
      }
    }

    logger.info(s"[AnalysisService] Generated ${sequences.result().length} sequences with unique first moves")
    sequences.result()
  }
}
