package shogi.puzzler.engine

import org.slf4j.LoggerFactory

/* ===========================
   ENGINE MANAGEMENT

   Handles communication with the Shogi engine (YaneuraOu).
   Manages initialization, configuration, and position analysis.
   =========================== */

/**
 * Manages the chess engine lifecycle and analysis requests.
 *
 * Business Logic:
 * - Singleton engine instance per manager
 * - Must be initialized before use
 * - Supports multi-PV analysis for finding alternative moves
 */
class EngineManager(engineCommandPath: Seq[String]) {
  private val logger = LoggerFactory.getLogger(getClass)
  protected def createEngine(command: Seq[String]): YaneuraOu = {
    val file = new java.io.File(command.head)
    logger.info(s"[ENGINE] Creating engine from path: ${command.head} (exists: ${file.exists()}, absolute: ${file.isAbsolute})")
    if (file.isAbsolute && file.exists()) {
      val cwd = Some(file.getParentFile)
      val cmd = file.getAbsolutePath +: command.tail
      logger.info(s"[ENGINE] Using absolute path: ${file.getAbsolutePath} in CWD: ${cwd.get.getAbsolutePath}")
      new YaneuraOu(cmd, cwd)
    } else if (file.exists()) {
      val absoluteFile = file.getAbsoluteFile
      val cwd = Some(absoluteFile.getParentFile)
      val cmd = absoluteFile.getAbsolutePath +: command.tail
      logger.info(s"[ENGINE] Using resolved absolute path: ${absoluteFile.getAbsolutePath} in CWD: ${cwd.get.getAbsolutePath}")
      new YaneuraOu(cmd, cwd)
    } else {
      // For Linux/Windows, check if it's in the current directory or 'engine/' directory
      val engineInDir = new java.io.File("engine", command.head)
      val engineRelative = if (command.head.startsWith("engine/")) {
        new java.io.File(command.head.stripPrefix("engine/"))
      } else {
        new java.io.File("engine", command.head)
      }

      if (engineInDir.exists()) {
        val absoluteFile = engineInDir.getAbsoluteFile
        val cwd = Some(absoluteFile.getParentFile)
        val cmd = absoluteFile.getAbsolutePath +: command.tail
        logger.info(s"[ENGINE] Found engine in engine/ subdir: ${absoluteFile.getAbsolutePath}")
        new YaneuraOu(cmd, cwd)
      } else if (engineRelative.exists()) {
        val absoluteFile = engineRelative.getAbsoluteFile
        val cwd = Some(absoluteFile.getParentFile)
        val cmd = absoluteFile.getAbsolutePath +: command.tail
        logger.info(s"[ENGINE] Found engine relative to root: ${absoluteFile.getAbsolutePath}")
        new YaneuraOu(cmd, cwd)
      } else {
        logger.warn(s"[ENGINE] Engine file not found: ${command.head} (also tried ${engineInDir.getPath} and ${engineRelative.getPath}). Falling back to raw command.")
        new YaneuraOu(command, None)
      }
    }
  }

  private val shogiEngine = if (engineCommandPath.nonEmpty) createEngine(engineCommandPath) else null

  private var isInitialized = false

  /**
   * Initialize the engine with computational resources.
   *
   * @param threadCount Number of CPU threads to use
   * @param memoryMegabytes Hash table size in MB
   */
  def initialize(threadCount: Int = Runtime.getRuntime.availableProcessors(),
                 memoryMegabytes: Int = 256): Unit = {
    logger.info(s"[ENGINE] Initializing engine with $threadCount threads and ${memoryMegabytes}MB memory")
    shogiEngine.startEngine(threadCount, memoryMegabytes)
    isInitialized = true
    logger.info("[ENGINE] Engine initialization complete")
  }

  /**
   * Analyze a position and return top moves.
   *
   * @param sfenPosition Position in SFEN notation
   * @param searchLimit Time or depth limit for analysis
   * @param multiPvCount Number of best moves to return (1 = best only)
   * @return List of analysis results, one per requested line
   */
  def analyze(sfenPosition: String, searchLimit: Limit, multiPvCount: Int = 1): Seq[Map[String, Any]] = {
    if (!isInitialized) {
      throw new IllegalStateException("Engine must be initialized before analysis")
    }

//    println(s"[ENGINE] Analyzing position with MultiPV=$multiPvCount, limit=$searchLimit")
    val results = shogiEngine.analyze(sfenPosition, searchLimit, multiPvCount)
//    println(s"[ENGINE] Analysis complete, found ${results.size} lines")
    results
  }
}