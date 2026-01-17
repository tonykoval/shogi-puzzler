package shogi.puzzler.analysis

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import shogi.Color
import shogi.format.forsyth.Sfen
import shogi.format.usi.Usi
import shogi.puzzler.domain.{ParsedGame, CpScore, MateScore}
import shogi.puzzler.engine.{EngineManager, Limit}

class GameAnalyzerSpec extends AnyFlatSpec with Matchers {

  def createParsedGame(playerColor: Color): ParsedGame = {
    ParsedGame(
      gameIdentifier = "test-game",
      playerColorInGame = playerColor,
      allPositions = Seq(
        (Vector.empty, Sfen("lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN b - 1")),
        (Vector(Usi("7g7f").get), Sfen("lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN w - 1"))
      )
    )
  }

  "GameAnalyzer.analyzeShallow" should "analyze all positions in a game" in {
    val mockEngine = new EngineManager(Nil) {
      var analyzeCalled = false
      var nextResults: Seq[Map[String, Any]] = Seq.empty

      override def initialize(threadCount: Int, memoryMegabytes: Int): Unit = {}

      override def analyze(sfenPosition: String, searchLimit: Limit, multiPvCount: Int): Seq[Map[String, Any]] = {
        analyzeCalled = true
        nextResults
      }
    }

    val analyzer = new GameAnalyzer(mockEngine)
    val game = createParsedGame(Color.Sente)

    mockEngine.nextResults = Seq(Map(
      "score" -> ("cp", 10),
      "pv" -> List("7g7f"),
      "nps" -> 1000
    ))

    val results = analyzer.analyzeShallow(game, secondsPerMove = 1)

    results should have size 2
    mockEngine.analyzeCalled shouldBe true
    results.head.moveNumber shouldBe 1
    results.head.evaluationScore.relativeScore shouldBe CpScore(10)
    results.last.moveNumber shouldBe 2
  }

  "GameAnalyzer.analyzeDeep" should "analyze a single position with multiPV" in {
    val mockEngine = new EngineManager(Nil) {
      var lastMultiPvCount: Int = 1
      var lastLimit: Limit = Limit()
      var nextResults: Seq[Map[String, Any]] = Seq.empty

      override def initialize(threadCount: Int, memoryMegabytes: Int): Unit = {}

      override def analyze(sfenPosition: String, searchLimit: Limit, multiPvCount: Int): Seq[Map[String, Any]] = {
        lastLimit = searchLimit
        lastMultiPvCount = multiPvCount
        nextResults
      }
    }

    val analyzer = new GameAnalyzer(mockEngine)
    val sfen = "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN b - 1"

    mockEngine.nextResults = Seq(
      Map("score" -> ("cp", 50), "pv" -> List("7g7f")),
      Map("score" -> ("cp", 20), "pv" -> List("2g2f")),
      Map("score" -> ("cp", 10), "pv" -> List("3g3f"))
    )

    val results = analyzer.analyzeDeep(sfen, Vector.empty, moveNumber = 1, analysisSeconds = 5)

    mockEngine.lastMultiPvCount shouldBe 3
    mockEngine.lastLimit.time shouldBe Some(5)
    results should have size 3
    results.head.evaluationScore.relativeScore shouldBe CpScore(50)
    results(1).evaluationScore.relativeScore shouldBe CpScore(20)
    results(2).evaluationScore.relativeScore shouldBe CpScore(10)
  }

  it should "handle mate scores correctly" in {
    val mockEngine = new EngineManager(Nil) {
      var nextResults: Seq[Map[String, Any]] = Seq.empty

      override def initialize(threadCount: Int, memoryMegabytes: Int): Unit = {}

      override def analyze(sfenPosition: String, searchLimit: Limit, multiPvCount: Int): Seq[Map[String, Any]] = {
        nextResults
      }
    }

    val analyzer = new GameAnalyzer(mockEngine)
    val sfen = "lnsgkgns1/1r5b1/ppppppppp/9/9/9/PPPPPPPPP/1B5R1/LNSGKGN b - 1"

    mockEngine.nextResults = Seq(
      Map("score" -> ("mate", 5), "pv" -> List("7g7f"))
    )

    val results = analyzer.analyzeDeep(sfen, Vector.empty, moveNumber = 10)

    results.head.evaluationScore.relativeScore shouldBe MateScore(5)
  }
}
