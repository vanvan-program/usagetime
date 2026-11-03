package dev.usagetime.app

import dev.usagetime.app.data.HistoryMaintenanceCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryMaintenanceCoordinatorTest {
    private class Fixture {
        val events = mutableListOf<String>()
        var due = true
        var failureAt = ""
        var failure: Exception = IllegalArgumentException("fixture")
        var warned: Exception? = null
        fun step(name: String) {
            events += name
            if (failureAt == name) throw failure
        }
        suspend fun run() = HistoryMaintenanceCoordinator.run(
            prepare = { step("prepare"); if (due) 1 else null },
            archive = { step("archive") },
            compact = { step("compact") },
            deferred = { _, e -> assertSame(failure, e); step("deferred") },
            finishMaintenance = { step("finish") },
            recordMaintenance = { step("record") },
            snapshot = { step("snapshot") },
            warning = { warned = it; events += "warning" }
        )
    }
    @Test fun dueMaintainsBeforeSnapshot() = runBlocking {
        val f = Fixture(); f.run()
        assertEquals(listOf("prepare", "archive", "compact", "finish", "record", "snapshot"), f.events)
    }
    @Test fun notDueStillSnapshots() = runBlocking {
        val f = Fixture(); f.due = false; f.run()
        assertEquals(listOf("prepare", "snapshot"), f.events)
    }
    @Test fun bothCleanupFailuresContinue() = runBlocking {
        for (error in listOf(IllegalArgumentException(), IllegalStateException())) {
            val f = Fixture(); f.failureAt = "compact"; f.failure = error; f.run()
            assertEquals(listOf("prepare", "archive", "compact", "deferred", "finish", "record", "snapshot"), f.events)
            assertNull(f.warned)
        }
    }
    @Test fun failuresStopAtOriginalBoundaryAndWarn() = runBlocking {
        for (at in listOf("prepare", "archive", "finish", "record", "snapshot", "deferred")) {
            val f = Fixture()
            if (at == "deferred") {
                // A deferred logger failure must reach the outer warning handler.
                HistoryMaintenanceCoordinator.run(
                    prepare = { 1 }, archive = {}, compact = { throw IllegalArgumentException() },
                    deferred = { _, _ -> throw f.failure }, finishMaintenance = { fail() },
                    recordMaintenance = { fail() }, snapshot = { fail() }, warning = { f.warned = it }
                )
            } else {
                f.failureAt = at; f.run()
                assertFalse(f.events.dropLast(1).contains("warning"))
                assertEquals("warning", f.events.last())
                if (at in listOf("prepare", "archive", "finish")) assertFalse(f.events.contains("record"))
                if (at != "snapshot") assertFalse(f.events.contains("snapshot"))
            }
            assertSame(f.failure, f.warned)
        }
    }
    @Test fun unexpectedCleanupFailureDoesNotContinue() = runBlocking {
        val f = Fixture(); f.failureAt = "compact"; f.failure = java.io.IOException()
        f.run()
        assertEquals(listOf("prepare", "archive", "compact", "warning"), f.events)
        assertSame(f.failure, f.warned)
    }
    @Test fun cancellationFromOuterStepsPropagatesUnchanged() = runBlocking {
        for (at in listOf("prepare", "archive", "finish", "record", "snapshot")) {
            val f = Fixture(); f.failureAt = at; f.failure = CancellationException("fixture")
            try { f.run(); fail("expected cancellation") } catch (e: CancellationException) { assertSame(f.failure, e) }
            assertNull(f.warned)
        }
    }
    @Test fun cleanupCancellationKeepsExistingDeferCatchOrder() = runBlocking {
        val f = Fixture(); f.failureAt = "compact"; f.failure = CancellationException("fixture")
        f.run()
        assertEquals(listOf("prepare", "archive", "compact", "deferred", "finish", "record", "snapshot"), f.events)
    }
}
