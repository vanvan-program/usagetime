package dev.usagetime.app.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Parses the existing backup envelope; the caller owns transactions, batching and row semantics. */
internal object BackupStreamReader {
    private val arrayTables = setOf(
        "intervals", "coverage", "apps", "usage_events", "usage_intervals",
        "history_days", "history_seals", "retention_state", "sync_checkpoints", "diagnostic_logs", "app_categories",
    )

    internal interface Sink {
        suspend fun row(table: String, row: JSONObject)
        suspend fun endArray(table: String)
        suspend fun checkpoint()
    }

    suspend fun read(input: InputStream, sink: Sink) {
        val buffered = BufferedInputStream(input, 65536)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        val decoded = if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered, 65536) else buffered
        val reader = JsonReader(InputStreamReader(decoded, Charsets.UTF_8))
        reader.isLenient = false
        var format = ""
        var version = 0
        var recordCount = 0L

        fun objectRow(table: String): JSONObject {
            val obj = JSONObject()
            reader.beginObject()
            var fields = 0
            while (reader.hasNext()) {
                require(++fields < 32) { "备份字段过多" }
                val name = reader.nextName()
                require(name.length <= 80)
                when (reader.peek()) {
                    JsonToken.NULL -> {
                        reader.nextNull()
                        obj.put(name, JSONObject.NULL)
                    }
                    JsonToken.BOOLEAN -> obj.put(name, reader.nextBoolean())
                    JsonToken.NUMBER -> obj.put(name, reader.nextLong())
                    JsonToken.STRING -> {
                        val value = reader.nextString()
                        obj.put(name, DualCodec.backupString(table, name, value))
                    }
                    else -> error("备份包含不支持的字段")
                }
            }
            reader.endObject()
            return obj
        }

        reader.beginObject()
        while (reader.hasNext()) when (val key = reader.nextName()) {
            "format" -> format = reader.nextString()
            "version" -> version = reader.nextInt()
            "exportedAt" -> reader.skipValue()
            in arrayTables -> {
                reader.beginArray()
                while (reader.hasNext()) {
                    recordCount++
                    if (recordCount % 1000 == 0L) {
                        currentCoroutineContext().ensureActive()
                        sink.checkpoint()
                    }
                    sink.row(key, objectRow(key))
                }
                sink.endArray(key)
                reader.endArray()
            }
            else -> reader.skipValue()
        }
        reader.endObject()
        require(format == "usagetime-backup" && version in 1..3) { "不是受支持的用时记备份" }
        require(reader.peek() == JsonToken.END_DOCUMENT) { "备份存在额外内容" }
        if (decoded is GZIPInputStream) require(decoded.read() == -1) { "压缩备份未完整结束" }
    }
}
