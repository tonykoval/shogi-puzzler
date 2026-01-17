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
  val settingsCollection: MongoCollection[Document] = database.getCollection("settings")

  // Create unique index on kif_hash
  gamesCollection.createIndex(
    org.mongodb.scala.model.Indexes.ascending("kif_hash"),
    org.mongodb.scala.model.IndexOptions().unique(true)
  ).toFuture().foreach(_ => println("[DB] Unique index on kif_hash created"))(scala.concurrent.ExecutionContext.global)
  
  def ping(): Future[String] = {
    database.runCommand(Document("ping" -> 1)).toFuture().map(_ => "pong")(scala.concurrent.ExecutionContext.global)
  }
}
