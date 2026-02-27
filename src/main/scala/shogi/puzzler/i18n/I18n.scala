package shogi.puzzler.i18n

import scala.io.Source
import scala.util.Try

/**
 * Simple i18n helper that loads translations from JSON files in src/main/resources/i18n/.
 *
 * Usage in Scala (Scalatags):
 *   implicit val lang: String = I18n.defaultLang  // or from cookie
 *   t("nav.myGames")   // returns "My Games" or "Moje partie" etc.
 *
 * Usage in JavaScript:
 *   window.i18n["nav.myGames"]   // injected by Components.layout
 */
object I18n {

  val defaultLang = "en"
  val supportedLangs: Set[String] = Set("en", "sk")

  private def loadMessages(lang: String): Map[String, String] = {
    Try {
      val stream = getClass.getResourceAsStream(s"/i18n/$lang.json")
      if (stream == null) Map.empty[String, String]
      else {
        val content = Source.fromInputStream(stream, "UTF-8").mkString
        ujson.read(content).obj.map { case (k, v) => k -> v.str }.toMap
      }
    }.getOrElse {
      Map.empty[String, String]
    }
  }

  /** All translations keyed by language code. Loaded once at startup. */
  private val translations: Map[String, Map[String, String]] =
    supportedLangs.map(lang => lang -> loadMessages(lang)).toMap

  /**
   * Translate a key using the implicit language.
   * Falls back to English, then to the key itself if not found.
   *
   * Example:
   *   implicit val lang: String = "sk"
   *   t("nav.myGames")  // => "Moje partie"
   */
  def t(key: String)(implicit lang: String): String =
    translations.getOrElse(lang, Map.empty).getOrElse(
      key,
      translations.getOrElse(defaultLang, Map.empty).getOrElse(key, key)
    )

  /**
   * Validate lang from cookie/query; return defaultLang if unsupported.
   */
  def validateLang(lang: String): String =
    if (supportedLangs.contains(lang)) lang else defaultLang

  /**
   * Serialize all messages for the given language to a JSON string,
   * suitable for injecting as window.i18n = {...} in HTML.
   */
  def messagesAsJson(lang: String): String = {
    val msgs = translations.getOrElse(lang, translations.getOrElse(defaultLang, Map.empty))
    val pairs = msgs.map { case (k, v) =>
      val ek = k.replace("\\", "\\\\").replace("\"", "\\\"")
      val ev = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
      s""""$ek":"$ev""""
    }
    pairs.mkString("{", ",", "}")
  }
}
