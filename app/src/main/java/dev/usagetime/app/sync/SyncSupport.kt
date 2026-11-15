package dev.usagetime.app.sync

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

/** Shared HTTP/WebDAV helpers and atomic file persistence for sync and release checks. */
internal object SyncSupport {
    fun resolveWebDavRoot(text: String, namespace: String): URI {
        val base = URI(text.trim().trimEnd('/') + "/")
        require(
            base.scheme == "https" && !base.host.isNullOrBlank() &&
                base.userInfo == null && base.query == null && base.fragment == null
        ) { "请输入 HTTPS WebDAV 地址，账号密码请填在独立输入框" }
        val normalized = namespace.trim('/')
        require(normalized.isNotEmpty()) { "WebDAV 命名空间不能为空" }
        return base.resolve("$normalized/")
    }

    fun writeAtomicBytes(file: AtomicFile, bytes: ByteArray) {
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun readLimitedBytes(
        input: InputStream,
        maxBytes: Int,
        overflowMessage: String,
        onChunk: () -> Unit = {}
    ): ByteArray {
        require(maxBytes >= 0) { "Byte limit must be non-negative" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            onChunk()
            val remainingPlusOne = maxBytes.toLong() - output.size() + 1L
            val toRead = minOf(buffer.size.toLong(), remainingPlusOne).toInt().coerceAtLeast(1)
            val count = input.read(buffer, 0, toRead)
            if (count <= 0) break
            output.write(buffer, 0, count)
            check(output.size() <= maxBytes) { overflowMessage }
        }
        return output.toByteArray()
    }
}
