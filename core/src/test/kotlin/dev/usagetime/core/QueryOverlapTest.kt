package dev.usagetime.core

import org.junit.Assert.*
import org.junit.Test

class QueryOverlapTest {
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    @Test fun firstCollectionLooksBackSevenDays(){
        assertEquals(now - 7 * day, QueryOverlap.queryStart(now, null, healthyContinuity = true, deepSweepDue = false))
    }

    @Test fun healthyContinuityUsesThirtyMinuteOverlap(){
        val latest = now - 60_000
        assertEquals(latest - QueryOverlap.HEALTHY_WINDOW_MS, QueryOverlap.queryStart(now, latest, true, false))
    }

    @Test fun anomalyFallsBackToTwoDayBudget(){
        val latest = now - 60_000
        assertEquals(latest - QueryOverlap.FALLBACK_WINDOW_MS, QueryOverlap.queryStart(now, latest, false, false))
    }

    @Test fun dueDeepSweepForcesFallbackEvenWhenHealthy(){
        val latest = now - 60_000
        assertEquals(latest - QueryOverlap.FALLBACK_WINDOW_MS, QueryOverlap.queryStart(now, latest, true, true))
    }

    @Test fun longAbsenceCoversGapButNeverExceedsSevenDays(){
        val threeDaysAgo = now - 3 * day
        assertEquals(threeDaysAgo - QueryOverlap.HEALTHY_WINDOW_MS, QueryOverlap.queryStart(now, threeDaysAgo, true, false))
        val twentyDaysAgo = now - 20 * day
        assertEquals(now - 7 * day, QueryOverlap.queryStart(now, twentyDaysAgo, true, false))
    }

    @Test fun queryStartIsNeverNegative(){
        assertEquals(0L, QueryOverlap.queryStart(1_000L, null, true, false))
    }

    @Test fun continuityIntactRequiresAllHealthySignals(){
        assertTrue(QueryOverlap.continuityIntact(false, false, true, now - 60_000, now))
        assertFalse(QueryOverlap.continuityIntact(true, false, true, now - 60_000, now))
        assertFalse(QueryOverlap.continuityIntact(false, true, true, now - 60_000, now))
        assertFalse(QueryOverlap.continuityIntact(false, false, false, now - 60_000, now))
        assertFalse(QueryOverlap.continuityIntact(false, false, true, now - 3 * day, now))
        assertTrue(QueryOverlap.continuityIntact(false, false, true, null, now))
    }
}
