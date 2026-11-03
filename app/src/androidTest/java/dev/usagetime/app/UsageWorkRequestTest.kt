package dev.usagetime.app

import androidx.work.BackoffPolicy
import dev.usagetime.app.data.UsageWorker
import dev.usagetime.app.data.catchUpUsageRequest
import dev.usagetime.app.data.periodicUsageRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class UsageWorkRequestTest {
    @Test fun periodicRequestRetainsCollectionAndBackoffContract() {
        val spec = periodicUsageRequest().workSpec
        assertEquals(UsageWorker::class.java.name, spec.workerClassName)
        assertTrue(spec.isPeriodic)
        assertEquals(TimeUnit.HOURS.toMillis(6), spec.intervalDuration)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.MINUTES.toMillis(30), spec.backoffDelayDuration)
        assertEquals(0L, spec.initialDelay)
    }

    @Test fun catchUpRequestRetainsCollectionAndBackoffContract() {
        val spec = catchUpUsageRequest().workSpec
        assertEquals(UsageWorker::class.java.name, spec.workerClassName)
        assertFalse(spec.isPeriodic)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(30), spec.backoffDelayDuration)
        assertEquals(0L, spec.initialDelay)
    }
}
