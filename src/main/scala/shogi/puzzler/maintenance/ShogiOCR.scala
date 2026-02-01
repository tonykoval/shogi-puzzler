package shogi.puzzler.maintenance

import net.sourceforge.tess4j.util.ImageHelper
import net.sourceforge.tess4j.Tesseract

import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import org.slf4j.LoggerFactory
import shogi.puzzler.model.PieceClassifierTrainer

case class Rect(x: Int, y: Int, width: Int, height: Int)

object ShogiOCR {
  private val logger = LoggerFactory.getLogger(getClass)

  private val tesseractInstance = {
    val t = new Tesseract()
    val tessDataPath = new File("tessdata")
    if (tessDataPath.exists()) {
      t.setDatapath(tessDataPath.getAbsolutePath)
    }
    t.setLanguage("jpn")
    t
  }

  private val pieceMap = Map(
    "王" -> "K", "玉" -> "K",
    "歩" -> "P", "と" -> "+P",
    "香" -> "L", "杏" -> "+L",
    "桂" -> "N", "圭" -> "+N",
    "銀" -> "S", "全" -> "+S",
    "金" -> "G",
    "角" -> "B", "馬" -> "+B",
    "飛" -> "R", "龍" -> "+R", "竜" -> "+R"
  )

  private def normalizePieceName(name: String): String = {
    val n = name.toLowerCase
    if (n.startsWith("gote_")) n.substring(5)
    else if (n.startsWith("sente_")) n.substring(6)
    else n
  }

  def extractSfen(file: File, boardRect: Option[Rect] = None): String = {
    val img = ImageIO.read(file)
    extractSfen(img, boardRect)
  }

  def extractSfen(image: BufferedImage, boardRect: Option[Rect]): String = {
    extractSfenFromRegions(image, boardRect.getOrElse(detectDefaultRegions()("board")), None, None)
  }

  def extractSfen(image: BufferedImage, boardRect: Rect, senteRect: Option[Rect], goteRect: Option[Rect]): String = {
    extractSfenFromRegions(image, boardRect, senteRect, goteRect)
  }

  def extractSfenFromRegions(image: BufferedImage, boardRect: Rect, senteRect: Option[Rect], goteRect: Option[Rect]): String = {
    val board = image.getSubimage(boardRect.x, boardRect.y, boardRect.width, boardRect.height)
    val squares = splitBoardIntoSquares(board)
    
    val sfenRows = for (rank <- 0 until 9) yield {
      var emptyCount = 0
      val row = new StringBuilder
      for (fileIdx <- (0 until 9).reverse) {
        val square = squares(rank)(8 - fileIdx)
        val shogiFile = fileIdx + 1
        val shogiRank = rank + 1
        val coords = s"${shogiFile}${('a' + rank).toChar}"
        val pieceResult = identifyPiece(square, coords, "all")
        pieceResult match {
          case Some((p, _, _)) =>
            if (p.nonEmpty) {
              if (emptyCount > 0) {
                row.append(emptyCount)
                emptyCount = 0
              }
              row.append(p)
            } else {
              emptyCount += 1
            }
          case None =>
            emptyCount += 1
        }
      }
      if (emptyCount > 0) row.append(emptyCount)
      row.toString()
    }
    
    val boardSfen = sfenRows.mkString("/")
    
    s"$boardSfen b - 1"
  }

  private val modelFile = new File("model/piece_model.txt")
  private val trainedModel: Map[String, PieceClassifierTrainer.FeatureVector] = if (modelFile.exists()) {
    try {
      PieceClassifierTrainer.loadModel(modelFile)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to load trained model: ${e.getMessage}")
        Map.empty
    }
  } else {
    Map.empty
  }

  def detectDefaultRegions(): Map[String, Rect] = {
    Map(
      "board" -> Rect(19, 377, 1010, 1103),
      "sente" -> Rect(0, 1495, 1080, 136),
      "gote" -> Rect(372, 203, 707, 124)
    )
  }

  def identifyPiece(square: BufferedImage, coords: String = "", mode: String = "all"): Option[(String, String, Boolean)] = {
    val width = square.getWidth
    val height = square.getHeight

    val marginW = width / 6
    val marginH = height / 6
    val subImage = square.getSubimage(marginW, marginH, width - 2 * marginW, height - 2 * marginH)

    val totalCheckedPixels = subImage.getWidth * subImage.getHeight
    var darkPixels = 0
    var sumY = 0
    val threshold = 120
    for (y <- 0 until subImage.getHeight) {
      for (x <- 0 until subImage.getWidth) {
        val color = subImage.getRGB(x, y)
        val r = (color >> 16) & 0xFF
        val g = (color >> 8) & 0xFF
        val b = color & 0xFF
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        if (luminance < threshold) {
          darkPixels += 1
          sumY += y
        }
      }
    }
    
    val ratio = darkPixels.toDouble / totalCheckedPixels
    if (ratio < 0.03) {
      return None
    }

    val centerY = if (darkPixels > 0) sumY.toDouble / darkPixels else 0.0
    val isGoteHeuristic = centerY < subImage.getHeight * 0.45

    def rotate180(img: BufferedImage): BufferedImage = {
      val rotated = new BufferedImage(img.getWidth, img.getHeight, img.getType)
      val g = rotated.createGraphics()
      g.rotate(Math.toRadians(180), img.getWidth / 2.0, img.getHeight / 2.0)
      g.drawImage(img, 0, 0, null)
      g.dispose()
      rotated
    }

    if ((mode == "model" || mode == "all") && trainedModel.nonEmpty) {
      val classified = PieceClassifierTrainer.classify(subImage, trainedModel)
      val classifiedRot = PieceClassifierTrainer.classify(rotate180(subImage), trainedModel)
      
      val (piece, score, isGote) = if (isGoteHeuristic) {
         (normalizePieceName(classifiedRot), PieceClassifierTrainer.centroidsDistance(PieceClassifierTrainer.extractFeatures(rotate180(subImage)), trainedModel)(classifiedRot), true)
      } else {
         (normalizePieceName(classified), PieceClassifierTrainer.centroidsDistance(PieceClassifierTrainer.extractFeatures(subImage), trainedModel)(classified), false)
      }

      if (score < 10.0) {
        logger.info(s"[MODEL] $coords classified as $piece (score: $score, isGote: $isGote)")
        return Some((piece, "Model", isGote))
      } else {
        logger.debug(s"[MODEL] $coords classification uncertain: $piece (score: $score)")
      }
    }

    Some(("", "None", isGoteHeuristic))
  }

  def splitScreenshot(img: BufferedImage): (BufferedImage, BufferedImage, BufferedImage) = {
    val regions = detectDefaultRegions()
    val b = regions("board")
    val s = regions("sente")
    val g = regions("gote")

    val board = img.getSubimage(b.x, b.y, b.width, b.height)
    val sente = img.getSubimage(s.x, s.y, s.width, s.height)
    val gote = img.getSubimage(g.x, g.y, g.width, g.height)

    (board, sente, gote)
  }

  def splitBoardIntoSquares(board: BufferedImage): Seq[Seq[BufferedImage]] = {
    val width = board.getWidth
    val height = board.getHeight

    val cellW = width.toDouble / 9
    val cellH = height.toDouble / 9

    for (rank <- 0 until 9) yield {
      for (fileIdx <- 0 until 9) yield {
        val x = (fileIdx * cellW).toInt
        val y = (rank * cellH).toInt
        val w = ((fileIdx + 1) * cellW).toInt - x
        val h = ((rank + 1) * cellH).toInt - y
        board.getSubimage(x, y, w, h)
      }
    }
  }

  def identifyAllSquares(image: BufferedImage, boardRect: Rect, senteRect: Option[Rect] = None, goteRect: Option[Rect] = None, skipOCR: Boolean = false, mode: String = "all"): Seq[Map[String, String]] = {
    val board = image.getSubimage(boardRect.x, boardRect.y, boardRect.width, boardRect.height)
    val boardSquares = splitBoardIntoSquares(board)
    val boardResults = for {
      rank <- 0 until 9
      fileIdx <- 0 until 9
    } yield {
      val square = boardSquares(rank)(fileIdx)
      val shogiFile = 9 - fileIdx
      val shogiRank = rank + 1
      val coords = s"${shogiFile}${('a' + rank).toChar}"
      val pieceResult = if (skipOCR) None else identifyPiece(square, coords, mode)
      
      val baos = new java.io.ByteArrayOutputStream()
      javax.imageio.ImageIO.write(square, "png", baos)
      val base64 = java.util.Base64.getEncoder.encodeToString(baos.toByteArray)
      
      Map(
        "coords" -> coords,
        "piece" -> pieceResult.map(_._1).getOrElse(""),
        "label" -> pieceResult.map(_._2).getOrElse(""),
        "isGote" -> pieceResult.map(_._3).getOrElse(false).toString,
        "count" -> "1",
        "image" -> s"data:image/png;base64,$base64",
        "type" -> "board"
      )
    }

    val senteResults = senteRect.map { r =>
      val handImg = image.getSubimage(r.x, r.y, r.width, r.height)
      identifyHandPieces(handImg, isGote = false, skipOCR = skipOCR, mode = mode)
    }.getOrElse(Nil)

    val goteResults = goteRect.map { r =>
      val handImg = image.getSubimage(r.x, r.y, r.width, r.height)
      identifyHandPieces(handImg, isGote = true, skipOCR = skipOCR, mode = mode)
    }.getOrElse(Nil)

    boardResults ++ senteResults ++ goteResults
  }

  def identifyHandPieces(handImg: BufferedImage, isGote: Boolean, skipOCR: Boolean = false, mode: String = "all"): Seq[Map[String, String]] = {
    // ShogiWars hand region:
    // Sente is at bottom, usually pieces are horizontal.
    // Gote is at top left, pieces are horizontal.
    // This is a simplified segmentation. In ShogiWars, pieces in hand are usually in specific order or just listed.
    // For now, let's split it into 7 possible piece types (P, L, N, S, G, B, R)
    val pieceTypes = Seq("P", "L", "N", "S", "G", "B", "R")
    val count = pieceTypes.size
    val cellW = handImg.getWidth.toDouble / count
    
    for (i <- 0 until count) yield {
      val x = (i * cellW).toInt
      val y = 0
      val w = ((i + 1) * cellW).toInt - x
      val h = handImg.getHeight
      val square = handImg.getSubimage(x, y, w, h)
      
      // Hand pieces in SFEN are same as board pieces but without coordinates in the same way.
      // We can use a virtual coordinate for them like "Sente-P", "Gote-P"
      val pieceType = pieceTypes(i)
      val label = if (isGote) pieceType.toLowerCase else pieceType.toUpperCase
      val coords = (if (isGote) "Gote-" else "Sente-") + pieceType
      
      // Identify if piece is present and its count? 
      // For training/manual tool, we just need to show the area and let user pick.
      // But let's try to identify if something is there.
      val pieceResult = if (skipOCR) None else identifyPiece(square, coords, mode)
      
      // Try to identify count if piece is present
      val pieceCount = if (pieceResult.isDefined && !skipOCR) {
        // Simple OCR for count which is usually a small number near the piece
        try {
          val scaled = ImageHelper.getScaledInstance(square, square.getWidth * 3, square.getHeight * 3)
          val text = synchronized {
            tesseractInstance.doOCR(scaled).trim()
          }
          // Look for numbers in the text
          val numberRegex = """(\d+)""".r
          numberRegex.findFirstIn(text).getOrElse("1")
        } catch {
          case _: Exception => "1"
        }
      } else "0"

      val baos = new java.io.ByteArrayOutputStream()
      javax.imageio.ImageIO.write(square, "png", baos)
      val base64 = java.util.Base64.getEncoder.encodeToString(baos.toByteArray)

      Map(
        "coords" -> coords,
        "piece" -> pieceResult.map(_._1).getOrElse(""), // OCR might find the piece
        "label" -> pieceResult.map(_._2).getOrElse(""),
        "isGote" -> isGote.toString,
        "count" -> pieceCount,
        "image" -> s"data:image/png;base64,$base64",
        "type" -> (if (isGote) "goteHand" else "senteHand")
      )
    }
  }
}
