package dev.usagetime.app.sync

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class AppUpdateInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val apkName: String?,
    val apkSizeBytes: Long?,
    val publishedAt: String?
)

internal sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: AppUpdateInfo) : UpdateCheckResult()
    data class UpToDate(val latestVersion: String, val releaseUrl: String) : UpdateCheckResult()
    data object NoReleaseYet : UpdateCheckResult()
}

internal data class ParsedVersion(
    val parts: List<Int>,
    val prerelease: List<String>
) : Comparable<ParsedVersion> {
    override fun compareTo(other: ParsedVersion): Int {
        val maxLen = maxOf(parts.size, other.parts.size)
        for (index in 0 until maxLen) {
            val left = parts.getOrElse(index) { 0 }
            val right = other.parts.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1
        val shared = minOf(prerelease.size, other.prerelease.size)
        for (index in 0 until shared) {
            val cmp = comparePrereleaseIdentifier(prerelease[index], other.prerelease[index])
            if (cmp != 0) return cmp
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    private fun comparePrereleaseIdentifier(left: String, right: String): Int {
        val leftDigits = left.all { it.isDigit() }
        val rightDigits = right.all { it.isDigit() }
        if (leftDigits && rightDigits) {
            val trimmedLeft = left.trimStart('0').ifEmpty { "0" }
            val trimmedRight = right.trimStart('0').ifEmpty { "0" }
            if (trimmedLeft.length != trimmedRight.length) {
                return trimmedLeft.length.compareTo(trimmedRight.length)
            }
            return trimmedLeft.compareTo(trimmedRight)
        }
        if (leftDigits != rightDigits) return if (leftDigits) -1 else 1
        return left.compareTo(right)
    }
}

/**
 * Checks GitHub Releases for newer app versions without requiring a dedicated update server.
 * Network calls happen only when explicitly invoked by the caller.
 */
internal object AppUpdateChecker {
    const val GITHUB_REPO_OWNER = "vanvan-program"
    const val GITHUB_REPO_NAME = "usagetime"
    const val REPO_WEB_URL = "https://github.com/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME"
    const val RELEASES_WEB_URL = "$REPO_WEB_URL/releases"
    const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"

    internal const val MAX_RESPONSE_BYTES = 512 * 1024
    private const val MAX_NOTES_CHARS = 4000
    private val VERSION_PATTERN =
        Regex("""^[vV]?(\d+(?:\.\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun checkLatestReleaseSuspend(
        currentVersion: String,
        apiUrl: String = LATEST_RELEASE_API_URL,
        client: OkHttpClient = defaultClient,
        maxResponseBytes: Int = MAX_RESPONSE_BYTES
    ): UpdateCheckResult {
        val request = buildLatestReleaseRequest(currentVersion, apiUrl)
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = handleLatestReleaseResponse(
                            response = response,
                            currentVersion = currentVersion,
                            maxResponseBytes = maxResponseBytes
                        )
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    fun checkLatestRelease(
        currentVersion: String,
        apiUrl: String = LATEST_RELEASE_API_URL,
        client: OkHttpClient = defaultClient,
        maxResponseBytes: Int = MAX_RESPONSE_BYTES
    ): UpdateCheckResult {
        val request = buildLatestReleaseRequest(currentVersion, apiUrl)
        return client.newCall(request).execute().use { response ->
            handleLatestReleaseResponse(response, currentVersion, maxResponseBytes)
        }
    }

    internal fun buildLatestReleaseRequest(currentVersion: String, apiUrl: String): Request {
        val normalizedAgent = currentVersion.trim().ifEmpty { "0" }
        return Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "UsageTime/$normalizedAgent")
            .get()
            .build()
    }

    internal fun handleLatestReleaseResponse(
        response: Response,
        currentVersion: String,
        maxResponseBytes: Int = MAX_RESPONSE_BYTES
    ): UpdateCheckResult = response.use { res ->
        val code = res.code
        if (code == 404) return UpdateCheckResult.NoReleaseYet
        if (code == 403 || code == 429) {
            val remaining = res.header("X-RateLimit-Remaining")?.trim()
            if (code == 429 || remaining == "0") {
                throw IllegalStateException("GitHub API 访问次数达到上限，请稍后再试或前往 Releases 页面查看")
            }
        }
        check(res.isSuccessful) { "GitHub 响应异常（HTTP $code）" }
        val body = res.body ?: error("GitHub 返回内容为空")
        val contentLength = body.contentLength()
        check(contentLength <= maxResponseBytes) { "GitHub 返回内容过大" }
        val rawBytes = body.byteStream().use {
            SyncSupport.readLimitedBytes(it, maxResponseBytes, "GitHub 返回内容过大")
        }
        val text = rawBytes.toString(Charsets.UTF_8)
        parseLatestReleaseJson(currentVersion, text)
    }

    internal fun parseLatestReleaseJson(currentVersion: String, jsonText: String): UpdateCheckResult {
        val root = JSONObject(jsonText)
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
            return UpdateCheckResult.NoReleaseYet
        }
        val tagName = optNonNullString(root, "tag_name")?.trim().orEmpty()
        val latestVersion = parseVersion(tagName) ?: return UpdateCheckResult.NoReleaseYet
        val displayVersion = tagName.removePrefix("v").removePrefix("V")
        val releaseUrl = optNonNullString(root, "html_url")
            ?.trim()
            ?.takeIf(::isAllowedExternalUrl)
            ?: RELEASES_WEB_URL

        val currentParsed = parseVersion(currentVersion)
        if (currentParsed == null || latestVersion <= currentParsed) {
            return UpdateCheckResult.UpToDate(latestVersion = displayVersion, releaseUrl = releaseUrl)
        }

        val apkAsset = selectApkAsset(root)
        val title = optNonNullString(root, "name")?.trim()?.takeIf { it.isNotEmpty() } ?: tagName
        val notes = optNonNullString(root, "body")?.trim().orEmpty().take(MAX_NOTES_CHARS)
        val publishedAt = optNonNullString(root, "published_at")?.trim()?.takeIf { it.isNotEmpty() }

        return UpdateCheckResult.UpdateAvailable(
            AppUpdateInfo(
                tagName = tagName,
                versionName = displayVersion,
                title = title,
                notes = notes,
                releaseUrl = releaseUrl,
                apkUrl = apkAsset?.first,
                apkName = apkAsset?.second,
                apkSizeBytes = apkAsset?.third,
                publishedAt = publishedAt
            )
        )
    }

    private fun selectApkAsset(root: JSONObject): Triple<String, String, Long?>? {
        val assets = root.optJSONArray("assets") ?: return null
        var fallback: Triple<String, String, Long?>? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = optNonNullString(asset, "name")?.trim().orEmpty()
            val url = optNonNullString(asset, "browser_download_url")?.trim()
            if (!name.endsWith(".apk", ignoreCase = true) || url == null || !isAllowedExternalUrl(url)) {
                continue
            }
            val size = asset.optLong("size", -1L).takeIf { it > 0L }
            val candidate = Triple(url, name, size)
            if (name.startsWith("usagetime-", ignoreCase = true)) {
                return candidate
            }
            if (fallback == null) {
                fallback = candidate
            }
        }
        return fallback
    }

    internal fun parseVersion(raw: String): ParsedVersion? {
        val match = VERSION_PATTERN.matchEntire(raw.trim()) ?: return null
        val coreParts = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
        if (coreParts.isEmpty()) return null
        val prereleaseRaw = match.groupValues[2]
        val prereleaseParts = if (prereleaseRaw.isEmpty()) {
            emptyList()
        } else {
            val parts = prereleaseRaw.split('.')
            if (parts.any { it.isEmpty() }) return null
            parts
        }
        return ParsedVersion(coreParts, prereleaseParts)
    }

    internal fun isNewerVersion(remoteTagOrVersion: String, currentVersion: String): Boolean {
        val remote = parseVersion(remoteTagOrVersion) ?: return false
        val current = parseVersion(currentVersion) ?: return false
        return remote > current
    }

    internal fun isAllowedExternalUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https") return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "github.com" ||
            host.endsWith(".github.com") ||
            host == "githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")
    }

    internal fun formatApkSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0L) return null
        val megabytes = bytes / 1_048_576.0
        return if (megabytes >= 1.0) {
            "%.1f MB".format(Locale.CHINA, megabytes)
        } else {
            "${(bytes + 1023L) / 1024L} KB"
        }
    }

    internal fun friendlyErrorMessage(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "连接 GitHub 超时，请稍后再试"
        is UnknownHostException, is ConnectException -> "无法连接 GitHub，请检查当前网络"
        is SSLException -> "GitHub 安全连接失败，请检查网络环境"
        is JSONException -> "GitHub 返回的版本信息格式异常"
        is IllegalStateException -> error.message?.takeIf { it.isNotBlank() } ?: "检查更新失败，请稍后重试"
        else -> "检查更新失败，请稍后重试"
    }

    private fun optNonNullString(obj: JSONObject, key: String): String? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.optString(key)
}
