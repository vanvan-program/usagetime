package dev.usagetime.app.sync

import android.content.Context

internal object DesktopDataSettings {
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("usage-settings", Context.MODE_PRIVATE)
            .getBoolean("show-desktop-data", false)

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences("usage-settings", Context.MODE_PRIVATE)
            .edit().putBoolean("show-desktop-data", enabled).apply()
    }
}
