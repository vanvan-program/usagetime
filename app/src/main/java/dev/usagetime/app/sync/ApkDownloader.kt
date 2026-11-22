package dev.usagetime.app.sync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal class UpdateDownloadException(
    message: String,
    val attempts: List<String>
) : IOException(message)

/** Downloads a release APK into the app cache, trying configured sources in order. */
internal object ApkDownloader {
    internal const val MAX_APK_BYTES = 300L * 1024L * 1024L
    private const val PROGRESS_INTERVAL_MS = 120L
    private const val BUFFER_BYTES = 64 * 1024

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor(trustedHostInterceptor())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun client(): OkHttpClient = defaultClient

    fun targetFile(context: Context, apkName: String?, versionName: String): File {
        val directory = File(context.cacheDir, "updates")
        if (!directory.exists()) directory.mkdirs()
        val fallbackVersion = versionName.filter { it.isLetterOrDigit() || it == '.' }.ifBlank { "update" }
        val fallback = "usagetime-$fallbackVersion.apk"
        val raw = apkName?.substringAfterLast('/')?.takeIf { it.endsWith(".apk", ignoreCase = true) } ?: fallback
        val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "usagetime-update.apk" }
        return File(directory, if (safe.endsWith(".apk", ignoreCase = true)) safe else "$safe.apk")
    }

    suspend fun download(
        context: Context,
        release: AppUpdateInfo,
        mode: UpdateSourceMode,
        client: OkHttpClient = defaultClient,
        onState: (UpdateDownloadState) -> Unit
    ): File {
        val source = release.apkUrl ?: throw UpdateDownloadException("该版本没有可下载的 APK 安装包", emptyList())
        check(AppUpdateChecker.isAllowedExternalUrl(source)) { "安装包地址不在可信范围内" }
        val expectedSha256 = release.apkSha256 ?: throw UpdateDownloadException(
            "该版本未提供校验值，请在浏览器中打开 Release 页面下载",
            emptyList()
        )
        if (release.apkSizeBytes != null && release.apkSizeBytes > MAX_APK_BYTES) {
            throw UpdateDownloadException("安装包超过大小上限", emptyList())
        }
        val candidates = UpdateMirrors.downloadCandidates(source, mode)
            .filter { (_, url) -> UpdateMirrors.isAllowedDownloadUrl(url) }
        val target = targetFile(context, release.apkName, release.versionName)
        return downloadTo(target, candidates, release.apkSizeBytes, client, expectedSha256, onState)
    }

    /**
     * Streams the first working candidate into [file].
     *
     * Callers are responsible for validating candidate URLs; this entry point is also used by
     * JVM tests with local HTTP URLs, hence no host check here.
     * [expectedSha256] stays optional only for those tests; [download] guarantees a non-null digest.
     */
    internal suspend fun downloadTo(
        file: File,
        candidates: List<Pair<UpdateMirror, String>>,
        expectedSize: Long?,
        client: OkHttpClient = defaultClient,
        expectedSha256: String? = null,
        onState: (UpdateDownloadState) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val attempts = mutableListOf<String>()
        for ((index, candidate) in candidates.withIndex()) {
            val (mirror, url) = candidate
            currentCoroutineContext().ensureActive()
            onState(
                UpdateDownloadState.Running(
                    progress = DownloadProgress(0L, expectedSize),
                    sourceLabel = mirror.label,
                    attempt = index + 1,
                    attemptCount = candidates.size
                )
            )
            try {
                val partial = File.createTempFile("update-", ".part", file.absoluteFile.parentFile)
                try {
                    val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
                    streamTo(url, partial, expectedSize, mirror, index, candidates.size, client, digest, onState)
                    currentCoroutineContext().ensureActive()
                    onState(UpdateDownloadState.Verifying)
                    validateApk(partial, expectedSize)
                    if (digest != null) {
                        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                        check(sha256.equals(expectedSha256, ignoreCase = true)) { "安装包校验值与发布信息不一致" }
                    }
                    Files.move(partial.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    partial.delete()
                }
                val size = file.length()
                onState(UpdateDownloadState.Ready(file, size))
                return@withContext file
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                attempts += "${mirror.label}：${friendlyError(error)}"
            }
        }
        val message = if (attempts.isEmpty()) "没有可用的下载源" else "下载失败，已尝试 ${attempts.size} 个来源"
        onState(UpdateDownloadState.Failed(message))
        throw UpdateDownloadException(message, attempts)
    }

    private suspend fun streamTo(
        url: String,
        partial: File,
        expectedSize: Long?,
        mirror: UpdateMirror,
        index: Int,
        attemptCount: Int,
        client: OkHttpClient,
        digest: MessageDigest?,
        onState: (UpdateDownloadState) -> Unit
    ) {
        val downloadContext = currentCoroutineContext()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "UsageTime-Update")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: throw IOException("响应内容为空")
            val declared = body.contentLength().takeIf { it > 0L }
            if (declared != null && declared > MAX_APK_BYTES) throw IOException("安装包超过大小上限")
            if (declared != null && expectedSize != null && expectedSize > 0L && declared != expectedSize) {
                throw IOException("安装包大小与发布信息不一致")
            }
            val total = declared ?: expectedSize
            var read = 0L
            var lastEmit = 0L
            val startedAt = System.nanoTime()
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        downloadContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        read += count
                        if (read > MAX_APK_BYTES) throw IOException("安装包超过大小上限")
                        output.write(buffer, 0, count)
                        digest?.update(buffer, 0, count)
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= PROGRESS_INTERVAL_MS || (total != null && read >= total)) {
                            lastEmit = now
                            onState(progressState(read, total, mirror, index, attemptCount, startedAt))
                        }
                    }
                }
            }
            if (read <= 0L) throw IOException("下载内容为空")
            onState(progressState(read, total, mirror, index, attemptCount, startedAt))
        }
    }

    private fun progressState(
        read: Long,
        total: Long?,
        mirror: UpdateMirror,
        index: Int,
        attemptCount: Int,
        startedAt: Long
    ): UpdateDownloadState.Running {
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        val speed = if (elapsedMs >= 500L) read * 1000L / elapsedMs else null
        return UpdateDownloadState.Running(
            progress = DownloadProgress(read, total),
            sourceLabel = mirror.label,
            attempt = index + 1,
            attemptCount = attemptCount,
            bytesPerSecond = speed
        )
    }

    internal fun validateApk(file: File, expectedSize: Long?) {
        check(file.length() > 0L) { "下载内容为空" }
        if (expectedSize != null && expectedSize > 0L) {
            check(file.length() == expectedSize) { "安装包大小与发布信息不一致" }
        }
        val header = ByteArray(4)
        val read = file.inputStream().use { it.read(header) }
        check(
            read == 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
                header[2] == 3.toByte() && header[3] == 4.toByte()
        ) { "下载内容不是有效的 APK 文件" }
    }

    internal fun friendlyError(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "连接超时"
        is UnknownHostException, is ConnectException -> "无法连接"
        is SSLException -> "安全连接失败"
        is IOException -> error.message?.takeIf { it.isNotBlank() } ?: "网络错误"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "下载失败"
    }
}