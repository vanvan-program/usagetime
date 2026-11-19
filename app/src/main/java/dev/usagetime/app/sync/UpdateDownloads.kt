package dev.usagetime.app.sync

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.net.URI
import java.util.Locale

internal enum class UpdateSourceMode(val key: String, val label: String, val description: String) {
    AUTO("auto", "自动（推荐）", "优先通过 GitHub 官方检查和下载，失败时使用镜像。"),
    MIRROR("mirror", "镜像优先", "优先通过第三方镜像检查和下载，失败时尝试 GitHub 官方。"),
    GITHUB("github", "仅 GitHub 官方", "检查和下载均通过 GitHub 官方。");

    companion object {
        fun fromKey(key: String?): UpdateSourceMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

internal data class UpdateMirror(val id: String, val label: String, val proxyPrefix: String?)

internal object UpdateMirrors {
    val OFFICIAL = UpdateMirror("github", "GitHub 官方", null)
    private val apiMirror = UpdateMirror("gh-proxy", "gh-proxy.com", "https://gh-proxy.com/")

    // ghfast.top and ghproxy.net serve release assets but reject GitHub's release API.
    private val downloadMirrors = listOf(
        apiMirror,
        UpdateMirror("ghfast", "ghfast.top", "https://ghfast.top/"),
        UpdateMirror("ghproxy-net", "ghproxy.net", "https://ghproxy.net/")
    )
    private val proxyHosts = downloadMirrors.mapNotNull { it.proxyPrefix?.let { prefix -> URI(prefix).host } }.toSet()

    fun downloadCandidates(target: String, mode: UpdateSourceMode): List<Pair<UpdateMirror, String>> =
        candidates(target, mode, downloadMirrors)

    fun apiCandidates(mode: UpdateSourceMode): List<Pair<UpdateMirror, String>> =
        candidates(AppUpdateChecker.LATEST_RELEASE_API_URL, mode, listOf(apiMirror))

    private fun candidates(
        target: String,
        mode: UpdateSourceMode,
        mirrors: List<UpdateMirror>
    ): List<Pair<UpdateMirror, String>> {
        val ordered = when (mode) {
            UpdateSourceMode.GITHUB -> listOf(OFFICIAL)
            UpdateSourceMode.MIRROR -> mirrors + OFFICIAL
            UpdateSourceMode.AUTO -> listOf(OFFICIAL) + mirrors
        }
        return ordered.map { mirror -> mirror to (mirror.proxyPrefix.orEmpty() + target) }
    }

    fun isAllowedDownloadUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (AppUpdateChecker.isAllowedExternalUrl(trimmed)) return true
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        return uri.host?.lowercase() in proxyHosts
    }
}
internal data class DownloadProgress(val bytesRead: Long, val totalBytes: Long?) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }
            ?.let { total -> (bytesRead.toDouble() / total).coerceIn(0.0, 1.0).toFloat() }

    val percent: Int? get() = fraction?.let { fraction -> (fraction * 100f).toInt() }
}

internal sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState

    data class Running(
        val progress: DownloadProgress,
        val sourceLabel: String,
        val attempt: Int,
        val attemptCount: Int,
        val bytesPerSecond: Long? = null
    ) : UpdateDownloadState

    data object Verifying : UpdateDownloadState

    data class Ready(val file: File, val sizeBytes: Long) : UpdateDownloadState

    data class Failed(val message: String, val sourceLabel: String? = null) : UpdateDownloadState
}

internal object UpdatePreferences {
    private const val PREFS = "update-settings"
    private const val KEY_SOURCE_MODE = "source-mode"

    fun sourceMode(context: Context): UpdateSourceMode =
        UpdateSourceMode.fromKey(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SOURCE_MODE, null)
        )

    fun setSourceMode(context: Context, mode: UpdateSourceMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_SOURCE_MODE, mode.key)
        }
    }
}

internal object UpdateFormatting {
    fun bytes(value: Long): String {
        if (value <= 0L) return "0 B"
        val megabytes = value / 1_048_576.0
        return when {
            megabytes >= 1.0 -> "%.1f MB".format(Locale.CHINA, megabytes)
            value >= 1024L -> "${value / 1024L} KB"
            else -> "$value B"
        }
    }

    fun speed(bytesPerSecond: Long?): String? {
        if (bytesPerSecond == null || bytesPerSecond <= 0L) return null
        val megabytes = bytesPerSecond / 1_048_576.0
        return when {
            megabytes >= 1.0 -> "%.1f MB/s".format(Locale.CHINA, megabytes)
            bytesPerSecond >= 1024L -> "${bytesPerSecond / 1024L} KB/s"
            else -> "$bytesPerSecond B/s"
        }
    }
}