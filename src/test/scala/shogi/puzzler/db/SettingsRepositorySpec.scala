package shogi.puzzler.db

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration._

class SettingsRepositorySpec extends AnyWordSpec with Matchers {
  "SettingsRepository" should {
    "save and load AppSettings including winChanceDropThreshold" in {
      val testUser = "test-user-" + java.util.UUID.randomUUID().toString
      val settings = AppSettings(
        lishogiNickname = "lishogi",
        shogiwarsNickname = "swars",
        dojo81Nickname = "dojo81",
        dojo81Password = "pass",
        enginePath = "engine",
        shallowLimit = 5,
        deepLimit = 15,
        winChanceDropThreshold = 0.25
      )

      try {
        Await.result(SettingsRepository.saveAppSettings(testUser, settings), 5.seconds)
        val loaded = Await.result(SettingsRepository.getAppSettings(Some(testUser)), 5.seconds)

        loaded.lishogiNickname shouldBe settings.lishogiNickname
        loaded.shogiwarsNickname shouldBe settings.shogiwarsNickname
        loaded.dojo81Nickname shouldBe settings.dojo81Nickname
        loaded.dojo81Password shouldBe settings.dojo81Password
        loaded.enginePath shouldBe settings.enginePath
        loaded.shallowLimit shouldBe settings.shallowLimit
        loaded.deepLimit shouldBe settings.deepLimit
        loaded.winChanceDropThreshold shouldBe settings.winChanceDropThreshold
      } finally {
        // Cleanup if needed, though for local mongo it's usually fine
      }
    }

    "save and load individual double setting" in {
        val key = "test-double-" + java.util.UUID.randomUUID().toString
        val value = 0.75
        
        Await.result(SettingsRepository.saveDoubleSetting(key, value), 5.seconds)
        val loaded = Await.result(SettingsRepository.getDoubleSetting(key, 0.1), 5.seconds)
        
        loaded shouldBe value
    }
  }
}
