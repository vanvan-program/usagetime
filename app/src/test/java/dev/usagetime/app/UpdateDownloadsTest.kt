package dev.usagetime.app

import dev.usagetime.app.sync.ApkDownloader
import dev.usagetime.app.sync.DownloadProgress
import dev.usagetime.app.sync.TrustedHosts
import dev.usagetime.app.sync.UpdateDownloadException
import dev.usagetime.app.sync.UpdateDownloadState
import dev.usagetime.app.sync.UpdateFormatting
import dev.usagetime.app.sync.UpdateMirror
import dev.usagetime.app.sync.UpdateMirrors
import dev.usagetime.app.sync.UpdateSourceMode
import dev.usagetime.app.sync.trustedHostInterceptor
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Collections

class UpdateDownloadsTest {
    private val githubApk =
        "https://github.com/vanvan-program/usagetime/releases/download/v0.4.4/usagetime-0.4.4.apk"

    @Test fun downloadCandidatesFollowModeAndPrefixEveryMirror() {
        val official = UpdateMirrors.OFFICIAL
        val auto = UpdateMirrors.downloadCandidates(githubApk, UpdateSourceMode.AUTO)
        assertEquals(listOf("github", "gh-proxy", "ghfast", "ghproxy-net"), auto.map { it.first.id })
        assertEquals(githubApk, auto.first().second)
        assertTrue(auto.drop(1).all { (mirror, url) -> url == mirror.proxyPrefix + githubApk })
        val mirrorFirst = UpdateMirrors.downloadCandidates(githubApk, UpdateSourceMode.MIRROR)
        assertEquals(auto.drop(1) + auto.first(), mirrorFirst)
        assertEquals(listOf(official to githubApk), UpdateMirrors.downloadCandidates(githubApk, UpdateSourceMode.GITHUB))

        val apiAuto = UpdateMirrors.apiCandidates(UpdateSourceMode.AUTO)
        assertEquals(listOf("github", "gh-proxy"), apiAuto.map { it.first.id })
        assertTrue(apiAuto.first().second.endsWith("/releases/latest"))
        assertEquals("https://gh-proxy.com/${apiAuto.first().second}", apiAuto.last().second)
        assertEquals(apiAuto.reversed(), UpdateMirrors.apiCandidates(UpdateSourceMode.MIRROR))
        assertEquals(apiAuto.take(1), UpdateMirrors.apiCandidates(UpdateSourceMode.GITHUB))
    }
    @Test fun allowedDownloadUrlsOnlyTrustGithubAndConfiguredMirrors() {
        assertTrue(UpdateMirrors.isAllowedDownloadUrl(githubApk))
        assertTrue(UpdateMirrors.isAllowedDownloadUrl("https://ghfast.top/$githubApk"))
        assertTrue(UpdateMirrors.isAllowedDownloadUrl("https://ghproxy.net/$githubApk"))
        assertFalse(UpdateMirrors.isAllowedDownloadUrl(githubApk.replace("https://", "http://")))
        assertFalse(UpdateMirrors.isAllowedDownloadUrl("https://evil.example.com/usagetime.apk"))
        assertFalse(UpdateMirrors.isAllowedDownloadUrl("https://user:pass" + "@" + "ghfast.top/$githubApk"))
    }

    @Test fun downloadProgressAndFormatting() {
        assertEquals(0.5f, DownloadProgress(50L, 100L).fraction!!, 0.0001f)
        assertEquals(50, DownloadProgress(50L, 100L).percent)
        assertNull(DownloadProgress(50L, null).percent)
        assertNull(DownloadProgress(0L, 0L).fraction)

        assertEquals("1.5 MB", UpdateFormatting.bytes(1_572_864L))
        assertEquals("512 KB", UpdateFormatting.bytes(524_288L))
        assertEquals("0 B", UpdateFormatting.bytes(0L))
        assertEquals("2.0 MB/s", UpdateFormatting.speed(2_097_152L))
        assertEquals("900 KB/s", UpdateFormatting.speed(921_600L))
        assertNull(UpdateFormatting.speed(null))
    }

    @Test fun downloadStreamsBytesAndReportsProgressUntilReady() {
        val payload = fakeApk(64 * 1024)
        val server = OneShotServer("200 OK", payload)
        val directory = tempDirectory()
        try {
            val file = File(directory, "usagetime-test.apk")
            val states = Collections.synchronizedList(mutableListOf<UpdateDownloadState>())
            val result = runBlocking {
                ApkDownloader.downloadTo(
                    file = file,
                    candidates = listOf(UpdateMirror("test", "测试源", null) to server.url("/usagetime.apk")),
                    expectedSize = payload.size.toLong(),
                    client = OkHttpClient(),
                    onState = { states.add(it) }
                )
            }
            assertTrue(result.readBytes().contentEquals(payload))
            assertTrue(states.any { it is UpdateDownloadState.Verifying })
            assertTrue(states.any { it is UpdateDownloadState.Ready })
            val running = states.filterIsInstance<UpdateDownloadState.Running>()
            assertTrue(running.isNotEmpty())
            assertEquals(payload.size.toLong(), running.last().progress.bytesRead)
            assertEquals(payload.size.toLong(), running.last().progress.totalBytes)
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test fun downloadFallsBackOnHttpErrorAndRejectsCorruptionWithMatchingSizeAndHeader() {
        val payload = fakeApk(8 * 1024)
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val missing = OneShotServer("404 Not Found", byteArrayOf())
        val corrupt = OneShotServer("200 OK", payload.copyOf().apply { this[lastIndex] = 99 })
        val server = OneShotServer("200 OK", payload)
        val directory = tempDirectory()
        try {
            val file = File(directory, "usagetime-fallback.apk")
            val states = Collections.synchronizedList(mutableListOf<UpdateDownloadState>())
            val result = runBlocking {
                ApkDownloader.downloadTo(
                    file = file,
                    candidates = listOf(
                        UpdateMirror("bad", "故障源", null) to missing.url("/usagetime.apk"),
                        UpdateMirror("corrupt", "损坏文件", null) to corrupt.url("/usagetime.apk"),
                        UpdateMirror("good", "可用源", null) to server.url("/usagetime.apk")
                    ),
                    expectedSize = payload.size.toLong(),
                    client = OkHttpClient(),
                    expectedSha256 = hash,
                    onState = { states.add(it) }
                )
            }
            assertTrue(result.readBytes().contentEquals(payload))
            assertTrue(states.filterIsInstance<UpdateDownloadState.Running>().any { it.sourceLabel == "可用源" })
            assertEquals(listOf(file.name), directory.list()!!.toList())
        } finally {
            missing.close()
            corrupt.close()
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test fun validateApkRejectsWrongMagicAndSize() {
        val directory = tempDirectory()
        try {
            val notApk = File(directory, "bad.apk").apply { writeText("hello") }
            assertThrows(IllegalStateException::class.java) { ApkDownloader.validateApk(notApk, null) }

            val apk = File(directory, "good.apk").apply { writeBytes(fakeApk(100)) }
            assertThrows(IllegalStateException::class.java) { ApkDownloader.validateApk(apk, 99L) }
            ApkDownloader.validateApk(apk, apk.length())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun friendlyDownloadErrorsAreUserReadable() {
        assertEquals("连接超时", ApkDownloader.friendlyError(java.net.SocketTimeoutException()))
        assertEquals("无法连接", ApkDownloader.friendlyError(java.net.UnknownHostException("x")))
        assertEquals("HTTP 500", ApkDownloader.friendlyError(java.io.IOException("HTTP 500")))
    }

    @Test fun trustedHostPolicyRejectsHttpForeignHostsAndAcceptsGithubAndMirrors() {
        assertTrue(TrustedHosts.allowed("https://github.com/vanvan-program/usagetime/releases/download/v0.4.4/usagetime-0.4.4.apk".toHttpUrl()))
        assertTrue(TrustedHosts.allowed("https://api.github.com/repos/vanvan-program/usagetime/releases/latest".toHttpUrl()))
        assertTrue(TrustedHosts.allowed("https://objects.githubusercontent.com/github-production-release-asset/a.apk".toHttpUrl()))
        assertTrue(TrustedHosts.allowed("https://gh-proxy.com/https://github.com/vanvan-program/usagetime/releases".toHttpUrl()))
        assertFalse(TrustedHosts.allowed("http://github.com/vanvan-program/usagetime".toHttpUrl()))
        assertFalse(TrustedHosts.allowed("https://evil.example.com/usagetime-0.4.4.apk".toHttpUrl()))
        assertFalse(TrustedHosts.allowed("https://github.com.evil.example/usagetime-0.4.4.apk".toHttpUrl()))
    }

    @Test fun redirectOutsideHostPolicyFailsWithoutProducingReadyFile() {
        val payload = fakeApk(8 * 1024)
        val destination = OneShotServer("200 OK", payload)
        val redirect = OneShotServer(
            "302 Found",
            ByteArray(0),
            listOf("Location: ${destination.url("/usagetime.apk")}")
        )
        val directory = tempDirectory()
        try {
            val file = File(directory, "usagetime-redirected.apk")
            val states = Collections.synchronizedList(mutableListOf<UpdateDownloadState>())
            val client = OkHttpClient.Builder()
                .addNetworkInterceptor(trustedHostInterceptor { url -> url.port == redirect.port })
                .build()
            val failure = assertThrows(UpdateDownloadException::class.java) {
                runBlocking {
                    ApkDownloader.downloadTo(
                        file = file,
                        candidates = listOf(UpdateMirror("redirect", "跳转源", null) to redirect.url("/usagetime.apk")),
                        expectedSize = null,
                        client = client,
                        onState = { states.add(it) }
                    )
                }
            }
            assertTrue(failure.attempts.single().contains("可信"))
            assertTrue(states.none { it is UpdateDownloadState.Ready })
            assertFalse(file.exists())
        } finally {
            redirect.close()
            destination.close()
            directory.deleteRecursively()
        }
    }

    @Test fun hostPolicyAcceptsTheSourceTheAppChoseToTalkTo() {
        val payload = fakeApk(16 * 1024)
        val server = OneShotServer("200 OK", payload)
        val directory = tempDirectory()
        try {
            val file = File(directory, "usagetime-allowed.apk")
            val client = OkHttpClient.Builder()
                .addNetworkInterceptor(trustedHostInterceptor { url -> url.host == "127.0.0.1" })
                .build()
            val result = runBlocking {
                ApkDownloader.downloadTo(
                    file = file,
                    candidates = listOf(UpdateMirror("local", "本地源", null) to server.url("/usagetime.apk")),
                    expectedSize = payload.size.toLong(),
                    client = client,
                    onState = {}
                )
            }
            assertTrue(result.readBytes().contentEquals(payload))
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test fun productionClientRejectsUntrustedSourceEvenWhenItAnswers() {
        val server = OneShotServer("200 OK", fakeApk(4 * 1024))
        val directory = tempDirectory()
        try {
            val file = File(directory, "usagetime-blocked.apk")
            val failure = assertThrows(UpdateDownloadException::class.java) {
                runBlocking {
                    ApkDownloader.downloadTo(
                        file = file,
                        candidates = listOf(UpdateMirror("loopback", "回环源", null) to server.url("/usagetime.apk")),
                        expectedSize = null,
                        client = ApkDownloader.client(),
                        onState = {}
                    )
                }
            }
            assertTrue(failure.attempts.single().contains("可信"))
            assertFalse(file.exists())
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    private fun tempDirectory(): File =
        File(System.getProperty("java.io.tmpdir"), "usagetime-update-${System.nanoTime()}")
            .apply { mkdirs() }

    private fun fakeApk(size: Int): ByteArray {
        require(size >= 4)
        val bytes = ByteArray(size)
        bytes[0] = 0x50
        bytes[1] = 0x4B
        bytes[2] = 0x03
        bytes[3] = 0x04
        for (index in 4 until size) bytes[index] = (index % 251).toByte()
        return bytes
    }

    private class OneShotServer(
        private val statusLine: String,
        private val body: ByteArray,
        private val extraHeaders: List<String> = emptyList()
    ) {
        private val server = ServerSocket(0)
        val port: Int = server.localPort
        private val worker = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val header = "HTTP/1.1 $statusLine\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        extraHeaders.joinToString("") { "$it\r\n" } +
                        "Connection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(header.toByteArray(Charsets.ISO_8859_1))
                        write(body)
                        flush()
                    }
                }
            } catch (_: Throwable) {
                // The client may abort the connection; nothing to report here.
            } finally {
                runCatching { server.close() }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }

        fun url(path: String): String = "http://127.0.0.1:$port$path"

        fun close() {
            runCatching { server.close() }
            runCatching { worker.join(2000) }
        }
    }
}