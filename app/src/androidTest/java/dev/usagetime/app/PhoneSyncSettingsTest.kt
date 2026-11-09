package dev.usagetime.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.usagetime.app.sync.PhoneSyncConfig
import dev.usagetime.app.sync.PhoneSyncSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhoneSyncSettingsTest {
    private fun isolated(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "phone-settings-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getFilesDir(): File = directory
        }
    }
    @Test fun keystoreProtectsAndReloadsPairingCredentials() {
        val context = isolated()
        val settings = PhoneSyncSettings(context)
        val value = PhoneSyncConfig(url = "https://example.invalid/dav/", user = "qa-user",
            password = "synthetic-secret", deviceName = "合成测试")
        settings.save(value)
        assertEquals(value, settings.load())
        val bytes = File(context.filesDir, "phone-sync.settings").readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(value.password))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(value.pairingKey))
        settings.save(value.copy(deviceName = "第二次保存"))
        assertEquals("第二次保存", settings.load().deviceName)
    }
    @Test fun insecureAddressCannotEnableSync() {
        val context = isolated()
        val settings = PhoneSyncSettings(context)
        assertThrows(IllegalArgumentException::class.java) {
            settings.save(PhoneSyncConfig(enabled = true, url = "http://example.invalid/"))
        }
        assertFalse(settings.load().enabled)
        assertFalse(File(context.filesDir, "phone-sync.settings").exists())
    }
}
