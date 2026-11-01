package dev.usagetime.core

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class EvidenceDisplayTest {
    private val zone=ZoneId.of("UTC")
    private val window=Window(0,86400000)
    private val slices=listOf(
        UsageSlice(UsageMetric.PRIMARY,"a",0,60000,Evidence.CONFIRMED,"focus",emptySet(),emptySet()),
        UsageSlice(UsageMetric.PRIMARY,"a",60000,180000,Evidence.INFERRED,"lifecycle",emptySet(),emptySet()),
        UsageSlice(UsageMetric.PRIMARY,"b",180000,240000,Evidence.CONFIRMED,"focus",emptySet(),emptySet())
    )
    @Test fun inferredTotalsBucketsAndRankingAgreeAcrossHourDayAndMonth(){
        val cells=DailyMetrics.cells(slices,UsageMetric.PRIMARY,window)
        val selected=DailyMetrics.displayCells(cells,false)
        val hour=DailyMetrics.hourReport(Reports.slots(window,zone,Grain.HOUR).map{it to DailyMetrics.displayCells(DailyMetrics.cells(slices,UsageMetric.PRIMARY,it),false)},window,zone,null)
        val acc=DailyMetrics.Accumulator(window,zone,Grain.DAY).apply{addDay(selected)}
        val month=DailyMetrics.monthReport(listOf(window to selected),window,zone,null,1)
        listOf(hour,acc.finish(),month).forEach{report->
            assertEquals(120000L,report.total)
            assertEquals(report.total,report.buckets.sumOf{it.millis})
            assertEquals(listOf(AppTotal("a",120000)),report.apps)
            assertEquals(0L,report.quality!!.confirmed)
            assertEquals(120000L,report.quality!!.inferred)
        }
    }
    @Test fun enablingConfirmedRestoresValuesWithoutMutatingStoredCells(){
        val cells=DailyMetrics.cells(slices,UsageMetric.PRIMARY,window)
        val original=cells.toList()
        val inferred=DailyMetrics.report(DailyMetrics.displayCells(cells,false),window,zone,Grain.DAY)
        val combined=DailyMetrics.report(DailyMetrics.displayCells(cells,true),window,zone,Grain.DAY)
        assertEquals(120000L,inferred.total);assertEquals(240000L,combined.total)
        assertEquals(2,combined.apps.size)
        assertEquals(original,cells)
        assertSame(cells,DailyMetrics.displayCells(cells,true))
    }
    @Test fun filteringPreservesCoverageAndUnknownEvidence(){
        val cells=DailyMetrics.cells(slices,UsageMetric.PRIMARY,window)
        val projected=DailyMetrics.displayCells(cells,false)
        assertEquals(cells.filter{it.app in setOf(DailyMetrics.SUMMARY,DailyMetrics.OBSERVED)},projected.filter{it.app in setOf(DailyMetrics.SUMMARY,DailyMetrics.OBSERVED)})
        val onlyConfirmed=DailyMetrics.cells(slices.filter{it.evidence==Evidence.CONFIRMED},UsageMetric.PRIMARY,window)
        val result=DailyMetrics.report(DailyMetrics.displayCells(onlyConfirmed,false),window,zone,Grain.DAY)
        assertEquals(0L,result.total);assertTrue(result.apps.isEmpty())
    }
}
