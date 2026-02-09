package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.bson.Document
import scala.concurrent.Future
import com.typesafe.config.ConfigFactory

object MongoDBConnection {
  private val config = ConfigFactory.load().getConfig("app.mongodb")
  private val connectionString = config.getString("uri")
  private val client: MongoClient = MongoClient(connectionString)
  private val database: MongoDatabase = client.getDatabase(config.getString("database"))
  
  val gamesCollection: MongoCollection[Document] = database.getCollection("games")
  val puzzlesCollection: MongoCollection[Document] = database.getCollection("puzzles")
  val customPuzzlesCollection: MongoCollection[Document] = database.getCollection("custom_puzzles")
  val settingsCollection: MongoCollection[Document] = database.getCollection("settings")
  val repertoireCollection: MongoCollection[Document] = database.getCollection("repertoire")
  val trainingPiecesCollection: MongoCollection[Document] = database.getCollection("training_pieces")
  val trainingHandsCollection: MongoCollection[Document] = database.getCollection("training_hands")
  val ocrHistoryCollection: MongoCollection[Document] = database.getCollection("ocr_history")
  val usersCollection: MongoCollection[Document] = database.getCollection("users")

  println(s"[DB] Connected to database: ${config.getString("database")}")
  ping().foreach(p => println(s"[DB] Ping: $p"))(scala.concurrent.ExecutionContext.global)

  // Create unique index on kif_hash
  gamesCollection.createIndex(
    org.mongodb.scala.model.Indexes.ascending("kif_hash"),
    org.mongodb.scala.model.IndexOptions().unique(true)
  ).toFuture().foreach(_ => println("[DB] Unique index on kif_hash created"))(scala.concurrent.ExecutionContext.global)

  // Create index on game_kif_hash for puzzles collection
  puzzlesCollection.createIndex(
    org.mongodb.scala.model.Indexes.ascending("game_kif_hash")
  ).toFuture().foreach(_ => println("[DB] Index on game_kif_hash for puzzles created"))(scala.concurrent.ExecutionContext.global)

  // Create unique index on image_data for training collections
  trainingPiecesCollection.createIndex(
    org.mongodb.scala.model.Indexes.ascending("image_data"),
    org.mongodb.scala.model.IndexOptions().unique(true)
  ).toFuture().foreach(_ => println("[DB] Unique index on image_data for training_pieces created"))(scala.concurrent.ExecutionContext.global)

  trainingHandsCollection.createIndex(
    org.mongodb.scala.model.Indexes.ascending("image_data"),
    org.mongodb.scala.model.IndexOptions().unique(true)
  ).toFuture().foreach(_ => println("[DB] Unique index on image_data for training_hands created"))(scala.concurrent.ExecutionContext.global)
  
  def ping(): Future[String] = {
    database.runCommand(Document("ping" -> 1)).toFuture().map(_ => "pong")(scala.concurrent.ExecutionContext.global)
  }
}
