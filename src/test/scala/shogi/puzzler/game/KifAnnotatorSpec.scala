package shogi.puzzler.game

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class KifAnnotatorSpec extends AnyFlatSpec with Matchers {

  "KifAnnotator.annotate" should "add comments to the correct moves" in {
    val kif = """手数----指手---------消費時間--
   1 ７六歩(77)   ( 0:01/00:00:01)
   2 ３四歩(33)   ( 0:01/00:00:01)
   3 ２六歩(27)   ( 0:01/00:00:02)
"""
    val comments = Map(2 -> "This is a comment for move 2", 3 -> "Comment for move 3\nwith multiple lines")
    val annotated = KifAnnotator.annotate(kif, comments)

    annotated should include("   2 ３四歩(33)   ( 0:01/00:00:01)")
    annotated should include("* This is a comment for move 2")
    annotated should include("   3 ２六歩(27)   ( 0:01/00:00:02)")
    annotated should include("* Comment for move 3")
    annotated should include("* with multiple lines")
    annotated should include("   1 ７六歩(77)   ( 0:01/00:00:01)")
    annotated should not include ("* Comment for move 1")
  }

  it should "handle different space indentations and full-width numbers" in {
    val kif = """   1 ７六歩(77)
  10 ７六歩(77)
 100 ７六歩(77)
"""
    val comments = Map(1 -> "One", 10 -> "Ten", 100 -> "Hundred")
    val annotated = KifAnnotator.annotate(kif, comments)

    annotated should include("   1 ７六歩(77)")
    annotated should include("* One")
    annotated should include("  10 ７六歩(77)")
    annotated should include("* Ten")
    annotated should include(" 100 ７六歩(77)")
    annotated should include("* Hundred")
  }

  it should "handle spaces between move number and move text correctly" in {
    val kif = """    1  ７六歩(77)
    2   ３四歩(33)
"""
    val comments = Map(1 -> "One", 2 -> "Two")
    val annotated = KifAnnotator.annotate(kif, comments)

    annotated should include("    1  ７六歩(77)")
    annotated should include("* One")
    annotated should include("    2   ３四歩(33)")
    annotated should include("* Two")
  }

  it should "handle full-width move numbers" in {
    // Some KIFs might use full-width characters for everything, though usually move numbers are half-width
    val kif = """１ ７六歩(77)
２ ３四歩(33)
"""
    val comments = Map(1 -> "One", 2 -> "Two")
    val annotated = KifAnnotator.annotate(kif, comments)

    annotated should include("１ ７六歩(77)")
    annotated should include("* One")
  }

  it should "handle moves with leading spaces and multiple spaces before move text" in {
    val kif = "  123  ７六歩(77)"
    val comments = Map(123 -> "Check")
    val annotated = KifAnnotator.annotate(kif, comments)
    annotated should include("  123  ７六歩(77)")
    annotated should include("* Check")
  }

  it should "not add comments to metadata lines that start with numbers" in {
    // Though rare, if a metadata line starts with a number, we shouldn't annotate it
    // Usually metadata lines have a colon like "1: Some metadata" or "1. Some metadata"
    // KIF metadata lines usually look like "Key：Value"
    val kif = """1st game
   1 ７六歩(77)
"""
    val comments = Map(1 -> "Move 1")
    val annotated = KifAnnotator.annotate(kif, comments)
    annotated should include("1st game")
    annotated should include("   1 ７六歩(77)")
    annotated should include("* Move 1")
    annotated should not include ("1st game\n* Move 1")
    annotated should not include ("1st game\r\n* Move 1")
  }

  it should "handle KIFs with only moves" in {
    val kif = "   1 ７六歩(77)"
    val comments = Map(1 -> "Move 1")
    val annotated = KifAnnotator.annotate(kif, comments)
    annotated should include("   1 ７六歩(77)")
    annotated should include("* Move 1")
  }

  it should "handle CRLF line endings" in {
    val kif = "   1 ７六歩(77)\r\n   2 ３四歩(33)\r\n"
    val comments = Map(1 -> "One", 2 -> "Two")
    val annotated = KifAnnotator.annotate(kif, comments)
    annotated should include("   1 ７六歩(77)\r\n* One")
    annotated should include("   2 ３四歩(33)\r\n* Two")
  }

  it should "handle KIF header and other lines correctly" in {
     val kif = """手合割：平手
先手：Sente
後手：Gote

手数----指手---------消費時間--
   1 ７六歩(77)
"""
     val comments = Map(1 -> "Move 1")
     val annotated = KifAnnotator.annotate(kif, comments)
     
     annotated should include("先手：Sente")
     annotated should include("   1 ７六歩(77)")
     annotated should include("* Move 1")
  }
}
