package dev.usagetime.app

import dev.usagetime.app.sync.AppUpdateChecker
import dev.usagetime.app.sync.UpdateCheckResult
import dev.usagetime.app.sync.UpdateService
import dev.usagetime.app.sync.UpdateSourceMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

class AppUpdateCheckerTest {
    @Test fun versionComparisonHandlesSemVerAndNumericSegments() {
        assertTrue(AppUpdateChecker.isNewerVersion("v0.4.3", "0.4.2"))
        assertTrue(AppUpdateChecker.isNewerVersion("0.10.0", "0.4.2"))
        assertTrue(AppUpdateChecker.isNewerVersion("v1.0", "0.9.9"))
        assertTrue(AppUpdateChecker.isNewerVersion("v0.4.3-rc.1", "0.4.2"))
        assertTrue(AppUpdateChecker.isNewerVersion("v0.4.2-beta.10", "0.4.2-beta.2"))

        assertFalse(AppUpdateChecker.isNewerVersion("v0.4.2", "0.4.2"))
        assertFalse(AppUpdateChecker.isNewerVersion("v0.4.2+build31", "0.4.2"))
        assertFalse(AppUpdateChecker.isNewerVersion("0.4.2.0", "0.4.2"))
        assertFalse(AppUpdateChecker.isNewerVersion("v0.4.2-rc.1", "0.4.2"))
        assertFalse(AppUpdateChecker.isNewerVersion("0.4.1", "0.4.2"))
        assertFalse(AppUpdateChecker.isNewerVersion("nightly", "0.4.2"))
        assertNull(AppUpdateChecker.parseVersion("build-2"))
    }

    @Test fun parseReleaseJsonPrefersUsagetimeApkAndSanitizesUrlsAndNullBody() {
        val json = """
            {
              "tag_name": "v0.4.3",
              "name": "用时记 0.4.3",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/vanvan-program/usagetime/releases/tag/v0.4.3",
              "published_at": "2026-11-16T08:00:00Z",
              "body": null,
              "assets": [
                {
                  "name": "other-debug.apk",
                  "size": 123456,
                  "browser_download_url": "https://github.com/vanvan-program/usagetime/releases/download/v0.4.3/other-debug.apk"
                },
                {
                  "name": "untrusted.apk",
                  "size": 999999,
                  "browser_download_url": "https://evil.example.com/usagetime-0.4.3.apk"
                },
                {
                  "name": "usagetime-0.4.3.apk",
                  "size": 14155776,
                  "digest": "sha256:${"ab".repeat(32)}",
                  "browser_download_url": "https://github.com/vanvan-program/usagetime/releases/download/v0.4.3/usagetime-0.4.3.apk"
                }
              ]
            }
        """.trimIndent()

        val result = AppUpdateChecker.parseLatestReleaseJson("0.4.2", json)
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val release = (result as UpdateCheckResult.UpdateAvailable).release
        assertEquals("v0.4.3", release.tagName)
        assertEquals("0.4.3", release.versionName)
        assertEquals("用时记 0.4.3", release.title)
        assertEquals("", release.notes)
        assertEquals("https://github.com/vanvan-program/usagetime/releases/tag/v0.4.3", release.releaseUrl)
        assertEquals("usagetime-0.4.3.apk", release.apkName)
        assertEquals(14_155_776L, release.apkSizeBytes)
        assertEquals("ab".repeat(32), release.apkSha256)
        assertEquals(
            "https://github.com/vanvan-program/usagetime/releases/download/v0.4.3/usagetime-0.4.3.apk",
            release.apkUrl
        )
    }

    @Test fun parseReleaseJsonIgnoresDraftsPrereleasesAndUnsafeLinks() {
        val draftJson = """{"tag_name":"v0.5.0","draft":true,"prerelease":false}"""
        assertEquals(UpdateCheckResult.NoReleaseYet, AppUpdateChecker.parseLatestReleaseJson("0.4.2", draftJson))

        val untrustedHtmlAndAsset = """
            {
              "tag_name": "v0.4.3",
              "html_url": "http://github.com/vanvan-program/usagetime/releases",
              "assets": [
                {
                  "name": "usagetime-0.4.3.apk",
                  "size": 1000,
                  "browser_download_url": "https://attacker.invalid/usagetime-0.4.3.apk"
                }
              ]
            }
        """.trimIndent()
        val parsed = AppUpdateChecker.parseLatestReleaseJson("0.4.2", untrustedHtmlAndAsset)
        val release = (parsed as UpdateCheckResult.UpdateAvailable).release
        assertEquals(AppUpdateChecker.RELEASES_WEB_URL, release.releaseUrl)
        assertNull(release.apkUrl)
        assertNull(release.apkName)
    }

    @Test fun urlWhitelistAndSizeFormattingEnforceBoundaries() {
        assertTrue(AppUpdateChecker.isAllowedExternalUrl("https://github.com/vanvan-program/usagetime/releases"))
        assertTrue(AppUpdateChecker.isAllowedExternalUrl("https://objects.githubusercontent.com/github-production-release-asset/foo.apk"))
        assertFalse(AppUpdateChecker.isAllowedExternalUrl("http://github.com/vanvan-program/usagetime"))
        assertFalse(AppUpdateChecker.isAllowedExternalUrl("https://user:pass@" + "github.com/vanvan-program/usagetime"))
        assertFalse(AppUpdateChecker.isAllowedExternalUrl("https://github.com:8443/vanvan-program/usagetime"))
        assertFalse(AppUpdateChecker.isAllowedExternalUrl("https://notgithub.com/vanvan-program/usagetime"))
        assertFalse(AppUpdateChecker.isAllowedExternalUrl("file:///sdcard/Download/app.apk"))

        assertNull(AppUpdateChecker.formatApkSize(null))
        assertNull(AppUpdateChecker.formatApkSize(0L))
        assertEquals("512 KB", AppUpdateChecker.formatApkSize(524_288L))
        assertEquals("13.2 MB", AppUpdateChecker.formatApkSize(13_857_274L))
    }

    @Test fun httpClientChecksUpdates404RateLimitAndOversizedBody() {
        val updateJson = """
            {
              "tag_name": "v0.5.0",
              "name": "用时记 0.5.0",
              "html_url": "https://github.com/vanvan-program/usagetime/releases/tag/v0.5.0",
              "body": "新增功能",
              "assets": [
                {
                  "name": "usagetime-0.5.0.apk",
                  "size": 13500000,
                  "browser_download_url": "https://github.com/vanvan-program/usagetime/releases/download/v0.5.0/usagetime-0.5.0.apk"
                }
              ]
            }
        """.trimIndent()

        val receivedHeaders = withSingleHttpResponse(
            statusLine = "200 OK",
            body = updateJson
        ) { url ->
            val result = runBlocking { AppUpdateChecker.checkLatestReleaseSuspend("0.4.2", apiUrl = url) }
            assertTrue(result is UpdateCheckResult.UpdateAvailable)
            assertEquals("0.5.0", (result as UpdateCheckResult.UpdateAvailable).release.versionName)
        }
        assertEquals("UsageTime/0.4.2", receivedHeaders["user-agent"])
        assertEquals("application/vnd.github+json", receivedHeaders["accept"])

        withSingleHttpResponse(statusLine = "200 OK", body = updateJson) { url ->
            val upToDate = AppUpdateChecker.checkLatestRelease("0.5.0", apiUrl = url)
            assertTrue(upToDate is UpdateCheckResult.UpToDate)
        }

        withSingleHttpResponse(statusLine = "404 Not Found", body = """{"message":"Not Found"}""") { url ->
            assertEquals(UpdateCheckResult.NoReleaseYet, AppUpdateChecker.checkLatestRelease("0.4.2", apiUrl = url))
        }

        val rateLimitError = assertThrows(IllegalStateException::class.java) {
            withSingleHttpResponse(
                statusLine = "403 Forbidden",
                extraHeaders = listOf("X-RateLimit-Remaining: 0"),
                body = """{"message":"API rate limit exceeded"}"""
            ) { url ->
                AppUpdateChecker.checkLatestRelease("0.4.2", apiUrl = url)
            }
        }
        assertTrue(rateLimitError.message?.contains("上限") == true)

        val oversizedError = assertThrows(IllegalStateException::class.java) {
            withSingleHttpResponse(statusLine = "200 OK", body = """{"tag_name":"v0.5.0","body":"${"a".repeat(200)}"}""") { url ->
                AppUpdateChecker.checkLatestRelease("0.4.2", apiUrl = url, maxResponseBytes = 64)
            }
        }
        assertTrue(oversizedError.message?.contains("过大") == true)
    }

    @Test fun mirrorWithoutCurrentReleaseFallsBackToOfficial() = runBlocking {
        val official = UpdateCheckResult.UpToDate("0.4.6", AppUpdateChecker.RELEASES_WEB_URL)
        val failures = listOf(
            UpdateCheckResult.NoReleaseYet,
            UpdateCheckResult.UpToDate("0.4.5", AppUpdateChecker.RELEASES_WEB_URL)
        )
        for (failure in failures) {
            val sources = mutableListOf<String>()
            val result = UpdateService.checkLatest("0.4.6", UpdateSourceMode.MIRROR, onSource = {
                sources += it.id
            }) { _, url ->
                if (url == AppUpdateChecker.LATEST_RELEASE_API_URL) official else failure
            }
            assertEquals(official, result)
            assertEquals(listOf("gh-proxy", "github"), sources)
        }
    }

    @Test fun malformedMetadataTriggersMirrorFallback() = runBlocking {
        val sources = mutableListOf<String>()
        val result = UpdateService.checkLatest("0.4.5", UpdateSourceMode.AUTO, onSource = {
            sources += it.id
        }) { current, url ->
            val json = if (url == AppUpdateChecker.LATEST_RELEASE_API_URL) "{}" else """{"tag_name":"v0.4.6"}"""
            AppUpdateChecker.parseLatestReleaseJson(current, json)
        }
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        assertEquals(listOf("github", "gh-proxy"), sources)
    }

    private fun withSingleHttpResponse(
        statusLine: String,
        body: String,
        extraHeaders: List<String> = emptyList(),
        block: (String) -> Unit
    ): Map<String, String> {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 8000 }
        val capturedHeaders = mutableMapOf<String, String>()
        val serverError = AtomicReference<Throwable?>()
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val worker = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    fun readLine(): String {
                        val out = ByteArrayOutputStream()
                        while (true) {
                            val next = input.read()
                            if (next < 0) break
                            if (next == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
                            out.write(next)
                        }
                        return out.toString("UTF-8")
                    }
                    readLine() // request line
                    while (true) {
                        val line = readLine()
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            capturedHeaders[line.take(colon).trim().lowercase()] = line.substring(colon + 1).trim()
                        }
                    }
                    val headerLines = buildString {
                        append("HTTP/1.1 $statusLine\r\n")
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("Content-Length: ${bodyBytes.size}\r\n")
                        append("Connection: close\r\n")
                        extraHeaders.forEach { append("$it\r\n") }
                        append("\r\n")
                    }
                    socket.getOutputStream().apply {
                        write(headerLines.toByteArray(Charsets.US_ASCII))
                        write(bodyBytes)
                        flush()
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            } finally {
                server.close()
            }
        }
        worker.start()
        try {
            block("http://127.0.0.1:${server.localPort}/repos/vanvan-program/usagetime/releases/latest")
        } finally {
            worker.join(8000)
            serverError.get()?.let { throw it }
        }
        return capturedHeaders
    }
}
