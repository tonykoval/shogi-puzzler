package shogi.puzzler.engine

import java.io._
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable
import org.slf4j.LoggerFactory

case class Limit(
                  time: Option[Int] = None,
                  depth: Option[Int] = None,
                  nodes: Option[Int] = None,
                  mate: Option[Int] = None,
                  senteClock: Option[Double] = None,
                  goteClock: Option[Double] = None,
                  senteInc: Option[Double] = None,
                  goteInc: Option[Double] = None,
                  byoyomi: Option[Double] = None,
                  remainingMoves: Option[Int] = None
                )

class YaneuraOu(
                 command: Seq[String],
                 cwd: Option[File] = None,
                 inputStream: Option[InputStream] = None,
                 outputStream: Option[OutputStream] = None
               ) {
  private val logger = LoggerFactory.getLogger(getClass)

  private val lock = new ReentrantLock()

  private val process: Option[Process] = if (inputStream.isEmpty || outputStream.isEmpty) {
    val pb = new ProcessBuilder(command: _*)
      .redirectErrorStream(true)

    cwd.foreach(pb.directory)

    Some(pb.start())
  } else None

  private val in =
    new BufferedReader(new InputStreamReader(inputStream.getOrElse(process.get.getInputStream)))
  private val out =
    new BufferedWriter(new OutputStreamWriter(outputStream.getOrElse(process.get.getOutputStream)))

  private var multipv = 1

  private def send(cmd: String): Unit = {
    out.write(cmd)
    out.newLine()
    out.flush()
  }

  private def recv(): String = {
    val line = in.readLine()
    if (line == null) throw new EOFException()
    line.trim
  }

  private def recvUSI(): (String, String) = {
    val parts = recv().split("\\s+", 2)
    if (parts.length == 1) (parts(0), "")
    else (parts(0), parts(1))
  }

  // ---------------- USI lifecycle ----------------

  def usi(): Map[String, String] = {
    send("usi")
    val info = mutable.Map.empty[String, String]

    while (true) {
      val (cmd, arg) = recvUSI()
      cmd match {
        case "usiok" => return info.toMap
        case "id" =>
          val p = arg.split("\\s+", 2)
          if (p.length == 2) info += (p(0) -> p(1))
        case "option" => // ignore
        case other =>
          logger.warn(s"Unexpected usi response: $other $arg")
      }
    }
    Map.empty
  }

  def isReady(): Unit = {
    send("stop")
    send("isready")
    while (true) {
      val (cmd, arg) = recvUSI()
      cmd match {
        case "readyok" => return
        case "info" if arg.startsWith("string") => ()
        case _ => logger.warn(s"Unexpected isready response: $cmd $arg")
      }
    }
  }

  def setOption(name: String, value: Any): Unit = {
    val v = value match {
      case true  => "true"
      case false => "false"
      case None  => "none"
      case x     => x.toString
    }
    send(s"setoption name $name value $v")
  }

  def startEngine(threads: Int = 4, memory: Int = 1024): Unit = {
    usi()
    setOption("Threads", threads)
    setOption("USI_Hash", memory)
    setOption("BookFile", "no_book")
    setOption("ConsiderationMode", true)
    setOption("OutputFailLHPV", true)
    isReady()
  }
  // ---------------- ANALYZE ----------------

  def analyze(
               sfen: String,
               limit: Limit = Limit(),
               multiPv: Int = 1
             ): Vector[Map[String, Any]] = {

    lock.lock()
    try {
      if (multiPv != multipv) {
        multipv = multiPv
        setOption("MultiPV", multiPv)
      }

      isReady()
      send(s"position sfen $sfen")

      val go = mutable.ListBuffer("go")

      limit.depth.foreach(d => go ++= Seq("depth", d.toString))
      limit.nodes.foreach(n => go ++= Seq("nodes", n.toString))
      limit.time.foreach(t => go ++= Seq("movetime", (t * 1000).toString))
      limit.byoyomi.foreach(b => go ++= Seq("byoyomi", (b * 1000).toInt.toString))

      send(go.mkString(" "))

      val info = Vector.fill(multiPv)(mutable.Map[String, Any]())

      while (true) {
        val (cmd, arg) = recvUSI()
        cmd match {
          case "bestmove" =>
            return info.map(_.toMap)

          case "info" =>
            parseInfo(arg, info)

          case _ =>
            logger.warn(s"Unexpected go response: $cmd $arg")
        }
      }

      Vector.empty
    } finally {
      lock.unlock()
    }
  }

  def analyzeWithMoves(
               sfen: String,
               moves: Seq[String],
               limit: Limit = Limit(),
               multiPv: Int =1
             ): Vector[Map[String, Any]] = {

    lock.lock()
    try {
      if (multiPv != multipv) {
        multipv = multiPv
        setOption("MultiPV", multiPv)
      }

      isReady()
      
      // Send position with move history
      val positionCmd = if (moves.isEmpty) {
        s"position sfen $sfen"
      } else {
        s"position sfen $sfen moves ${moves.mkString(" ")}"
      }
      send(positionCmd)

      val go = mutable.ListBuffer("go")

      limit.depth.foreach(d => go ++= Seq("depth", d.toString))
      limit.nodes.foreach(n => go ++= Seq("nodes", n.toString))
      limit.time.foreach(t => go ++= Seq("movetime", (t * 1000).toString))
      limit.byoyomi.foreach(b => go ++= Seq("byoyomi", (b * 1000).toInt.toString))

      send(go.mkString(" "))

      val info = Vector.fill(multiPv)(mutable.Map[String, Any]())

      while (true) {
        val (cmd, arg) = recvUSI()
        cmd match {
          case "bestmove" =>
            return info.map(_.toMap)

          case "info" =>
            parseInfo(arg, info)

          case _ =>
            logger.warn(s"Unexpected go response: $cmd $arg")
        }
      }

      Vector.empty
    } finally {
      lock.unlock()
    }
  }

  private def parseInfo(
                         arg: String,
                         info: Vector[mutable.Map[String, Any]]
                       ): Unit = {

    var currentPv = 1
    var scoreKind: Option[String] = None
    var scoreValue: Option[Int] = None
    var nps = 0
    val pv = mutable.ListBuffer[String]()

    val tokens = arg.split("\\s+")
    var i = 0
    while (i < tokens.length) {
      tokens(i) match {
        case "multipv" =>
          currentPv = tokens(i + 1).toInt
          i += 1
        case "nps" =>
          nps = tokens(i + 1).toInt
          i += 1
        case "score" =>
          scoreKind = Some(tokens(i + 1))
          scoreValue = Some(tokens(i + 2).toInt)
          i += 2
        case "pv" =>
          pv ++= tokens.drop(i + 1)
          i = tokens.length
        case _ =>
      }
      i += 1
    }

    val entry = info(currentPv - 1)
    entry("pv") = pv.toList
    entry("nps") = nps
    scoreValue.foreach(v => entry("score") = (scoreKind.get, v))
  }

  def quit(): Unit = {
    process.foreach(_.destroyForcibly())
  }
}
