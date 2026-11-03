package dev.usagetime.app.data

import android.database.Cursor
import android.util.JsonWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Writes the existing backup format; the caller owns the snapshot lock and transaction. */
internal object BackupStreamWriter {
    suspend fun write(output: OutputStream, query: (String) -> Cursor) {
        val writer = JsonWriter(OutputStreamWriter(output, Charsets.UTF_8))
        writer.beginObject().name("format").value("usagetime-backup").name("version").value(3L).name("exportedAt").value(System.currentTimeMillis())
        val tables = listOf("intervals", "coverage", "apps", "usage_events", "usage_intervals", "history_days", "history_seals", "retention_state", "sync_checkpoints", "diagnostic_logs")
        for (table in tables) {
            writer.name(table).beginArray()
            var exported = 0L
            query("SELECT * FROM $table").use { cursor ->
                while (cursor.moveToNext()) {
                    if (++exported % 1000 == 0L) currentCoroutineContext().ensureActive()
                    writer.beginObject()
                    for (i in 0 until cursor.columnCount) {
                        val name = cursor.getColumnName(i)
                        writer.name(name)
                        when (cursor.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> writer.nullValue()
                            Cursor.FIELD_TYPE_INTEGER -> if (name in setOf("reliable", "provisional", "blocked", "known")) writer.value(cursor.getLong(i) != 0L) else writer.value(cursor.getLong(i))
                            else -> writer.value(cursor.getString(i))
                        }
                    }
                    writer.endObject()
                }
            }
            writer.endArray()
        }
        writer.endObject()
        writer.flush()
    }
}
