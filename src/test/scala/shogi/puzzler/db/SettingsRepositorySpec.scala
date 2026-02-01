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
        loaded.enginePath shouldBe settings.enginePath
        loaded.shallowLimit shouldBe settings.shallowLimit
        loaded.deepLimit shouldBe settings.deepLimit
        loaded.winChanceDropThreshold shouldBe settings.winChanceDropThreshold
        loaded.isConfigured shouldBe true
      } finally {
        // Cleanup if needed, though for local mongo it's usually fine
      }
    }

    "return isConfigured as false when settings are not in DB" in {
      val nonExistentUser = "non-existent-" + java.util.UUID.randomUUID().toString
      val loaded = Await.result(SettingsRepository.getAppSettings(Some(nonExistentUser)), 5.seconds)
      loaded.isConfigured shouldBe false
    }

    "save and load individual double setting" in {
        val key = "test-double-" + java.util.UUID.randomUUID().toString
        val value = 0.75
        
        Await.result(SettingsRepository.saveDoubleSetting(key, value), 5.seconds)
        val loaded = Await.result(SettingsRepository.getDoubleSetting(key, 0.1), 5.seconds)
        
        loaded shouldBe value
    }

    "share OCR profiles between users" in {
      val userA = "user-a-" + java.util.UUID.randomUUID().toString
      val userB = "user-b-" + java.util.UUID.randomUUID().toString
      
      val profileName = "SharedProfile-" + java.util.UUID.randomUUID().toString
      val regions = Map("board" -> shogi.puzzler.maintenance.Rect(10, 20, 30, 40))
      
      // User A saves a profile
      Await.result(SettingsRepository.updateGlobalOcrProfiles(Map(profileName -> regions)), 5.seconds)
      
      // User B should see it
      val loadedB = Await.result(SettingsRepository.getAppSettings(Some(userB)), 5.seconds)
      loadedB.ocrProfiles should contain key profileName
      loadedB.ocrProfiles(profileName)("board").x shouldBe 10
      
      // User A should also see it
      val loadedA = Await.result(SettingsRepository.getAppSettings(Some(userA)), 5.seconds)
      loadedA.ocrProfiles should contain key profileName
    }
  }
}
