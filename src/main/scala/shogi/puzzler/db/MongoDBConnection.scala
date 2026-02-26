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
  val legacyPuzzlesCollection: MongoCollection[Document] = database.getCollection("puzzles")
  val puzzlesCollection: MongoCollection[Document] = database.getCollection("custom_puzzles")
  val settingsCollection: MongoCollection[Document] = database.getCollection("settings")
  val studyCollection: MongoCollection[Document] = database.getCollection("repertoire")
  val trainingPiecesCollection: MongoCollection[Document] = database.getCollection("training_pieces")
  val trainingHandsCollection: MongoCollection[Document] = database.getCollection("training_hands")
  val ocrHistoryCollection: MongoCollection[Document] = database.getCollection("ocr_history")
  val usersCollection: MongoCollection[Document] = database.getCollection("users")
  val srsCardsCollection: MongoCollection[Document] = database.getCollection("srs_cards")
  val srsAttemptsCollection: MongoCollection[Document] = database.getCollection("srs_attempts")

  println(s"[DB] Initializing connection to database: ${config.getString("database")}")

  ping().recover {
    case e: Exception =>
      println(s"[DB] CRITICAL: Failed to connect to MongoDB: ${e.getMessage}")
      println(s"[DB] Check your connection string in application.conf and ensure MongoDB is accessible.")
      "failed"
  }(scala.concurrent.ExecutionContext.global).foreach(p => println(s"[DB] Initial ping result: $p"))(scala.concurrent.ExecutionContext.global)

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

  // Index on is_public for study (repertoire) collection
  studyCollection.createIndex(
    org.mongodb.scala.model.Indexes.ascending("is_public")
  ).toFuture().foreach(_ => println("[DB] Index on is_public for repertoire created"))(scala.concurrent.ExecutionContext.global)

  // SRS: unique compound index on (user_email, puzzle_object_id)
  srsCardsCollection.createIndex(
    org.mongodb.scala.model.Indexes.compoundIndex(
      org.mongodb.scala.model.Indexes.ascending("user_email"),
      org.mongodb.scala.model.Indexes.ascending("puzzle_object_id")
    ),
    org.mongodb.scala.model.IndexOptions().unique(true)
  ).toFuture().foreach(_ => println("[DB] Unique index on (user_email, puzzle_object_id) for srs_cards created"))(scala.concurrent.ExecutionContext.global)

  // SRS: query index on (user_email, next_review)
  srsCardsCollection.createIndex(
    org.mongodb.scala.model.Indexes.compoundIndex(
      org.mongodb.scala.model.Indexes.ascending("user_email"),
      org.mongodb.scala.model.Indexes.ascending("next_review")
    )
  ).toFuture().foreach(_ => println("[DB] Index on (user_email, next_review) for srs_cards created"))(scala.concurrent.ExecutionContext.global)

  // SRS attempts: index on (user_email, reviewed_at)
  srsAttemptsCollection.createIndex(
    org.mongodb.scala.model.Indexes.compoundIndex(
      org.mongodb.scala.model.Indexes.ascending("user_email"),
      org.mongodb.scala.model.Indexes.ascending("reviewed_at")
    )
  ).toFuture().foreach(_ => println("[DB] Index on (user_email, reviewed_at) for srs_attempts created"))(scala.concurrent.ExecutionContext.global)

  def ping(): Future[String] = {
    database.runCommand(Document("ping" -> 1)).toFuture().map(_ => "pong")(scala.concurrent.ExecutionContext.global)
  }
}
