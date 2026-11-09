package dev.usagetime.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.usagetime.app.sync.DesktopDataSettings
import dev.usagetime.app.sync.DesktopSync
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DesktopDataSettingsTest {
    private fun isolated(denySyncAccess: Boolean = false): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "desktop-opt-in-${UUID.randomUUID()}"
        val directory = File(base.cacheDir, namespace).apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getFilesDir(): File {
                check(!denySyncAccess) { "关闭电脑显示时不应读取同步配置或缓存" }
                return directory
            }

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(!denySyncAccess || name == "usage-settings") { "关闭电脑显示时不应读取同步状态" }
                return base.getSharedPreferences("$namespace-$name", mode)
            }
        }
    }

    @Test fun desktopDataIsHiddenUntilExplicitlyEnabled() {
        val context = isolated()
        assertFalse(DesktopDataSettings.enabled(context))
        DesktopDataSettings.save(context, true)
        assertTrue(DesktopDataSettings.enabled(ContextWrapper(context)))
        DesktopDataSettings.save(context, false)
        assertFalse(DesktopDataSettings.enabled(ContextWrapper(context)))
    }

    @Test fun disabledPullSkipsCredentialsCacheAndNetwork() = runBlocking {
        val context = isolated(denySyncAccess = true)
        assertEquals(0, DesktopSync.pull(context))
        DesktopSync.pullIfStale(context, 0)
        DesktopDataSettings.save(context, true)
        DesktopDataSettings.save(context, false)
        assertEquals(0, DesktopSync.pull(context))
    }

    @Test fun enablingDesktopDisplayStillRequiresCloudSyncConfiguration() {
        val context = isolated()
        DesktopDataSettings.save(context, true)
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { DesktopSync.pull(context) }
        }
        assertTrue(error.message.orEmpty().contains("启用同步"))
    }
}
