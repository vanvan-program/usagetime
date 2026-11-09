package dev.usagetime.app

import dev.usagetime.app.sync.PhoneDav
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

class PhoneDavTest {
    @Test fun cancelledPullCannotStartAnotherNetworkRequest() {
        var checks = 0
        val dav = PhoneDav(URI("http://127.0.0.1:1/"), "qa", "synthetic-password") {
            checks++
            throw kotlinx.coroutines.CancellationException("合成取消")
        }
        listOf<() -> Unit>(
            { dav.head("manifest") },
            { dav.get("day", 100) },
            { dav.propfind("") },
            { dav.put("day", byteArrayOf(1)) }
        ).forEach { request ->
            assertThrows(kotlinx.coroutines.CancellationException::class.java) { request() }
        }
        assertEquals(4, checks)
    }

    private fun runServer(codes: List<Int>, action: (PhoneDav) -> Unit): List<Pair<String, Map<String, String>>> {
        val server = ServerSocket(0).apply { soTimeout = 8000 }
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try {
                for (code in codes) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().buffered()
                        fun line(): String {
                            val bytes = java.io.ByteArrayOutputStream()
                            while (true) {
                                val next = input.read()
                                check(next >= 0) { "请求意外结束" }
                                if (next == 10) return bytes.toString("UTF-8").trimEnd('\r')
                                bytes.write(next)
                            }
                        }
                        val first = line()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                        }
                        repeat(headers["content-length"]?.toInt() ?: 0) { check(input.read() >= 0) }
                        requests += first to headers
                        socket.getOutputStream().write("HTTP/1.1 $code Result\r\nContent-Length: 0\r\nLocation: http://127.0.0.1:${server.localPort}/redirect\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        thread.start()
        try { action(PhoneDav(URI("http://127.0.0.1:${server.localPort}/usagetime-v1/"), "qa", "pass")) }
        finally { thread.join(10_000); server.close() }
        failure.get()?.let { throw AssertionError(it) }
        return requests
    }
    @Test fun supportsDavMethodsAndAtomicReplace() {
        val requests = runServer(listOf(201, 201, 204)) { dav ->
            dav.mkdir("")
            dav.put("device/day.utday", byteArrayOf(1, 2, 3))
        }
        assertEquals(listOf("MKCOL /usagetime-v1/ HTTP/1.1", "PUT /usagetime-v1/device/.tmp-day.utday HTTP/1.1",
            "MOVE /usagetime-v1/device/.tmp-day.utday HTTP/1.1"), requests.map { it.first })
        assertEquals("Basic cWE6cGFzcw==", requests.first().second["authorization"])
        assertTrue(requests.last().second.getValue("destination").endsWith("/usagetime-v1/device/day.utday"))
        assertEquals("T", requests.last().second["overwrite"])
    }
    @Test fun doesNotFollowRedirectWithCredentials() {
        val requests = runServer(listOf(302)) { dav ->
            assertThrows(IllegalStateException::class.java) { dav.put("file", byteArrayOf(1)) }
        }
        assertEquals(1, requests.size)
    }
    @Test fun failedMoveIsNotReportedAsSuccess() {
        runServer(listOf(201, 409)) { dav ->
            val error = assertThrows(IllegalStateException::class.java) { dav.put("file", byteArrayOf(1)) }
            assertTrue(error.message!!.contains("409"))
        }
    }
}
