package dev.usagetime.app

import dev.usagetime.app.data.RawEventBatchCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RawEventBatchCollectorTest {
    private val sixHours = 6 * 3_600_000L
    private class Fixture {
        val calls = mutableListOf<String>()
        val writes = mutableListOf<List<Int>>()
        val inserted = mutableListOf<Int>()
        val seen = mutableListOf<Int>()
        var source: (Long, Long) -> Sequence<Int> = { _, _ -> emptySequence() }
        var failureAt = ""
        val failure = CancellationException("fixture")
        suspend fun collect(start: Long, end: Long) = RawEventBatchCollector.collect(
            start, end,
            read = { from, until -> calls += "read:$from:$until"; source(from, until) },
            write = {
                calls += "write:${it.size}"
                if (failureAt == "write") throw failure
                writes += it.toList()
                it.map { n -> if (n % 2 == 0) n.toLong() else -1L }
            },
            onRow = { seen += it; if (failureAt == "row") throw failure },
            onInserted = { inserted += it },
            check = { calls += "check"; if (failureAt == "check") throw failure }
        )
    }
    @Test fun emptyAndReversedRangesDoNothing() = runBlocking {
        val f = Fixture(); f.collect(5, 5); f.collect(6, 5)
        assertTrue(f.calls.isEmpty())
    }
    @Test fun windowsAreSixHoursWithExactPartialTail() = runBlocking {
        val f = Fixture(); f.collect(7, 7 + 2 * sixHours + 3)
        assertEquals(listOf("check", "read:7:${7 + sixHours}",
            "check", "read:${7 + sixHours}:${7 + 2 * sixHours}",
            "check", "read:${7 + 2 * sixHours}:${10 + 2 * sixHours}"), f.calls)
        assertTrue(f.writes.isEmpty())
    }
    @Test fun thresholdAndTailPreserveOrderAndInsertedIds() = runBlocking {
        val f = Fixture(); f.source = { _, _ -> (0..2000).asSequence() }; f.collect(0, 1)
        assertEquals(listOf(1000, 1000, 1), f.writes.map { it.size })
        assertEquals((0..2000).toList(), f.writes.flatten())
        assertEquals((0..2000).toList(), f.seen)
        assertEquals((0..2000 step 2).toList(), f.inserted)
        assertEquals(listOf("check", "read:0:1", "write:1000", "check",
            "write:1000", "check", "write:1", "check"), f.calls)
    }
    @Test fun exactThresholdDoesNotWriteAnEmptyTail() = runBlocking {
        val f = Fixture(); f.source = { _, _ -> (0 until 1000).asSequence() }; f.collect(0, 1)
        assertEquals(listOf(1000), f.writes.map { it.size })
        assertEquals(2, f.calls.count { it == "check" })
    }
    @Test fun batchesNeverLeakAcrossWindows() = runBlocking {
        val f = Fixture(); f.source = { from, _ -> sequenceOf(from.toInt()) }; f.collect(0, sixHours + 1)
        assertEquals(listOf(listOf(0), listOf(sixHours.toInt())), f.writes)
    }
    @Test fun callbackCancellationStopsWithoutWrappingOrLaterReads() = runBlocking {
        for (at in listOf("check", "row", "write")) {
            val f = Fixture(); f.failureAt = at; f.source = { _, _ -> sequenceOf(0, 1) }
            try { f.collect(0, sixHours + 1); fail() }
            catch (e: CancellationException) { assertSame(f.failure, e) }
            assertTrue(f.inserted.isEmpty())
            assertTrue(f.writes.isEmpty())
            assertTrue(f.calls.count { it.startsWith("read:") } <= 1)
        }
    }
    @Test fun lazySourceFailureLeavesPartialBatchUnwritten() = runBlocking {
        val f = Fixture()
        val error = IllegalStateException("query")
        f.source = { _, _ -> sequence { yield(1); throw error } }
        try { f.collect(0, 1); fail() } catch (e: IllegalStateException) { assertSame(error, e) }
        assertEquals(listOf(1), f.seen)
        assertTrue(f.writes.isEmpty())
    }
}
