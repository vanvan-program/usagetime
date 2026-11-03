package dev.usagetime.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.usagetime.app.data.BackupStreamReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupStreamReaderTest {
    private class RecordingSink : BackupStreamReader.Sink {
        val rows = mutableListOf<Pair<String, JSONObject>>()
        val ended = mutableListOf<String>()
        val events = mutableListOf<String>()
        var checkpoints = 0

        override suspend fun row(table: String, row: JSONObject) {
            rows += table to row
            events += "row:$table"
        }

        override suspend fun endArray(table: String) {
            ended += table
            events += "end:$table"
        }

        override suspend fun checkpoint() {
            checkpoints++
            events += "checkpoint"
        }
    }

    private suspend fun parse(text: String, sink: RecordingSink = RecordingSink()): RecordingSink {
        BackupStreamReader.read(text.byteInputStream(), sink)
        return sink
    }

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(text.toByteArray()) }
        }.toByteArray()

    @Test fun plainAndGzipStreamsParseWithTheSameRows() = runBlocking {
        val text = """{"format":"usagetime-backup","version":3,"apps":[{"app":"fixture","label":"Fixture"}]}"""
        val plain = parse(text)
        val gzip = RecordingSink()
        BackupStreamReader.read(gzip(text).inputStream(), gzip)

        assertEquals(listOf("apps" to "fixture"), plain.rows.map { it.first to it.second.getString("app") })
        assertEquals(plain.rows.map { it.first to it.second.toString() }, gzip.rows.map { it.first to it.second.toString() })
        assertEquals(listOf("apps"), plain.ended)
        assertEquals(listOf("apps"), gzip.ended)
    }

    @Test fun onlyKnownArraysReachTheSinkAndCallerOrderIsPreserved() = runBlocking {
        val tables = listOf(
            "intervals", "coverage", "apps", "usage_events", "usage_intervals",
            "history_days", "history_seals", "retention_state", "sync_checkpoints", "diagnostic_logs",
        )
        val arrays = tables.reversed().joinToString(",") { "\"$it\":[]" }
        val sink = parse("""{"format":"usagetime-backup","version":1,$arrays,"future":[{"ignored":true}]}""")

        assertEquals(tables.reversed(), sink.ended)
        assertTrue(sink.rows.isEmpty())
    }

    @Test fun scalarNullsBooleansNumbersAndStringsKeepTheirJsonShape() = runBlocking {
        val sink = parse(
            """{"format":"usagetime-backup","version":2,"apps":[{"empty":null,"flag":true,"count":-7,"text":"a \"b\" 中文"}]}"""
        )
        val row = sink.rows.single().second
        assertTrue(row.isNull("empty"))
        assertEquals(true, row.getBoolean("flag"))
        assertEquals(-7L, row.getLong("count"))
        assertEquals("a \"b\" 中文", row.getString("text"))
    }

    @Test fun nestedValuesDecimalsAndOversizedNamesAreRejected() = runBlocking {
        val nested = """{"format":"usagetime-backup","version":1,"apps":[{"nested":{}}]}"""
        val decimal = """{"format":"usagetime-backup","version":1,"apps":[{"count":1.5}]}"""
        val oversized = """{"format":"usagetime-backup","version":1,"apps":[{"${"x".repeat(81)}":1}]}"""
        assertTrue(runCatching { parse(nested) }.isFailure)
        assertTrue(runCatching { parse(decimal) }.isFailure)
        assertTrue(runCatching { parse(oversized) }.isFailure)
    }

    @Test fun rowFieldCountLimitRemainsThirtyOne() = runBlocking {
        val allowed = (1..31).joinToString(",") { "\"f$it\":$it" }
        val rejected = (1..32).joinToString(",") { "\"f$it\":$it" }
        assertEquals(31, parse(
            """{"format":"usagetime-backup","version":1,"apps":[$allowed]}"""
        ).rows.single().second.length())
        assertTrue(runCatching {
            parse("""{"format":"usagetime-backup","version":1,"apps":[{${rejected}}]}""")
        }.isFailure)
    }

    @Test fun formatVersionAndTrailingDocumentContentAreValidated() = runBlocking {
        val valid = """{"format":"usagetime-backup","version":3}"""
        parse(valid)
        for (text in listOf(
            """{"format":"other","version":3}""",
            """{"format":"usagetime-backup","version":0}""",
            """{"format":"usagetime-backup","version":4}""",
            """$valid {}""",
        )) {
            assertTrue(text, runCatching { parse(text) }.isFailure)
        }
    }

    @Test fun emptyOrTruncatedGzipFailsWithoutClosingCallerInput() = runBlocking {
        val payload = gzip("""{"format":"usagetime-backup","version":3,"apps":[]}""")
        val owned = object : ByteArrayInputStream(payload) {
            var closed = false
            override fun close() {
                closed = true
                super.close()
            }
        }
        BackupStreamReader.read(owned, RecordingSink())
        assertFalse(owned.closed)

        val truncated = payload.copyOf(payload.size - 4)
        assertTrue(runCatching {
            BackupStreamReader.read(truncated.inputStream(), RecordingSink())
        }.isFailure)
    }

    @Test fun thousandthRecordRunsCancellationAndCheckpointBeforeRow() = runBlocking {
        val rows = (1..1000).joinToString(",") { "{}" }
        val sink = parse("""{"format":"usagetime-backup","version":1,"usage_intervals":[$rows]}""")
        assertEquals(1, sink.checkpoints)
        assertEquals(1000, sink.rows.size)
        assertEquals("checkpoint", sink.events[999])
        assertEquals("row:usage_intervals", sink.events[1000])
    }

    @Test fun cancellationStopsAtTheThousandthRecord() = runBlocking {
        val rows = (1..1000).joinToString(",") { "{}" }
        val result = runCatching {
            withContext(Job()) {
                val job = currentCoroutineContext()[Job]!!
                val sink = object : BackupStreamReader.Sink {
                    var seen = 0
                    override suspend fun row(table: String, row: JSONObject) {
                        seen++
                        if (seen == 999) job.cancel()
                    }
                    override suspend fun endArray(table: String) = Unit
                    override suspend fun checkpoint() = Unit
                }
                BackupStreamReader.read(
                    """{"format":"usagetime-backup","version":1,"usage_intervals":[$rows]}""".byteInputStream(),
                    sink,
                )
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
}
