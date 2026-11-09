package dev.usagetime.app.sync

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object PhoneCrypto {
    private val random = SecureRandom()
    fun newKey(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun key(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim()).also {
        require(it.size == 32) { "配对码无效，需要 32 字节密钥" }
    }
    fun encrypt(key: ByteArray, path: String, body: ByteArray, prefix: String = "usagetime-sync-v1"): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("$prefix:$path".toByteArray(Charsets.UTF_8))
        return nonce + cipher.doFinal(body)
    }
    fun decrypt(key: ByteArray, path: String, body: ByteArray, prefix: String = "usagetime-sync-v1"): ByteArray {
        require(body.size >= 28) { "同步文件不完整" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, body.copyOfRange(0, 12)))
        cipher.updateAAD("$prefix:$path".toByteArray(Charsets.UTF_8))
        return cipher.doFinal(body.copyOfRange(12, body.size))
    }
}
