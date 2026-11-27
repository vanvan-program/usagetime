package dev.usagetime.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupWorkGateTest {
    @Test fun workerAlreadyStartingWaitsForFirstReport() = runBlocking {
        val gate = StartupWorkGate({ 0L }, activityGraceMillis = 5_000)
        val worker = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitReportIfLaunching() }
        assertFalse(worker.isCompleted)
        gate.activityStarted()
        yield()
        assertFalse(worker.isCompleted)
        gate.firstReportDrawn()
        withTimeout(2_000) { worker.await() }
        assertTrue(worker.isCompleted)
    }

    @Test fun warmActivityAndBackgroundOnlyProcessNeverWaitForAnUnneededReport() = runBlocking {
        var now = 0L
        val gate = StartupWorkGate({ now })
        now = 251L
        val background = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitReportIfLaunching() }
        assertTrue(background.isCompleted)
        gate.activityStarted()
        gate.firstReportDrawn()
        gate.activityStarted()
        val reopened = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitReportIfLaunching() }
        assertTrue(reopened.isCompleted)
    }

    @Test fun missingActivityAndUndrawnReportBothHaveAnExit() = runBlocking {
        val gate = StartupWorkGate({ 0L }, activityGraceMillis = 1, reportTimeoutMillis = 1)
        withTimeout(2_000) { gate.awaitReportIfLaunching() }
        gate.activityStarted()
        withTimeout(2_000) { gate.awaitReportIfLaunching() }
    }
}
