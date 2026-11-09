package dev.usagetime.app

import dev.usagetime.app.sync.PhoneCrypto
import org.junit.Assert.*
import org.junit.Test

class PhoneCryptoTest {
    @Test fun decryptsCrossPlatformVector() {
        val encrypted = java.util.Base64.getDecoder().decode("AwMDAwMDAwMDAwMDVZbMbT8FLTYbNCovnz6cf06b4Yl6vyOc1uvCMSbLB1I=")
        assertEquals("phone-statistics", String(PhoneCrypto.decrypt(ByteArray(32) { 7 }, "device/day", encrypted), Charsets.UTF_8))
    }
    @Test fun pairedKeyRoundTrip() {
        val key = PhoneCrypto.key(PhoneCrypto.newKey())
        val body = "{\"device\":\"手机\",\"unknown\":3600000}".toByteArray(Charsets.UTF_8)
        assertArrayEquals(body, PhoneCrypto.decrypt(key, "device/day", PhoneCrypto.encrypt(key, "device/day", body)))
    }
    @Test fun ciphertextCannotMoveToAnotherDay() {
        val key = PhoneCrypto.key(PhoneCrypto.newKey())
        val encrypted = PhoneCrypto.encrypt(key, "device/day", byteArrayOf(1, 2, 3))
        assertThrows(Exception::class.java) { PhoneCrypto.decrypt(key, "device/other", encrypted) }
    }
    @Test fun corruptedCiphertextAndWrongPairingFail() {
        val key = PhoneCrypto.key(PhoneCrypto.newKey())
        val encrypted = PhoneCrypto.encrypt(key, "manifest", byteArrayOf(1, 2, 3))
        assertThrows(Exception::class.java) { PhoneCrypto.decrypt(PhoneCrypto.key(PhoneCrypto.newKey()), "manifest", encrypted) }
        encrypted[15] = (encrypted[15].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { PhoneCrypto.decrypt(key, "manifest", encrypted) }
    }
    @Test fun nonceIsDifferentForEachUpload() {
        val key = PhoneCrypto.key(PhoneCrypto.newKey())
        assertFalse(PhoneCrypto.encrypt(key, "day", byteArrayOf(1)).contentEquals(PhoneCrypto.encrypt(key, "day", byteArrayOf(1))))
    }
    @Test fun invalidKeyAndTruncatedFileFail() {
        assertThrows(Exception::class.java) { PhoneCrypto.key("abc") }
        assertThrows(Exception::class.java) { PhoneCrypto.decrypt(ByteArray(32), "day", ByteArray(27)) }
    }
}
