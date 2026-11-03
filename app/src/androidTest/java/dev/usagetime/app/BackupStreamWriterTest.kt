package dev.usagetime.app

import android.database.MatrixCursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.usagetime.app.data.BackupStreamWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupStreamWriterTest {
    private val tables = listOf(
        "intervals", "coverage", "apps", "usage_events", "usage_intervals",
        "history_days", "history_seals", "retention_state", "sync_checkpoints", "diagnostic_logs",
    )

    @Test fun emptySnapshotKeepsEnvelopeTableOrderAndQueries() = runBlocking {
        val output = ByteArrayOutputStream()
        val queries = mutableListOf<String>()
        val cursors = mutableListOf<MatrixCursor>()
        val before = System.currentTimeMillis()
        BackupStreamWriter.write(output) { sql ->
            queries += sql
            MatrixCursor(arrayOf("id")).also { cursors += it }
        }
        val after = System.currentTimeMillis()
        val text = output.toString("UTF-8")
        val json = JSONObject(text)
        assertEquals("usagetime-backup", json.getString("format"))
        assertEquals(3, json.getInt("version"))
        assertTrue(json.getLong("exportedAt") in before..after)
        assertEquals(tables.map { "SELECT * FROM $it" }, queries)
        val keys = listOf("format", "version", "exportedAt") + tables
        assertEquals(keys.toSet(), json.keys().asSequence().toSet())
        val positions = keys.map { text.indexOf("\"$it\":") }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        tables.forEach { assertEquals(0, json.getJSONArray(it).length()) }
        assertTrue(cursors.all { it.isClosed })
    }

    @Test fun cursorTypesBooleanNamesAndEscapedTextKeepExistingEncoding() = runBlocking {
        val output = ByteArrayOutputStream()
        val columns = arrayOf("missing", "big", "reliable", "provisional", "blocked", "known", "ordinary", "fraction", "text")
        val text = "quote \" slash \\ newline\n中文"
        BackupStreamWriter.write(output) { sql ->
            MatrixCursor(columns).apply {
                if (sql == "SELECT * FROM intervals") {
                    addRow(arrayOf<Any?>(null, Long.MAX_VALUE, 1L, 0L, -7L, 0L, 1L, 1.25, text))
                }
            }
        }
        val row = JSONObject(output.toString("UTF-8")).getJSONArray("intervals").getJSONObject(0)
        assertTrue(row.isNull("missing"))
        assertEquals(Long.MAX_VALUE, row.getLong("big"))
        assertEquals(true, row.get("reliable"))
        assertEquals(false, row.get("provisional"))
        assertEquals(true, row.get("blocked"))
        assertEquals(false, row.get("known"))
        assertTrue(row.get("ordinary") is Number)
        assertEquals(1, row.getInt("ordinary"))
        assertEquals("1.25", row.get("fraction"))
        assertEquals(text, row.getString("text"))
    }

    @Test fun rowAndColumnOrderAreRetained() = runBlocking {
        val output = ByteArrayOutputStream()
        BackupStreamWriter.write(output) { sql ->
            MatrixCursor(arrayOf("label", "app")).apply {
                if (sql == "SELECT * FROM apps") {
                    addRow(arrayOf("second", "b"))
                    addRow(arrayOf("first", "a"))
                }
            }
        }
        val rows = JSONObject(output.toString("UTF-8")).getJSONArray("apps")
        assertEquals(2, rows.length())
        assertEquals("b", rows.getJSONObject(0).getString("app"))
        assertEquals("a", rows.getJSONObject(1).getString("app"))
        // Inspect text because JSONObject does not promise key iteration order on Android.
        assertTrue(output.toString("UTF-8").contains("""{"label":"second","app":"b"}"""))
    }

    @Test fun outputIsFlushedAndRemainsOwnedByCaller() = runBlocking {
        val output = object : ByteArrayOutputStream() {
            var flushed = false
            var closed = false
            override fun flush() { flushed = true; super.flush() }
            override fun close() { closed = true; super.close() }
        }
        BackupStreamWriter.write(output) { MatrixCursor(arrayOf("id")) }
        assertTrue(output.flushed)
        assertFalse(output.closed)
        assertTrue(output.toString("UTF-8").endsWith("}"))
    }

    @Test fun queryFailurePropagatesAndStopsAfterClosingPreviousCursor() = runBlocking {
        val failure = IOException("query fixture")
        val first = MatrixCursor(arrayOf("id"))
        var queries = 0
        val result = runCatching {
            BackupStreamWriter.write(ByteArrayOutputStream()) {
                if (++queries == 1) first else throw failure
            }
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(2, queries)
        assertTrue(first.isClosed)
    }

    @Test fun writeFailureClosesActiveCursorWithoutClosingOutput() = runBlocking {
        val failure = IOException("output fixture")
        val cursor = MatrixCursor(arrayOf("text")).apply { addRow(arrayOf("x".repeat(40000))) }
        val output = object : OutputStream() {
            var closed = false
            override fun write(value: Int) { throw failure }
            override fun close() { closed = true }
        }
        var queries = 0
        val result = runCatching { BackupStreamWriter.write(output) { queries++; cursor } }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, queries)
        assertTrue(cursor.isClosed)
        assertFalse(output.closed)
    }

    @Test fun cancellationAtThousandthRowClosesCursorAndStopsFurtherTables() = runBlocking {
        val cursor = MatrixCursor(arrayOf("id")).apply {
            repeat(1001) { addRow(arrayOf(it)) }
        }
        var queries = 0
        val result = runCatching {
            withContext(Job()) {
                val job = currentCoroutineContext()[Job]!!
                BackupStreamWriter.write(ByteArrayOutputStream()) {
                    queries++
                    job.cancel()
                    cursor
                }
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, queries)
        assertEquals(999, cursor.position)
        assertTrue(cursor.isClosed)
    }
}
