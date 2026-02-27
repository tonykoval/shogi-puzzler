package shogi.puzzler.db

import org.mongodb.scala._
import org.mongodb.scala.bson.BsonArray
import org.mongodb.scala.model.Filters
import org.mongodb.scala.model.ReplaceOptions
import scala.concurrent.Future
import com.typesafe.config.ConfigFactory
import scala.concurrent.ExecutionContext.Implicits.global

case class User(
  email: String,
  role: String, // "ADMIN", "USER"
  allowedPages: Set[String] = Set.empty
)

object UserRepository {
  private val collection = MongoDBConnection.usersCollection
  private val config = ConfigFactory.load()

  private val adminEmail = config.getString("app.security.admin-email")

  def getUser(email: String): Future[Option[User]] = {
    if (email == adminEmail) {
      Future.successful(Some(User(email, "ADMIN", Set("*"))))
    } else {
      collection.find(Filters.equal("_id", email)).toFuture().map { docs =>
        docs.headOption.map(fetchFromDoc)
      }
    }
  }

  def getAllUsers(): Future[Seq[User]] = {
    collection.find().toFuture().map { docs =>
      val dbUsers = docs.map(fetchFromDoc)
      val admin = User(adminEmail, "ADMIN", Set("*"))
      if (!dbUsers.exists(_.email == adminEmail)) {
        admin +: dbUsers
      } else {
        dbUsers
      }
    }
  }

  def saveUser(user: User): Future[org.mongodb.scala.result.UpdateResult] = {
    if (user.email == adminEmail) {
       Future.failed(new Exception("Cannot modify hardcoded admin user in database"))
    } else {
      val doc = Document(
        "_id" -> user.email,
        "role" -> user.role,
        "allowedPages" -> user.allowedPages.toSeq
      )
      collection.replaceOne(Filters.equal("_id", user.email), doc, ReplaceOptions().upsert(true)).toFuture()
    }
  }

  def deleteUser(email: String): Future[org.mongodb.scala.result.DeleteResult] = {
    if (email == adminEmail) {
      Future.failed(new Exception("Cannot delete hardcoded admin user"))
    } else {
      collection.deleteOne(Filters.equal("_id", email)).toFuture()
    }
  }

  private def fetchFromDoc(doc: Document): User = {
    import scala.jdk.CollectionConverters._
    User(
      email = doc.getString("_id"),
      role = doc.getString("role"),
      allowedPages = if (doc.containsKey("allowedPages")) {
        doc.get[BsonArray]("allowedPages").map(_.asArray().getValues().asScala.map(_.asString().getValue).toSet).getOrElse(Set.empty)
      } else Set.empty
    )
  }
}
