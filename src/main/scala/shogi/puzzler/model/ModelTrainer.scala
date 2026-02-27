package shogi.puzzler.model

import net.sourceforge.tess4j.util.ImageHelper
import org.slf4j.LoggerFactory
import shogi.puzzler.db.TrainingRepository

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object ModelTrainer {
  private val logger = LoggerFactory.getLogger(getClass)

  case class TrainedModel(templates: Map[String, Seq[Array[Int]]]) {
    def predict(img: BufferedImage): String = {
      val features = extractFeatures(img)
      templates.map { case (label, examples) =>
        val minDistance = examples.map(dist(_, features)).min
        label -> minDistance
      }.minBy(_._2)._1
    }

    private def dist(a: Array[Int], b: Array[Int]): Double = {
      math.sqrt(a.zip(b).map { case (x, y) => math.pow(x - y, 2).toDouble }.sum)
    }
  }

  def train(): Future[TrainedModel] = {
    TrainingRepository.getAllPieces().map { pieces =>
      val data: Seq[(String, Array[Int])] = pieces.flatMap { p =>
        try {
          val img = decodeBase64(p.image_data)
          if (img != null) List((p.label, extractFeatures(img))) else Nil
        } catch {
          case e: Exception =>
            logger.error(s"Failed to process training piece ${p.id}: ${e.getMessage}")
            Nil
        }
      }

      val templates: Map[String, Seq[Array[Int]]] = data.groupBy(_._1).view.mapValues(_.map(_._2)).toSeq.toMap
      logger.info(s"Trained model with ${templates.size} classes and ${data.size} total examples")
      TrainedModel(templates)
    }
  }

  def decodeBase64(base64Data: String): BufferedImage = {
    val bytes = Base64.getDecoder.decode(base64Data)
    ImageIO.read(new ByteArrayInputStream(bytes))
  }

  def extractFeatures(img: BufferedImage): Array[Int] = {
    val scaled = ImageHelper.getScaledInstance(img, 32, 32)
    val gray = ImageHelper.convertImageToGrayscale(scaled)
    
    val features = new Array[Int](32 * 32)
    for (y <- 0 until 32) {
      for (x <- 0 until 32) {
        features(y * 32 + x) = gray.getRGB(x, y) & 0xFF
      }
    }
    features
  }

  def main(args: Array[String]): Unit = {
    println("Starting model training...")
    try {
      val model = Await.result(train(), 60.seconds)
      println(s"Model trained. Labels: ${model.templates.keys.mkString(", ")}")
      
      if (model.templates.isEmpty) {
        println("Warning: No training data found in database!")
      } else {
        println(s"Total examples: ${model.templates.values.map(_.size).sum}")
      }
    } catch {
      case e: Exception =>
        println(s"Training failed: ${e.getMessage}")
        e.printStackTrace()
    } finally {
      // MongoDB connection pool might keep the app running, but in a simple main it's okay for now
      System.exit(0)
    }
  }
}
