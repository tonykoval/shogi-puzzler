package shogi.puzzler.model

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import scala.collection.mutable

object PieceClassifierTrainer {

  case class FeatureVector(data: Array[Double])

  def extractFeatures(img: BufferedImage): FeatureVector = {

    val scaled = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY)
    val g = scaled.createGraphics()
    g.drawImage(img, 0, 0, 32, 32, null)
    g.dispose()

    val features = new Array[Double](32 * 32)
    for (y <- 0 until 32) {
      for (x <- 0 until 32) {
        val gray = scaled.getRGB(x, y) & 0xFF
        features(y * 32 + x) = gray.toDouble / 255.0
      }
    }
    FeatureVector(features)
  }

  def main(args: Array[String]): Unit = {
    val dataDir = new File("model/training_data")
    if (!dataDir.exists()) {
      println("Training data not found. Run ExportTrainingData first.")
      return
    }

    val samples = mutable.ArrayBuffer[(FeatureVector, String)]()

    println("Loading images and extracting features...")
    dataDir.listFiles().filter(_.isDirectory).foreach { labelDir =>
      val label = labelDir.getName
      labelDir.listFiles().filter(_.getName.endsWith(".png")).foreach { imgFile =>
        val img = ImageIO.read(imgFile)
        if (img != null) {
          samples += (extractFeatures(img) -> label)
        }
      }
    }

    if (samples.isEmpty) {
      println("No samples found.")
      return
    }

    println(s"Loaded ${samples.size} samples.")

    val centroids = samples.groupBy(_._2).map { case (label, labelSamples) =>
      val sumFeatures = new Array[Double](32 * 32)
      labelSamples.foreach { case (fv, _) =>
        for (i <- fv.data.indices) {
          sumFeatures(i) += fv.data(i)
        }
      }
      val avgFeatures = sumFeatures.map(_ / labelSamples.size)
      label -> FeatureVector(avgFeatures)
    }

    saveModel(centroids, new File("model/piece_model.txt"))
    println("Model saved to model/piece_model.txt")
  }

  def saveModel(centroids: Map[String, FeatureVector], file: File): Unit = {
    val parent = file.getParentFile
    if (parent != null && !parent.exists()) parent.mkdirs()
    
    val writer = new java.io.PrintWriter(file)
    centroids.foreach { case (label, fv) =>
      writer.println(s"$label:${fv.data.mkString(",")}")
    }
    writer.close()
  }

  def loadModel(file: File): Map[String, FeatureVector] = {
    val source = scala.io.Source.fromFile(file)
    val centroids = source.getLines().map { line =>
      val parts = line.split(":")
      val label = parts(0)
      val data = parts(1).split(",").map(_.toDouble)
      label -> FeatureVector(data)
    }.toMap
    source.close()
    centroids
  }

  def classify(img: BufferedImage, model: Map[String, FeatureVector]): String = {
    val features = extractFeatures(img)

    centroidsDistance(features, model).minBy(_._2)._1
  }

  def centroidsDistance(features: FeatureVector, model: Map[String, FeatureVector]): Map[String, Double] = {
    model.map { case (label, centroid) =>
      val dist = Math.sqrt(features.data.zip(centroid.data).map { case (a, b) => Math.pow(a - b, 2) }.sum)
      label -> dist
    }
  }
}
