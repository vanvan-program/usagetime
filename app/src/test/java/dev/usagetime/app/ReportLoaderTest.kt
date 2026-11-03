package dev.usagetime.app

import dev.usagetime.app.data.Records
import dev.usagetime.core.*
import dev.usagetime.core.Period
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReportLoaderTest {
    private val zone=ZoneId.of("UTC")
    private val today=LocalDate.of(2026,11,2)
    private val now=Dates.midnight(today,zone)+12*3_600_000
    private fun selection()=Selection(anchor=today,compareA=today,compareB=today.minusWeeks(1))
    private data class Query(val window:Window,val grain:Grain,val app:String?)
    private inner class Source {
        val calls=mutableListOf<String>()
        val queries=mutableListOf<Query>()
        var first:Long?=Dates.midnight(today.minusDays(100),zone)
        var legacy=true
        var failure:Exception?=null
        var report:(Query)->UsageReport={q->UsageReport(q.window,emptyList(),listOf(AppTotal(q.app?:"fixture",123)),7,false,recordedDays=2)}
        val loader=ReportLoader(
            first={calls+="first";first},
            last={calls+="last";now-1000},
            hasLegacy={calls+="legacy";legacy},
            aggregate={w,g,app->
                calls+="aggregate";queries+=Query(w,g,app)
                failure?.let{throw it}
                report(queries.last())
            })
    }

    @Test fun todayUsesHourlyReportAndPreservesAllFields()=runBlocking{
        val source=Source()
        val result=source.loader.load(selection().copy(period=Period.TODAY),now,zone)
        assertEquals(listOf(Query(Window(Dates.midnight(today,zone),now),Grain.HOUR,null)),source.queries)
        assertEquals(source.report(source.queries.single()),result.report)
        assertEquals(source.first,result.first);assertEquals(now-1000,result.last)
        assertEquals(2,result.recordedDays);assertNull(result.all);assertFalse(result.legacyAvailable)
        assertEquals(listOf("first","aggregate","last"),source.calls)
    }

    @Test fun everyStandardPeriodKeepsItsWindowAndGrain()=runBlocking{
        val start=Dates.midnight(today,zone)
        val expected=mapOf(Period.WEEK to start,Period.MONTH to start-86_400_000L,
            Period.DAYS7 to start-6*86_400_000L,Period.DAYS30 to start-29*86_400_000L)
        for((period,from) in expected){
            val source=Source()
            source.loader.load(selection().copy(period=period),now,zone)
            assertEquals(Query(Window(from,now),Grain.DAY,null),source.queries.single())
        }
    }

    @Test fun allPeriodUsesEarliestHistoryAndSelectedGrain()=runBlocking{
        for(grain in listOf(Grain.DAY,Grain.WEEK,Grain.MONTH)){
            val source=Source()
            source.loader.load(selection().copy(period=Period.ALL,grain=grain),now,zone)
            assertEquals(Query(Window(source.first!!,now),grain,null),source.queries.single())
        }
    }

    @Test fun absentHistoryAndUnsetCustomRangeStayEmpty()=runBlocking{
        for(period in listOf(Period.ALL,Period.CUSTOM)){
            val source=Source();source.first=null
            val result=source.loader.load(selection().copy(period=period),now,zone)
            assertEquals(Window(now,now),result.report!!.window);assertNull(result.first)
        }
    }

    @Test fun customRangeChangesGrainOnlyAboveNinetyDays()=runBlocking{
        for(days in listOf(90L,91L)){
            val source=Source()
            source.loader.load(selection().copy(period=Period.CUSTOM,custom=today.minusDays(days) to today.minusDays(1)),now,zone)
            assertEquals(Query(Window(Dates.midnight(today.minusDays(days),zone),Dates.midnight(today,zone)),
                if(days==90L)Grain.DAY else Grain.MONTH,null),source.queries.single())
        }
    }

    @Test fun detailLoadsSelectedAndOverallReportsAndUsesDayCountFallback()=runBlocking{
        val source=Source()
        source.report={q->UsageReport(q.window,
            listOf(Bucket(q.window.start,q.window.end,"fixture",17,true,true),Bucket(q.window.start,q.window.end,"missing",0,false,false)),
            listOf(AppTotal(q.app?:"overall",17)),0,false)}
        val result=source.loader.load(selection().copy(period=Period.TODAY,detail=true,app="chosen"),now,zone)
        assertEquals(listOf("chosen",null,null),source.queries.map{it.app})
        assertEquals(listOf(Grain.HOUR,Grain.HOUR,Grain.DAY),source.queries.map{it.grain})
        assertEquals("chosen",result.report!!.apps.single().app);assertEquals("overall",result.all!!.apps.single().app)
        assertEquals(1,result.recordedDays)
    }

    @Test fun dailyFallbackReusesSameRequestButNeverNextRequest()=runBlocking{
        val source=Source();source.report={q->UsageReport(q.window,emptyList(),emptyList(),0,false)}
        val first=source.loader.load(selection(),now,zone)
        assertEquals(1,source.queries.size);assertEquals(0,first.recordedDays);assertTrue(first.legacyAvailable)
        source.loader.load(selection(),now,zone)
        assertEquals(2,source.queries.size)
    }

    @Test fun legacyHintIsOnlyQueriedForEmptyModernReports()=runBlocking{
        for(legacy in listOf(false,true))for(empty in listOf(false,true))for(available in listOf(false,true)){
            val source=Source();source.legacy=available
            source.report={q->UsageReport(q.window,emptyList(),if(empty)emptyList()else listOf(AppTotal("a",1)),0,false,recordedDays=0)}
            val result=source.loader.load(selection().copy(legacy=legacy),now,zone)
            assertEquals(!legacy&&empty&&available,result.legacyAvailable)
            assertEquals(if(!legacy&&empty)1 else 0,source.calls.count{it=="legacy"})
        }
    }

    @Test fun comparisonKeepsAlignedProgressAndFourReportRoles()=runBlocking{
        val source=Source()
        val result=source.loader.load(selection().copy(page=2,compareApp="chosen"),now,zone)
        val a=Window(Dates.midnight(today,zone),now)
        val b=Window(Dates.midnight(today.minusWeeks(1),zone),Dates.midnight(today.minusWeeks(1),zone)+12*3_600_000)
        assertEquals(listOf(Query(a,Grain.DAY,"chosen"),Query(b,Grain.DAY,"chosen"),
            Query(a,Grain.DAY,null),Query(b,Grain.DAY,null)),source.queries)
        assertEquals(a,result.report!!.window);assertEquals(b,result.other!!.window)
        assertEquals(a,result.all!!.window);assertEquals(b,result.allOther!!.window)
        assertEquals(0,result.recordedDays);assertFalse(result.legacyAvailable)
        assertEquals(listOf("first","aggregate","aggregate","aggregate","last","aggregate"),source.calls)
    }

    @Test fun monthlyComparisonCanUseFullPreviousMonth()=runBlocking{
        val source=Source()
        val result=source.loader.load(selection().copy(page=2,monthly=true,same=false,compareB=today.minusMonths(1)),now,zone)
        assertEquals(Window(Dates.midnight(LocalDate.of(2026,11,1),zone),now),result.report!!.window)
        assertEquals(Window(Dates.midnight(LocalDate.of(2026,10,1),zone),Dates.midnight(LocalDate.of(2026,11,1),zone)),result.other!!.window)
        assertEquals(2,source.queries.size);assertNull(result.all);assertNull(result.allOther)
    }

    @Test fun identicalComparisonWindowsShareTheSameReportInstance()=runBlocking{
        val source=Source()
        val result=source.loader.load(selection().copy(page=2,compareB=today),now,zone)
        assertSame(result.report,result.other);assertEquals(1,source.queries.size)
    }

    @Test fun demoKeepsInferredEvidenceForBothMetricsWithoutStoreAccess()=runBlocking{
        val start=Dates.midnight(today,zone)
        val records=Records(listOf(Interval("a",start,start+1000),Interval("b",start+2000,start+5000)),
            listOf(Coverage(start,now,true)))
        for(metric in UsageMetric.entries){
            val source=Source()
            val result=source.loader.load(selection().copy(period=Period.TODAY,metric=metric,detail=true,app="a"),now,zone,records)
            assertEquals(1000L,result.report!!.total);assertEquals(4000L,result.all!!.total)
            assertEquals(0L,result.report!!.coveredMillis);assertFalse(result.report!!.fullyCovered)
            assertEquals(1000L,result.report!!.quality!!.inferred);assertEquals(0L,result.report!!.quality!!.confirmed)
            assertEquals(start,result.first);assertEquals(now,result.last);assertFalse(result.legacyAvailable)
            assertTrue(source.calls.isEmpty())
        }
    }

    @Test fun legacyDemoRetainsCoverageInsteadOfSyntheticEvidence()=runBlocking{
        val start=Dates.midnight(today,zone)
        val source=Source()
        val result=source.loader.load(selection().copy(period=Period.TODAY,legacy=true),now,zone,
            Records(listOf(Interval("a",start,start+1000)),listOf(Coverage(start,now,true))))
        assertEquals(1000L,result.report!!.total);assertTrue(result.report!!.fullyCovered)
        assertEquals(now-start,result.report!!.coveredMillis);assertNull(result.report!!.quality)
        assertTrue(source.calls.isEmpty())
    }

    @Test fun dstCalendarUsesCivilDayRatherThanFixedDuration()=runBlocking{
        val dstZone=ZoneId.of("America/New_York")
        val date=LocalDate.of(2026,11,1)
        val end=Dates.midnight(date.plusDays(1),dstZone)
        val source=Source()
        source.loader.load(selection().copy(period=Period.CUSTOM,custom=date to date),end,dstZone)
        assertEquals(25*3_600_000L,source.queries.single().window.duration)
    }

    @Test fun failuresAndCancellationPropagateWithoutReadingLastMetadata()=runBlocking{
        for(error in listOf(IllegalStateException("fixture"),CancellationException("cancelled"))){
            val source=Source();source.failure=error
            val actual=try{source.loader.load(selection(),now,zone);null}catch(e:Exception){e}
            assertSame(error,actual);assertEquals(listOf("first","aggregate"),source.calls)
        }
    }
}
