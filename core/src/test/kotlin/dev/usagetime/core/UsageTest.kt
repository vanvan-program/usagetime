package dev.usagetime.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class UsageTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    @Test fun latePauseDoesNotCloseSuccessor() {
        val p = UsageParser.parse(listOf(Event(10,EventKind.RESUME,"a","A"),Event(20,EventKind.RESUME,"a","B"),Event(22,EventKind.PAUSE,"a","A"),Event(30,EventKind.PAUSE,"a","B")),Window(0,40))
        assertEquals(20L,p.intervals.sumOf { it.end-it.start })
    }
    @Test fun screenLockAndRestartNeverLeakTime() {
        val p = UsageParser.parse(listOf(Event(10,EventKind.RESUME,"a"),Event(20,EventKind.SCREEN_OFF),Event(25,EventKind.RESUME,"a"),Event(30,EventKind.SCREEN_ON),Event(32,EventKind.UNLOCK),Event(35,EventKind.RESUME,"b")),Window(0,40))
        assertEquals(listOf(Interval("a",10,20),Interval("a",32,35),Interval("b",35,40)),p.intervals)
    }
    @Test fun resumedBeforeUnlockWaitsForUnlockAcrossCollections() {
        val a=UsageParser.parse(listOf(Event(5,EventKind.LOCK),Event(10,EventKind.RESUME,"a")),Window(0,20))
        assertTrue(a.intervals.isEmpty())
        val b=UsageParser.parse(listOf(Event(25,EventKind.UNLOCK),Event(35,EventKind.PAUSE,"a")),Window(20,40),initial=a.state)
        assertEquals(listOf(Interval("a",25,35)),b.intervals)
    }
    @Test fun resumeAcrossCollectionBoundaries() {
        val a=UsageParser.parse(listOf(Event(10,EventKind.RESUME,"a")),Window(0,20))
        val b=UsageParser.parse(listOf(Event(30,EventKind.PAUSE,"a")),Window(20,40),initial=a.state)
        assertEquals(20L,(a.intervals+b.intervals).sumOf { it.end-it.start })
    }
    @Test fun totalsAndBucketsDeduplicateAndSplitMidnight() {
        val start=Dates.midnight(LocalDate.of(2026,10,28),zone)
        val data=listOf(Interval("a",start+23*3600000,start+25*3600000),Interval("a",start+23*3600000,start+24*3600000))
        val r=Reports.build(data,listOf(Coverage(start,start+48*3600000,true)),Window(start,start+48*3600000),zone,Grain.DAY)
        assertEquals(7200000,r.total);assertEquals(r.total,r.buckets.sumOf{it.millis})
        assertEquals(listOf(3600000L,3600000L),r.buckets.map{it.millis})
    }
    @Test fun missingCoverageIsNotZero() {
        val r=Reports.build(emptyList(),listOf(Coverage(0,86400000,false)),Window(0,86400000),zone,Grain.DAY)
        assertFalse(r.fullyCovered);assertTrue(r.buckets.all{!it.available})
    }
    @Test fun calendarAndRollingRangesDiffer() {
        val now=LocalDate.of(2026,10,29).atTime(19,30).atZone(zone).toInstant().toEpochMilli()
        assertNotEquals(Dates.window(Period.WEEK,now,zone).start,Dates.window(Period.DAYS7,now,zone).start)
        val target=Dates.fullMonth(LocalDate.of(2026,2,1),zone)
        val aligned=Dates.alignedComparison(Dates.fullMonth(LocalDate.of(2026,10,1),zone),target,now,zone,true)
        assertEquals(target.end,aligned.second.end)
    }
    @Test fun dstDaysDoNotAssumeTwentyFourHours() {
        val z=ZoneId.of("America/New_York");val d=LocalDate.of(2026,3,8)
        val w=Window(Dates.midnight(d,z),Dates.midnight(d.plusDays(1),z))
        assertEquals(23*3600000L,w.duration);assertEquals(23,Reports.slots(w,z,Grain.HOUR).size)
    }
}
