package shogi.puzzler.maintenance

import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

case class TaskProgress(
    id: String,
    status: String, // "running", "completed", "failed", "waiting"
    message: String,
    resultHtml: Option[String] = None,
    resultJson: Option[String] = None,
    error: Option[String] = None,
    kifHash: Option[String] = None,
    createdAt: Long = System.currentTimeMillis()
)

object TaskManager {
  private val tasks = new ConcurrentHashMap[String, TaskProgress]()

  def createTask(kifHash: Option[String] = None): String = {
    val id = UUID.randomUUID().toString
    tasks.put(id, TaskProgress(id, "waiting", "Pending...", kifHash = kifHash))
    id
  }

  def getAllTasks: Seq[TaskProgress] = {
    import scala.jdk.CollectionConverters._
    tasks.values().asScala.toSeq
  }

  def updateProgress(id: String, message: String): Unit = {
    val current = tasks.get(id)
    if (current != null) {
      val newStatus = if (message.contains("In queue")) "waiting" else "running"
      tasks.put(id, current.copy(message = message, status = newStatus))
    }
  }

  def complete(id: String, resultHtml: String): Unit = {
    val current = tasks.get(id)
    if (current != null) {
      tasks.put(id, current.copy(status = "completed", message = "Completed", resultHtml = Some(resultHtml)))
    }
  }

  def completeWithJson(id: String, resultHtml: String, resultJson: String): Unit = {
    val current = tasks.get(id)
    if (current != null) {
      tasks.put(id, current.copy(
        status = "completed",
        message = "Completed",
        resultHtml = Some(resultHtml),
        resultJson = Some(resultJson)
      ))
    }
  }

  def fail(id: String, error: String): Unit = {
    val current = tasks.get(id)
    if (current != null) {
      tasks.put(id, current.copy(status = "failed", message = "Failed", error = Some(error)))
    }
  }

  def getTask(id: String): Option[TaskProgress] = {
    Option(tasks.get(id))
  }

  def cleanUp(id: String): Unit = {
    tasks.remove(id)
  }
}
