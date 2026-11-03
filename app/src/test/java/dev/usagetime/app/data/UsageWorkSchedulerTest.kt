package dev.usagetime.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class UsageWorkSchedulerTest {
    private val calls = mutableListOf<String>()
    private val scheduler = UsageWorkScheduler({ calls += "periodic" }, { calls += "catch-up" })

    @Test fun startupSchedulesOnlyPeriodicWork() {
        scheduler.schedule()
        assertEquals(listOf("periodic"), calls)
    }

    @Test fun explicitCatchUpDoesNotReschedulePeriodicWork() {
        scheduler.catchUp()
        assertEquals(listOf("catch-up"), calls)
    }

    @Test fun bootSchedulesBeforeCatchUp() = assertBroadcast("android.intent.action.BOOT_COMPLETED")
    @Test fun packageReplacementSchedulesBeforeCatchUp() = assertBroadcast("android.intent.action.MY_PACKAGE_REPLACED")
    @Test fun unlockSchedulesBeforeCatchUp() = assertBroadcast("android.intent.action.USER_UNLOCKED")

    @Test fun nullActionIsIgnored() {
        scheduler.onBroadcast(null)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun unrelatedActionsAreIgnored() {
        listOf("", "android.intent.action.LOCKED_BOOT_COMPLETED", "android.intent.action.SCREEN_ON",
            "android.intent.action.PACKAGE_REPLACED").forEach(scheduler::onBroadcast)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun repeatedBroadcastsStillReachWorkManagerForKeepCoalescing() {
        repeat(2) { scheduler.onBroadcast("android.intent.action.USER_UNLOCKED") }
        assertEquals(listOf("periodic", "catch-up", "periodic", "catch-up"), calls)
    }

    @Test fun periodicFailurePropagatesAndSkipsCatchUp() {
        val failure = IllegalStateException("unavailable")
        val failing = UsageWorkScheduler({ throw failure }, { calls += "catch-up" })
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            failing.onBroadcast("android.intent.action.USER_UNLOCKED")
        })
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun catchUpFailurePropagatesAfterPeriodicScheduling() {
        val failure = IllegalStateException("unavailable")
        val failing = UsageWorkScheduler({ calls += "periodic" }, { throw failure })
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            failing.onBroadcast("android.intent.action.USER_UNLOCKED")
        })
        assertEquals(listOf("periodic"), calls)
    }

    private fun assertBroadcast(action: String) {
        scheduler.onBroadcast(action)
        assertEquals(listOf("periodic", "catch-up"), calls)
    }
}
