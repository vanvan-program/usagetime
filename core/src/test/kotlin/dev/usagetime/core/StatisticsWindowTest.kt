package dev.usagetime.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class StatisticsWindowTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private fun at(date:String,hour:Int=0)=LocalDate.parse(date).atTime(hour,0).atZone(zone).toInstant().toEpochMilli()
    @Test fun calendarAndRollingPeriodsShareExclusiveNowBoundary(){
        val now=at("2026-11-01",15)
        val expected=mapOf(Period.TODAY to "2026-11-01",Period.WEEK to "2026-10-26",
            Period.DAYS7 to "2026-10-26",Period.MONTH to "2026-11-01",Period.DAYS30 to "2026-10-03")
        expected.forEach{(period,start)->assertEquals(Window(at(start),now),Dates.window(period,now,zone))}
        val monday=at("2026-11-02",15)
        assertNotEquals(Dates.window(Period.WEEK,monday,zone).start,Dates.window(Period.DAYS7,monday,zone).start)
    }
    @Test fun anchoredHistoryUsesWholeCivilPeriodsAndInclusiveCustomEndDate(){
        val now=at("2026-11-01",15);val anchor=LocalDate.parse("2024-02-29")
        assertEquals(Window(at("2024-02-01"),at("2024-03-01")),Dates.window(Period.MONTH,now,zone,anchor=anchor))
        assertEquals(Window(at("2024-02-26"),at("2024-03-04")),Dates.window(Period.WEEK,now,zone,anchor=anchor))
        assertEquals(Window(at("2024-02-23"),at("2024-03-01")),Dates.window(Period.DAYS7,now,zone,anchor=anchor))
        assertEquals(Window(at("2024-02-29"),at("2024-03-01")),Dates.window(Period.CUSTOM,now,zone,custom=anchor to anchor))
        assertEquals(Window(at("2016-11-01"),now),Dates.window(Period.ALL,now,zone,at("2016-11-01")))
    }
    @Test fun dstAndNonHourTimezonesUseCivilDates(){
        for(name in listOf("America/New_York","Europe/Berlin","Asia/Kathmandu","Australia/Lord_Howe")){
            val z=ZoneId.of(name)
            for(date in listOf("2026-03-08","2026-11-01","2026-10-04")){
                val d=LocalDate.parse(date);val now=d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
                val w=Dates.window(Period.TODAY,now,z,anchor=d)
                assertEquals(Window(Dates.midnight(d,z),Dates.midnight(d.plusDays(1),z)),w)
                assertEquals(w.duration,Reports.slots(w,z,Grain.HOUR).sumOf{it.duration})
            }
        }
    }
    @Test fun comparisonsClipBothSidesToObservedTimeAndAlignCivilProgress(){
        val now=at("2026-11-01",15)
        val current=Dates.fullMonth(LocalDate.parse("2026-11-01"),zone)
        val target=Dates.fullMonth(LocalDate.parse("2024-02-01"),zone)
        val (a,b)=Dates.alignedComparison(current,target,now,zone,true)
        assertEquals(now,a.end);assertEquals(at("2024-02-01",15),b.end)
        for(same in listOf(true,false)){
            val pair=Dates.alignedComparison(target,current,now,zone,same)
            assertTrue(pair.first.end<=now);assertTrue(pair.second.end<=now)
            val future=Dates.fullMonth(LocalDate.parse("2027-01-01"),zone)
            assertEquals(Window(now,now),Dates.alignedComparison(current,future,now,zone,same).second)
        }
    }
    @Test fun snapshotRemainsIndependentOfSystemTimezone(){
        val now=at("2026-11-01",15);val expected=Dates.window(Period.WEEK,now,zone)
        val old=java.util.TimeZone.getDefault()
        try{java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            assertEquals(expected,Dates.window(Period.WEEK,now,zone))
        }finally{java.util.TimeZone.setDefault(old)}
    }
    @Test fun reportCopiesRecalculateTotalsAndCacheCountersSurviveInvalidation(){
        val report=UsageReport(Window(0,1),emptyList(),listOf(AppTotal("a",3),AppTotal("b",5)),0,false)
        assertEquals(8L,report.total);assertEquals(3L,report.copy(apps=report.apps.take(1)).total)
        val cache=ReportCache<String>(1);assertNull(cache["a"]);cache.put("a",report)
        assertSame(report,cache["a"]);cache.put("b",report);cache.clear()
        assertEquals(1L,cache.hits);assertEquals(1L,cache.misses)
        assertEquals(1L,cache.evictions);assertEquals(1L,cache.invalidations)
    }
}
