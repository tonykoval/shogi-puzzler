package shogi.puzzler.model

import shogi.puzzler.db.TrainingRepository

import java.io.{ByteArrayInputStream, File}
import java.util.Base64
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration._

object ExportTrainingData {
  def main(args: Array[String]): Unit = {
    val targetDir = new File("model/training_data")
    if (!targetDir.exists()) targetDir.mkdirs()

    println("Fetching training pieces from database...")
    val piecesFuture = TrainingRepository.getAllPieces()
    val pieces = Await.result(piecesFuture, 30.seconds)
    println(s"Found ${pieces.size} pieces.")

    pieces.zipWithIndex.foreach { case (piece, index) =>
      try {
        val label = if (piece.isGote) s"gote_${piece.label}" else s"sente_${piece.label}"
        val labelDir = new File(targetDir, label)
        if (!labelDir.exists()) labelDir.mkdirs()

        val imageData = if (piece.image_data.contains(",")) {
          piece.image_data.split(",")(1)
        } else {
          piece.image_data
        }

        val bytes = Base64.getDecoder.decode(imageData)
        val bis = new ByteArrayInputStream(bytes)
        val image = ImageIO.read(bis)
        
        val outFile = new File(labelDir, s"piece_${piece.id}_$index.png")
        ImageIO.write(image, "png", outFile)
        
        if (index % 100 == 0) println(s"Exported $index pieces...")
      } catch {
        case e: Exception =>
          println(s"Error exporting piece ${piece.id}: ${e.getMessage}")
      }
    }

    println("Export finished.")
    System.exit(0)
  }
}
