package dev.usagetime.app.sync

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.net.URI
import java.util.Locale

/**
 * Where the app is allowed to fetch update metadata and APK files from.
 *
 * GitHub's release hosts are unreachable from some mainland networks; the domestic entries are
 * public read-only GitHub proxies that forward the same path. They are only download helpers:
 * the version decision and the package signature are still made locally.
 */
internal enum class UpdateSourceMode(val key: String, val label: String, val shortLabel: String) {
    AUTO("auto", "自动（GitHub 官方 → 国内镜像）", "自动"),
    MIRROR("mirror", "国内镜像优先", "国内镜像"),
    GITHUB("github", "仅 GitHub 官方", "仅 GitHub");

    companion object {
        fun fromKey(key: String?): UpdateSourceMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

internal data class UpdateMirror(val id: String, val label: String, val proxyPrefix: String?)

internal object UpdateMirrors {
    val OFFICIAL = UpdateMirror("github", "GitHub 官方", null)

    /** Public GitHub proxies commonly reachable from mainland China. */
    val DOMESTIC: List<UpdateMirror> = listOf(
        UpdateMirror("ghfast", "ghfast.top", "https://ghfast.top/"),
        UpdateMirror("ghproxy-net", "ghproxy.net", "https://ghproxy.net/"),
        UpdateMirror("gh-proxy", "gh-proxy.com", "https://gh-proxy.com/"),
        UpdateMirror("moeyy", "moeyy.xyz", "https://github.moeyy.xyz/")
    )

    val ALL: List<UpdateMirror> = listOf(OFFICIAL) + DOMESTIC

    private const val MAX_DOWNLOAD_ATTEMPTS = 5

    fun proxyHosts(): Set<String> = DOMESTIC.mapNotNull { mirror ->
        mirror.proxyPrefix?.let { prefix -> runCatching { URI(prefix).host?.lowercase() }.getOrNull() }
    }.toSet()

    fun downloadUrl(target: String, mirror: UpdateMirror): String =
        mirror.proxyPrefix?.let { prefix -> prefix + target } ?: target

    /** Ordered APK download candidates for [target]; mirrors are derived from the same GitHub URL. */
    fun downloadCandidates(target: String, mode: UpdateSourceMode): List<Pair<UpdateMirror, String>> {
        val ordered = when (mode) {
            UpdateSourceMode.GITHUB -> listOf(OFFICIAL)
            UpdateSourceMode.MIRROR -> DOMESTIC
            UpdateSourceMode.AUTO -> ALL
        }
        return ordered.take(MAX_DOWNLOAD_ATTEMPTS).map { mirror -> mirror to downloadUrl(target, mirror) }
    }

    /** Ordered release-metadata candidates; proxies forward the api.github.com path. */
    fun apiCandidates(mode: UpdateSourceMode): List<Pair<UpdateMirror, String>> {
        val api = AppUpdateChecker.LATEST_RELEASE_API_URL
        val ordered = when (mode) {
            UpdateSourceMode.GITHUB -> listOf(OFFICIAL)
            UpdateSourceMode.MIRROR -> DOMESTIC
            // Trying every proxy for the API would be slow; two fallbacks are enough.
            UpdateSourceMode.AUTO -> listOf(OFFICIAL) + DOMESTIC.take(2)
        }
        return ordered.map { mirror -> mirror to downloadUrl(api, mirror) }
    }

    fun isAllowedDownloadUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (AppUpdateChecker.isAllowedExternalUrl(trimmed)) return true
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return proxyHosts().any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    fun describeCandidates(mode: UpdateSourceMode): String = when (mode) {
        UpdateSourceMode.AUTO -> "GitHub 官方，失败后依次尝试 " + DOMESTIC.joinToString("、") { it.label }
        UpdateSourceMode.MIRROR -> "依次尝试 " + DOMESTIC.joinToString("、") { it.label }
        UpdateSourceMode.GITHUB -> "仅尝试 GitHub 官方下载地址"
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