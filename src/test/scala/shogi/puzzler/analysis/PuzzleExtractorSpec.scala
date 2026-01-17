package shogi.puzzler.analysis

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import shogi.Color
import shogi.format.forsyth.Sfen
import shogi.format.usi.Usi
import shogi.puzzler.domain._

class PuzzleExtractorSpec extends AnyFlatSpec with Matchers {

  class StubGameAnalyzer extends GameAnalyzer(null) {
    var nextDeepAnalysisResult: Seq[AnalysisResult] = Seq.empty
    var deepAnalysisCalled: Boolean = false

    override def analyzeDeep(sfenPosition: String, moveHistory: Vector[Usi], moveNumber: Int, analysisSeconds: Int): Seq[AnalysisResult] = {
      deepAnalysisCalled = true
      nextDeepAnalysisResult
    }

    override def analyzeShallow(game: ParsedGame, secondsPerMove: Int): Seq[AnalysisResult] = Seq.empty
  }

  val stubAnalyzer = new StubGameAnalyzer()
  val extractor = new PuzzleExtractor(stubAnalyzer)

  val sente = Color.Sente
  val gote = Color.Gote

  def createAnalysisResult(
      moveNumber: Int,
      sfen: String,
      score: Score,
      history: Vector[Usi] = Vector.empty,
      pv: Option[String] = None
  ): AnalysisResult = {
    val sfenObj = Sfen(sfen)
    val colorToMove = if (sfen.contains(" b ")) Color.Sente else if (sfen.contains(" w ")) Color.Gote else Color.Sente
    AnalysisResult(
      positionSfen = sfenObj,
      moveHistoryToPosition = history,
      evaluationScore = PovScore(colorToMove, score),
      moveNumber = moveNumber,
      principalVariation = pv
    )
  }

  def createParsedGame(playerColor: Color): ParsedGame = {
    ParsedGame(
      gameIdentifier = "test-game",
      playerColorInGame = playerColor,
      allPositions = Seq(
        (Vector.empty, Sfen("lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN b - 1")),
        (Vector(Usi("7g7f").get), Sfen("lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN w - 1"))
      ),
      sentePlayerName = Some("Sente"),
      gotePlayerName = Some("Gote")
    )
  }

  "PuzzleExtractor.extract" should "extract a puzzle when a significant blunder occurs" in {
    val playerColor = sente
    val game = createParsedGame(playerColor)

    // Position 1: Sente to move, equal position
    val res1 = createAnalysisResult(1, "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN b - 1", CpScore(0))
    
    // Position 2: Gote to move, Sente blundered
    // res2 is from perspective of side to move (Gote)
    val res2 = createAnalysisResult(2, "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN w - 1", CpScore(1000), history = Vector(Usi("7g7f").get))

    val shallowResults = Seq(res1, res2)

    // Deep analysis for res1 should find a better move
    // Result of deep analysis for res1 is from perspective of side to move (Sente)
    // Best move should lead to an equal position (CpScore(0))
    val bestMoveResult = createAnalysisResult(1, "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN w - 1", CpScore(0), history = Vector(Usi("2g2f").get), pv = Some("2g2f"))
    
    stubAnalyzer.nextDeepAnalysisResult = Seq(bestMoveResult)

    val puzzles = extractor.extract(game, shallowResults, winChanceDropThreshold = 0.1)

    puzzles should have size 1
    puzzles.head.moveNumber shouldBe 1
    puzzles.head.playerColorName shouldBe "sente"
  }

  it should "not extract a puzzle when win chance drop is below threshold" in {
    val playerColor = sente
    val game = createParsedGame(playerColor)
    stubAnalyzer.deepAnalysisCalled = false

    // Small drop: 0 to -10
    val res1 = createAnalysisResult(1, "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN b - 1", CpScore(0))
    val res2 = createAnalysisResult(2, "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN w - 1", CpScore(10))

    val shallowResults = Seq(res1, res2)

    val puzzles = extractor.extract(game, shallowResults, winChanceDropThreshold = 0.5)

    puzzles should be (empty)
    stubAnalyzer.deepAnalysisCalled shouldBe false
  }

  it should "not extract puzzles for moves not belonging to the player" in {
    val playerColor = gote
    val game = createParsedGame(playerColor)

    // Sente's turn, but player is Gote. 
    val res1 = createAnalysisResult(1, "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN b - 1", CpScore(0))
    val res2 = createAnalysisResult(2, "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN w - 1", CpScore(1000))

    val shallowResults = Seq(res1, res2)

    val puzzles = extractor.extract(game, shallowResults, winChanceDropThreshold = 0.1)

    puzzles should be (empty)
  }

  it should "skip terminal mate positions" in {
    val playerColor = sente
    val game = createParsedGame(playerColor)

    val res1 = createAnalysisResult(1, "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN b - 1", CpScore(0))
    // Position after blunder is Mate in 0 (already mated)
    val res2 = createAnalysisResult(2, "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN w - 1", MateScore(0))

    val shallowResults = Seq(res1, res2)

    val puzzles = extractor.extract(game, shallowResults, winChanceDropThreshold = 0.1)

    puzzles should be (empty)
  }
  
  it should "not extract a puzzle if deep analysis shows no significant improvement" in {
    val playerColor = sente
    val game = createParsedGame(playerColor)

    // Position 1: Sente to move, equal position
    val res1 = createAnalysisResult(1, "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN b - 1", CpScore(0))
    
    // Position 2: Gote to move, Sente blundered
    val res2 = createAnalysisResult(2, "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN w - 1", CpScore(1000), history = Vector(Usi("7g7f").get))

    val shallowResults = Seq(res1, res2)

    // Deep analysis shows that the "best" move is actually also bad
    val bestMoveResult = createAnalysisResult(1, "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN w - 1", CpScore(950), history = Vector(Usi("7g7f").get), pv = Some("7g7f"))
    
    stubAnalyzer.nextDeepAnalysisResult = Seq(bestMoveResult)

    val puzzles = extractor.extract(game, shallowResults, winChanceDropThreshold = 0.3)

    puzzles should be (empty)
  }
}
