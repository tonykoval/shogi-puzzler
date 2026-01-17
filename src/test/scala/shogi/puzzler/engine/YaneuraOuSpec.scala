package shogi.puzzler.engine

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io._

class YaneuraOuSpec extends AnyFlatSpec with Matchers {

  "YaneuraOu" should "send 'usi' and parse response" in {
    val in = new PipedInputStream()
    val out = new PipedOutputStream()
    val engineIn = new PipedInputStream(out)
    val engineOut = new PipedOutputStream(in)

    val yaneuraOu = new YaneuraOu(Nil, None, Some(engineIn), Some(engineOut))

    val engineThread = new Thread(() => {
      val reader = new BufferedReader(new InputStreamReader(in))
      val writer = new BufferedWriter(new OutputStreamWriter(out))
      
      val line = reader.readLine()
      if (line == "usi") {
        writer.write("id name YaneuraOu\n")
        writer.write("id author yaneurao\n")
        writer.write("usiok\n")
        writer.flush()
      }
    })
    engineThread.start()

    val info = yaneuraOu.usi()
    info should contain("name" -> "YaneuraOu")
    info should contain("author" -> "yaneurao")
    
    engineThread.join(1000)
  }

  it should "handle isready" in {
    val in = new PipedInputStream()
    val out = new PipedOutputStream()
    val engineIn = new PipedInputStream(out)
    val engineOut = new PipedOutputStream(in)

    val yaneuraOu = new YaneuraOu(Nil, None, Some(engineIn), Some(engineOut))

    val engineThread = new Thread(() => {
      val reader = new BufferedReader(new InputStreamReader(in))
      val writer = new BufferedWriter(new OutputStreamWriter(out))
      
      reader.readLine() // stop
      val line = reader.readLine()
      if (line == "isready") {
        writer.write("readyok\n")
        writer.flush()
      }
    })
    engineThread.start()

    yaneuraOu.isReady()
    
    engineThread.join(1000)
  }

  it should "analyze a position" in {
    val in = new PipedInputStream()
    val out = new PipedOutputStream()
    val engineIn = new PipedInputStream(out)
    val engineOut = new PipedOutputStream(in)

    val yaneuraOu = new YaneuraOu(Nil, None, Some(engineIn), Some(engineOut))

    val engineThread = new Thread(() => {
      val reader = new BufferedReader(new InputStreamReader(in))
      val writer = new BufferedWriter(new OutputStreamWriter(out))
      
      reader.readLine() // stop
      reader.readLine() // isready
      writer.write("readyok\n")
      writer.flush()
      
      reader.readLine() // position sfen ...
      val go = reader.readLine()
      if (go.startsWith("go")) {
        writer.write("info multipv 1 score cp 123 nps 1000 pv 7g7f\n")
        writer.write("bestmove 7g7f\n")
        writer.flush()
      }
    })
    engineThread.start()

    val results = yaneuraOu.analyze("startpos", Limit(time = Some(1)))
    
    results should have size 1
    results.head("score") shouldBe ("cp", 123)
    results.head("nps") shouldBe 1000
    results.head("pv") shouldBe List("7g7f")

    engineThread.join(1000)
  }

  it should "handle multiPV analysis" in {
    val in = new PipedInputStream()
    val out = new PipedOutputStream()
    val engineIn = new PipedInputStream(out)
    val engineOut = new PipedOutputStream(in)

    val yaneuraOu = new YaneuraOu(Nil, None, Some(engineIn), Some(engineOut))

    val engineThread = new Thread(() => {
      val reader = new BufferedReader(new InputStreamReader(in))
      val writer = new BufferedWriter(new OutputStreamWriter(out))
      
      reader.readLine() // setoption name MultiPV value 2
      reader.readLine() // stop
      reader.readLine() // isready
      writer.write("readyok\n")
      writer.flush()
      
      reader.readLine() // position sfen ...
      reader.readLine() // go ...
      
      writer.write("info multipv 1 score cp 100 nps 1000 pv 7g7f\n")
      writer.write("info multipv 2 score cp 50 nps 1000 pv 2g2f\n")
      writer.write("bestmove 7g7f\n")
      writer.flush()
    })
    engineThread.start()

    val results = yaneuraOu.analyze("startpos", Limit(time = Some(1)), multiPv = 2)
    
    results should have size 2
    results(0)("score") shouldBe ("cp", 100)
    results(1)("score") shouldBe ("cp", 50)

    engineThread.join(1000)
  }

  it should "handle mate scores" in {
    val in = new PipedInputStream()
    val out = new PipedOutputStream()
    val engineIn = new PipedInputStream(out)
    val engineOut = new PipedOutputStream(in)

    val yaneuraOu = new YaneuraOu(Nil, None, Some(engineIn), Some(engineOut))

    val engineThread = new Thread(() => {
      val reader = new BufferedReader(new InputStreamReader(in))
      val writer = new BufferedWriter(new OutputStreamWriter(out))
      
      reader.readLine() // stop
      reader.readLine() // isready
      writer.write("readyok\n")
      writer.flush()
      
      reader.readLine() // position sfen ...
      reader.readLine() // go ...
      
      writer.write("info multipv 1 score mate 5 nps 1000 pv 7g7f\n")
      writer.write("bestmove 7g7f\n")
      writer.flush()
    })
    engineThread.start()

    val results = yaneuraOu.analyze("startpos", Limit(time = Some(1)))
    
    results.head("score") shouldBe ("mate", 5)

    engineThread.join(1000)
  }
}
