package shogi.puzzler.game

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import shogi.Color

/**
 * Tests for GameLoader
 */
class GameLoaderSpec extends AnyFlatSpec with Matchers {

  // Helper to create sample KIF content
  def sampleKifContent(sentePlayer: String, gotePlayer: String): String = {
    s"""手合割：平手
開始日時：2024/12/31 10:00:00
終了日時：2024/12/31 11:30:00
棋戦：Test Tournament
先手：$sentePlayer
後手：$gotePlayer
場所：Test Arena
持ち時間：15分+10秒
手数----指手---------消費時間--
   1 ７六歩(77)   ( 0:01/00:00:01)
   2 ３四歩(33)   ( 0:01/00:00:01)
   3 ２六歩(27)   ( 0:01/00:00:02)
   4 ８四歩(83)   ( 0:01/00:00:02)
"""
  }

  "GameLoader.parseKif" should "extract metadata correctly" in {
    val kif = sampleKifContent("SentePlayer", "GotePlayer")
    val parsed = GameLoader.parseKif(kif)

    parsed.sentePlayerName shouldBe Some("SentePlayer")
    parsed.gotePlayerName shouldBe Some("GotePlayer")
    parsed.gameDate shouldBe Some("2024/12/31 10:00:00")
    parsed.gameSite shouldBe Some("Test Arena")
  }

  it should "detect player color if target name provided" in {
    val kif = sampleKifContent("SentePlayer", "GotePlayer")
    
    val parsedSente = GameLoader.parseKif(kif, targetPlayerName = Some("SentePlayer"))
    parsedSente.playerColorInGame shouldBe Color.sente

    val parsedGote = GameLoader.parseKif(kif, targetPlayerName = Some("GotePlayer"))
    parsedGote.playerColorInGame shouldBe Color.gote
  }

  it should "default to sente if no target name provided" in {
    val kif = sampleKifContent("SentePlayer", "GotePlayer")
    val parsed = GameLoader.parseKif(kif)
    parsed.playerColorInGame shouldBe Color.sente
  }

  "GameLoader.load" should "throw exception for missing file" in {
    an[Exception] should be thrownBy {
      GameLoader.load("nonexistent-file.kif", "Player")
    }
  }
}
