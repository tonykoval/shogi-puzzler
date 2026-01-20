package shogi.puzzler.maintenance

import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

case class TaskProgress(
    id: String,
    status: String, // "running", "completed", "failed"
    message: String,
    resultHtml: Option[String] = None,
    error: Option[String] = None
)

object TaskManager {
  private val tasks = new ConcurrentHashMap[String, TaskProgress]()

  def createTask(): String = {
    val id = UUID.randomUUID().toString
    tasks.put(id, TaskProgress(id, "running", "Initializing..."))
    id
  }

  def updateProgress(id: String, message: String): Unit = {
    val current = tasks.get(id)
    if (current != null) {
      tasks.put(id, current.copy(message = message))
    }
  }

  def complete(id: String, resultHtml: String): Unit = {
    val current = tasks.get(id)
    if (current != null) {
      tasks.put(id, current.copy(status = "completed", message = "Completed", resultHtml = Some(resultHtml)))
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
