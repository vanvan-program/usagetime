package dev.usagetime.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupImportBatchSchedulerTest {
    private fun event(id: String) =
        StoredEvent(id, 100, "RESUME", "fixture", "Main", null, "TEST", "UTC")

    private fun slice(id: String) =
        StoredSlice(id, "PRIMARY", "fixture", 100, 200, "INFERRED", "test", "LOCAL", "", false)

    @Test fun flushUsesEventsSlicesIntervalsOrderAndClearsBatches() = runBlocking {
        val calls = mutableListOf<String>()
        var writtenEvents = emptyList<StoredEvent>()
        var writtenSlices = emptyList<StoredSlice>()
        var writtenIntervals = emptyList<SavedInterval>()
        val scheduler = BackupImportBatchScheduler<StoredEvent, StoredSlice, SavedInterval>(
            writeEvents = { values -> calls += "events"; writtenEvents = values.toList() },
            writeSlices = { values -> calls += "slices"; writtenSlices = values.toList() },
            writeIntervals = { values -> calls += "intervals"; writtenIntervals = values.toList() },
            ensureWriteSpace = { calls += "space" },
        )

        scheduler.addEvent(event("event"), 1)
        scheduler.addSlice(slice("slice"), 1)
        scheduler.addInterval(SavedInterval("fixture", 100, 200), 1)
        scheduler.flushBatches()

        assertEquals(listOf("events", "slices", "intervals"), calls)
        assertEquals(listOf(event("event")), writtenEvents)
        assertEquals(listOf(slice("slice")), writtenSlices)
        assertEquals(listOf(SavedInterval("fixture", 100, 200)), writtenIntervals)

        scheduler.flushBatches()
        assertEquals(3, calls.size)
    }

    @Test fun thousandRowsFlushAndCheckSpaceOnce() = runBlocking {
        var eventWrites = 0
        var spaceChecks = 0
        val scheduler = BackupImportBatchScheduler<StoredEvent, StoredSlice, SavedInterval>(
            writeEvents = { eventWrites++ },
            writeSlices = {},
            writeIntervals = {},
            ensureWriteSpace = { spaceChecks++ },
        )
        repeat(999) { scheduler.addEvent(event("event-$it"), 1) }
        scheduler.finishRow()
        assertEquals(0, eventWrites)
        assertEquals(0, spaceChecks)

        scheduler.addEvent(event("event-999"), 1)
        scheduler.finishRow()
        assertEquals(1, eventWrites)
        assertEquals(1, spaceChecks)
    }

    @Test fun byteBoundaryFlushesAtOneMebibyte() = runBlocking {
        var eventWrites = 0
        var spaceChecks = 0
        val scheduler = BackupImportBatchScheduler<StoredEvent, StoredSlice, SavedInterval>(
            writeEvents = { eventWrites++ },
            writeSlices = {},
            writeIntervals = {},
            ensureWriteSpace = { spaceChecks++ },
        )

        scheduler.addEvent(event("below"), 1_048_575)
        scheduler.finishRow()
        assertEquals(0, eventWrites)

        scheduler.addEvent(event("boundary"), 1)
        scheduler.finishRow()
        assertEquals(1, eventWrites)
        assertEquals(1, spaceChecks)
    }

    @Test fun thresholdCanBeReachedWithoutAddingOnCurrentRow() = runBlocking {
        var eventWrites = 0
        var spaceChecks = 0
        val scheduler = BackupImportBatchScheduler<StoredEvent, StoredSlice, SavedInterval>(
            writeEvents = { eventWrites++ },
            writeSlices = {},
            writeIntervals = {},
            ensureWriteSpace = { spaceChecks++ },
        )
        repeat(1000) { scheduler.addEvent(event("event-$it"), 1) }

        scheduler.finishRow()
        assertEquals(1, eventWrites)
        assertEquals(1, spaceChecks)
    }

    @Test fun explicitFlushResetsByteCounterWithoutCheckingSpace() = runBlocking {
        var eventWrites = 0
        var spaceChecks = 0
        val scheduler = BackupImportBatchScheduler<StoredEvent, StoredSlice, SavedInterval>(
            writeEvents = { eventWrites++ },
            writeSlices = {},
            writeIntervals = {},
            ensureWriteSpace = { spaceChecks++ },
        )
        scheduler.addEvent(event("table-end"), 1_048_576)
        scheduler.flushBatches()
        assertEquals(1, eventWrites)
        assertEquals(0, spaceChecks)

        scheduler.addEvent(event("next-table"), 1)
        scheduler.finishRow()
        assertEquals(1, eventWrites)
        assertEquals(0, spaceChecks)
    }

    @Test fun emptyFlushDoesNotInvokeCallbacks() = runBlocking {
        var callbacks = 0
        val scheduler = BackupImportBatchScheduler<StoredEvent, StoredSlice, SavedInterval>(
            writeEvents = { callbacks++ },
            writeSlices = { callbacks++ },
            writeIntervals = { callbacks++ },
            ensureWriteSpace = { callbacks++ },
        )

        scheduler.flushBatches()
        scheduler.finishRow()
        assertEquals(0, callbacks)
    }

    @Test fun callbackFailurePropagatesAndLeavesFailedBatchRetryable() = runBlocking {
        var fail = true
        var eventWrites = 0
        val scheduler = BackupImportBatchScheduler<StoredEvent, StoredSlice, SavedInterval>(
            writeEvents = {
                eventWrites++
                if (fail) error("write failed")
            },
            writeSlices = {},
            writeIntervals = {},
            ensureWriteSpace = {},
        )
        scheduler.addEvent(event("retry"), 1)

        val failure = runCatching { scheduler.flushBatches() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("write failed", failure?.message)
        assertEquals(1, eventWrites)

        fail = false
        scheduler.flushBatches()
        assertEquals(2, eventWrites)
        scheduler.flushBatches()
        assertEquals(2, eventWrites)
    }
}
