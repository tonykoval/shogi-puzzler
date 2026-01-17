package shogi.puzzler.game

object KifAnnotator {
  /**
   * Annotates a KIF string with comments at specific moves.
   * 
   * @param kif The original KIF content
   * @param comments A map of move number to comment string
   * @return Annotated KIF content
   */
  def annotate(kif: String, comments: Map[Int, String]): String = {
    val lineSeparator = if (kif.contains("\r\n")) "\r\n" else "\n"
    val lines = kif.split("\r?\n")
    // Match optional spaces, then either half-width digits or full-width digits, followed by spaces and more text
    val moveLineRegex = """^\s*([0-9０-９]+)\s+.*$""".r
    val annotatedLines = lines.map { line =>
      line match {
        case moveLineRegex(moveNumStr) =>
          try {
            val moveNum = normalizeNumber(moveNumStr).toInt
            comments.get(moveNum) match {
              case Some(comment) =>
                val formattedComment = comment.split("\r?\n").map(c => s"* $c").mkString(lineSeparator)
                line + lineSeparator + formattedComment
              case None => line
            }
          } catch {
            case _: NumberFormatException => line
          }
        case _ => line
      }
    }
    annotatedLines.mkString(lineSeparator)
  }

  private def normalizeNumber(s: String): String = {
    s.map {
      case c if c >= '０' && c <= '９' => (c - '０' + '0').toChar
      case c => c
    }
  }
}
