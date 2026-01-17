package shogi.puzzler.util

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object CryptoUtil {
  private val Transformation = "AES/ECB/PKCS5Padding"
  private val Algorithm = "AES"
  
  // In a real app, this should be a secure key from a environment variable or a keystore
  private val Key = "shogi-puzzler-ke".getBytes("UTF-8") 

  def encrypt(plainText: String): String = {
    if (plainText == null || plainText.isEmpty) return ""
    val secretKey = new SecretKeySpec(Key, Algorithm)
    val cipher = Cipher.getInstance(Transformation)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val encryptedBytes = cipher.doFinal(plainText.getBytes("UTF-8"))
    Base64.getEncoder.encodeToString(encryptedBytes)
  }

  def decrypt(encryptedText: String): String = {
    if (encryptedText == null || encryptedText.isEmpty) return ""
    try {
      val secretKey = new SecretKeySpec(Key, Algorithm)
      val cipher = Cipher.getInstance(Transformation)
      cipher.init(Cipher.DECRYPT_MODE, secretKey)
      val decryptedBytes = cipher.doFinal(Base64.getDecoder.decode(encryptedText))
      new String(decryptedBytes, "UTF-8")
    } catch {
      case _: Exception => encryptedText // Return as is if decryption fails (might be plain text from old config)
    }
  }
}
