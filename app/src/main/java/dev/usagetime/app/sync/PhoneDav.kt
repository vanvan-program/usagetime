package dev.usagetime.app.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit

internal class PhoneDav(
    private val root: URI,
    user: String,
    password: String,
    private val beforeRequest: () -> Unit = {}
) {
    private val authorization = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()

    fun request(path: String, method: String, bytes: ByteArray? = null, headers: Map<String, String> = emptyMap()): Int {
        beforeRequest()
        val builder = Request.Builder().url(root.resolve(path).toURL()).header("Authorization", authorization)
            .header("Content-Type", "application/octet-stream").method(method, bytes?.toRequestBody())
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.newCall(builder.build()).execute().use { it.code }
    }
    fun head(path: String): Int = request(path, "HEAD")
    fun get(path: String, maxBytes: Int): ByteArray =
        read(path, "GET", null, emptyMap(), maxBytes)
    fun propfind(path: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray =
        read(
            path,
            "PROPFIND",
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
                .toByteArray(Charsets.UTF_8),
            mapOf("Depth" to "1", "Content-Type" to "application/xml"),
            maxBytes
        )
    private fun read(path: String, method: String, bytes: ByteArray?, headers: Map<String, String>, maxBytes: Int): ByteArray {
        beforeRequest()
        val builder = Request.Builder().url(root.resolve(path).toURL()).header("Authorization", authorization)
            .method(method, bytes?.toRequestBody())
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "WebDAV 请求失败（HTTP ${response.code}）" }
            val body = response.body ?: error("WebDAV 返回为空")
            check(body.contentLength() <= maxBytes) { "同步文件超出大小上限" }
            body.byteStream().use { readLimited(it, maxBytes) }
        }
    }
    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            beforeRequest()
            val count = input.read(buffer)
            if (count <= 0) break
            output.write(buffer, 0, count)
            check(output.size() <= maxBytes) { "同步文件超出大小上限" }
        }
        return output.toByteArray()
    }
    fun mkdir(path: String) {
        val code = request(path, "MKCOL")
        check(code in 200..299 || code == 405) { "WebDAV 目录创建失败（HTTP $code）" }
    }
    fun put(path: String, encrypted: ByteArray) {
        if (root.host == "dav.jianguoyun.com") {
            val code = request(path, "PUT", encrypted)
            check(code in 200..299) { "WebDAV 上传失败（HTTP $code）" }
        } else {
            val temporary = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + ".tmp-" + path.substringAfterLast('/')
            val uploaded = request(temporary, "PUT", encrypted)
            check(uploaded in 200..299) { "WebDAV 上传失败（HTTP $uploaded）" }
            val moved = request(temporary, "MOVE", headers = mapOf("Destination" to root.resolve(path).toString(), "Overwrite" to "T"))
            check(moved in 200..299) { "WebDAV 原子替换失败（HTTP $moved），下次自动重试" }
        }
    }
}
