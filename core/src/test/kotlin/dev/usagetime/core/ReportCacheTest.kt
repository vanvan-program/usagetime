package dev.usagetime.core

import org.junit.Test
import org.junit.Assert.*

class ReportCacheTest {
    private fun report(app:String="a")=UsageReport(Window(0,1000),emptyList(),listOf(AppTotal(app,100)),0,false)
    @Test fun revisitedReportsReuseTheSameResult(){
        val cache=ReportCache<String>();val r=report();cache.put("week",r)
        assertSame(r,cache["week"])
    }
    @Test fun leastRecentlyUsedEntryIsEvicted(){
        val cache=ReportCache<String>(2);cache.put("a",report());cache.put("b",report())
        assertNotNull(cache["a"]);cache.put("c",report())
        assertNull(cache["b"]);assertNotNull(cache["a"]);assertEquals(2,cache.size)
    }
    @Test fun weightLimitEvictsEvenBeforeEntryLimit(){
        val cache=ReportCache<String>(12,1600);cache.put("a",report());cache.put("b",report());cache.put("c",report())
        assertEquals(2,cache.size);assertNull(cache["a"]);assertTrue(cache.weight<=1600)
    }
    @Test fun oversizedResultIsNotRetained(){
        val cache=ReportCache<String>(12,800);cache.put("small",report());cache.put("large",report("x".repeat(1000)))
        assertNotNull(cache["small"]);assertNull(cache["large"]);assertEquals(1,cache.size)
    }
    @Test fun replacingAKeyReleasesItsOldWeight(){
        val cache=ReportCache<String>();cache.put("a",report());val initial=cache.weight
        repeat(10){cache.put("a",report())};assertEquals(initial,cache.weight);assertEquals(1,cache.size)
    }
    @Test fun invalidationDropsAllMetricsAndHistory(){
        val cache=ReportCache<String>();listOf("primary","visible","legacy").forEach{cache.put(it,report())}
        cache.clear();assertEquals(0,cache.size);assertEquals(0L,cache.weight);assertNull(cache["legacy"])
    }
}
