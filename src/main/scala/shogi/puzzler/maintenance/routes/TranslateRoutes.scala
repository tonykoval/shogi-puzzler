package shogi.puzzler.maintenance.routes

import cask._

/** Proxy route for MyMemory translation API (avoids CORS from browser). */
object TranslateRoutes extends BaseRoutes {

  @cask.post("/api/translate")
  def translate(request: cask.Request): cask.Response[ujson.Value] = {
    try {
      val json = ujson.read(request.text())
      val text = json("text").str
      val from = json.obj.get("from").map(_.str).getOrElse("en")
      val to   = json.obj.get("to").map(_.str).getOrElse("sk")

      if (text.trim.isEmpty) {
        cask.Response(
          ujson.Obj("error" -> "Empty text"),
          statusCode = 400,
          headers = Seq("Content-Type" -> "application/json")
        )
      } else {
        val resp = requests.get(
          "https://api.mymemory.translated.net/get",
          params = Seq("q" -> text, "langpair" -> s"$from|$to"),
          readTimeout = 10000,
          connectTimeout = 5000
        )
        val respJson = ujson.read(resp.text())

        val translated = respJson.obj
          .get("responseData")
          .flatMap(_.obj.get("translatedText"))
          .map(_.str)
          .getOrElse("")

        cask.Response(
          ujson.Obj("translated" -> translated),
          headers = Seq("Content-Type" -> "application/json")
        )
      }
    } catch {
      case e: Exception =>
        logger.error(s"[TRANSLATE] Error: ${e.getMessage}", e)
        cask.Response(
          ujson.Obj("error" -> Option(e.getMessage).getOrElse("Unknown error")),
          statusCode = 500,
          headers = Seq("Content-Type" -> "application/json")
        )
    }
  }

  initialize()
}
