package dev.usagetime.app

import dev.usagetime.core.AppTotal
import dev.usagetime.core.Period
import dev.usagetime.core.UsageMetric
import dev.usagetime.core.UsageReport
import dev.usagetime.core.Window
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ReportExportTest {
    private val now=1_793_664_000_000L
    private val zone=ZoneId.of("UTC")
    private fun report(total:Long)=UsageReport(Window(now-1_000,now),emptyList(),listOf(AppTotal("com.example",total)),0,false)

    @Test fun exportLoadsAReportForTheCapturedSelectionAndBasis()=runBlocking{
        val fresh=report(42)
        var requested:Selection?=null
        var requestedConfirmed:Boolean?=null
        val snapshot=ReportExportRequest.load(
            demo=false,
            selection=Selection(metric=UsageMetric.VISIBLE),
            includeConfirmed=true,
            clock=now,
            zone=zone
        ){selection,confirmed,clock,requestZone->
            requested=selection;requestedConfirmed=confirmed
            assertEquals(now,clock);assertEquals(zone,requestZone)
            DisplayData(report=fresh)
        }
        assertEquals(UsageMetric.VISIBLE,requested!!.metric)
        assertEquals(true,requestedConfirmed)
        assertSame(fresh,snapshot.report)
        assertEquals(UsageMetric.VISIBLE,snapshot.metric)
        assertEquals("屏幕可见时间",snapshot.metricLabel)
        assertEquals("已确认与候选估计",snapshot.basisLabel)
    }

    @Test fun demoDataIsRejectedBeforeQueryingRealRecords()=runBlocking{
        val failure=assertThrows(IllegalStateException::class.java){
            runBlocking{
                ReportExportRequest.load(
                    demo=true,
                    selection=Selection(),
                    includeConfirmed=false,
                    clock=now,
                    zone=zone
                ){_,_,_,_->fail("demo data must not be queried as real records");DisplayData()}
            }
        }
        assertEquals("示例数据不能导出为真实报表",failure.message)
    }

    @Test fun invalidatingReportKeepsOnlySummaryDatesForSettings(){
        val report=report(17)
        val display=DisplayData(report,other=report,all=report,first=1,last=2,recordedDays=3,legacyAvailable=true,allOther=report)
        assertEquals(DisplayData(first=1,last=2),display.invalidateReport())
    }

    @Test fun exportSelectionKeepsLastReportContextAndAppliesCurrentSettings(){
        val lastReport=Selection(page=1,period=Period.TODAY,detail=true,app="com.example",anchor=LocalDate.of(2026,11,3),metric=UsageMetric.PRIMARY)
        val settings=lastReport.copy(page=3,detail=false,app=null,metric=UsageMetric.VISIBLE,legacy=true)
        val export=ReportExportRequest.selection(settings,lastReport)
        assertEquals(1,export.page)
        assertTrue(export.detail)
        assertEquals("com.example",export.app)
        assertEquals(Period.TODAY,export.period)
        assertEquals(UsageMetric.VISIBLE,export.metric)
        assertTrue(export.legacy)
    }

    @Test fun exportSelectionFallsBackToStatisticsContextWithoutSettingsPage(){
        val settings=Selection(page=3,metric=UsageMetric.VISIBLE)
        val export=ReportExportRequest.selection(settings,null)
        assertEquals(0,export.page)
        assertEquals(UsageMetric.VISIBLE,export.metric)
    }

    @Test fun csvUsesCapturedLabelsForTheCapturedReport(){
        val snapshot=ReportExportSnapshot(report(42),UsageMetric.VISIBLE,legacy=false,includeConfirmed=true)
        val csv=CsvReportFormat.build(snapshot){"示例应用"}
        assertTrue(csv.contains("范围汇总,\"屏幕可见时间\""))
        assertTrue(csv.contains("\"已确认与候选估计\""))
        assertTrue(csv.contains("\"示例应用\""))
        assertTrue(csv.contains("42"))
        assertFalse(csv.contains("主要使用时间"))
    }
}
