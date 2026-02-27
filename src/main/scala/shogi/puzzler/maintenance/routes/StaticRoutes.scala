package shogi.puzzler.maintenance.routes

import cask._

object StaticRoutes extends BaseRoutes {
  @cask.staticResources("/assets")
  def assets() = "assets"

  @cask.get("/js/:path", subpath = true)
  def js(path: String, request: cask.Request): cask.Response.Raw = {
    val resourcePath = "js/" + path
    val stream = getClass.getClassLoader.getResourceAsStream(resourcePath)
    if (stream == null) {
      cask.Response("Not found", statusCode = 404)
    } else {
      val bytes = stream.readAllBytes()
      stream.close()
      val contentType = if (path.endsWith(".js")) "application/javascript"
        else if (path.endsWith(".css")) "text/css"
        else "application/octet-stream"
      cask.Response(bytes, headers = Seq("Content-Type" -> contentType))
    }
  }

  @cask.get("/engine-wasm/:path", subpath = true)
  def engineWasm(path: String, request: cask.Request): cask.Response.Raw = {
    val resourcePath = "engine-wasm/" + path
    val stream = getClass.getClassLoader.getResourceAsStream(resourcePath)
    if (stream == null) {
      cask.Response("Not found", statusCode = 404)
    } else {
      val bytes = stream.readAllBytes()
      stream.close()
      val contentType = if (path.endsWith(".js")) "application/javascript"
        else if (path.endsWith(".wasm")) "application/wasm"
        else "application/octet-stream"
      cask.Response(bytes, headers = Seq(
        "Content-Type"                -> contentType,
        "Cross-Origin-Resource-Policy" -> "same-origin"
      ))
    }
  }

  initialize()
}
