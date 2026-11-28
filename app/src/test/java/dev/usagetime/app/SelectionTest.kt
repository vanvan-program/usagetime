package dev.usagetime.app

import dev.usagetime.core.*
import org.junit.Test
import org.junit.Assert.*

class SelectionTest {
    @Test fun midnightFollowsCurrentDatesWithoutMovingSelectedHistory(){
        val yesterday=java.time.LocalDate.of(2026,10,31);val today=yesterday.plusDays(1)
        val current=Selection(anchor=yesterday,compareA=yesterday,compareB=yesterday.minusWeeks(1))
        val next=current.followCurrentDate(yesterday,today)
        assertEquals(today,next.anchor);assertEquals(today,next.compareA);assertEquals(today.minusWeeks(1),next.compareB)
        val history=current.copy(anchor=yesterday.minusMonths(2),compareA=yesterday.minusMonths(3),compareB=yesterday.minusMonths(4))
        assertEquals(history,history.followCurrentDate(yesterday,today))
    }
    @Test fun typingSortingAndChartSwitchDoNotRequireAnotherReport(){
        val previous=Selection()
        assertFalse(previous.copy(query="微信").requiresReport(previous))
        assertFalse(previous.copy(nameSort=true).requiresReport(previous))
        assertFalse(previous.copy(line=true).requiresReport(previous))
        assertFalse(previous.copy(query="b",nameSort=true,line=true).requiresReport(previous))
        assertFalse(previous.requiresReport(previous))
    }
    @Test fun dataChangesStillQueryEvenWhenCombinedWithPresentationChanges(){
        val previous=Selection()
        listOf(previous.copy(anchor=previous.anchor.minusDays(1)),previous.copy(metric=UsageMetric.VISIBLE),
            previous.copy(legacy=true),previous.copy(grain=Grain.DAY),previous.copy(page=2),
            previous.copy(detail=true,app="fixture.a"),previous.copy(compareApp="fixture.a"),
            previous.copy(compareA=previous.compareA.minusWeeks(1)),previous.copy(same=false),
            previous.copy(monthly=true),previous.copy(period=dev.usagetime.core.Period.ALL),
            previous.copy(custom=previous.anchor.minusDays(1) to previous.anchor)
        ).forEach{assertTrue(it.copy(query="typing",line=true,nameSort=true).requiresReport(previous))}
    }
    @Test fun sharedTabsReuseOnlyMatchingSummarySelections() {
        val overview = Selection()
        val apps = overview.copy(page = 1, query = "fixture", nameSort = true)
        val categories = overview.copy(page = 4)
        assertTrue(apps.isSharedReportTabChange(overview))
        assertTrue(categories.isSharedReportTabChange(apps))
        assertTrue(overview.isSharedReportTabChange(categories))

        val incompatible = listOf(
            apps.copy(detail = true, app = "fixture"),
            overview.copy(page = 2),
            overview.copy(page = 3),
            apps.copy(anchor = apps.anchor.minusDays(1)),
            apps.copy(metric = UsageMetric.VISIBLE),
            apps.copy(legacy = true),
            apps.copy(period = dev.usagetime.core.Period.MONTH)
        )
        incompatible.forEach { assertFalse(categories.isSharedReportTabChange(it)) }
        assertFalse(overview.isSharedReportTabChange(overview))
    }

    @Test fun rollingPeriodsLandOnTodayWhenNextFullStepWouldOvershoot() {
        val today = java.time.LocalDate.of(2026, 11, 28)
        for (period in listOf(dev.usagetime.core.Period.DAYS7, dev.usagetime.core.Period.DAYS30)) {
            val old = Selection(period = period, anchor = today.minusDays(2))
            assertTrue(old.canStepForward(today))
            val current = old.stepAnchor(1, today)
            assertEquals(today, current.anchor)
            assertFalse(current.canStepForward(today))
            assertEquals(current, current.stepAnchor(-1, today).stepAnchor(1, today))
        }
    }

    @Test fun monthlyFollowsPreviousMonthAndPreservesManualCompareSelection(){
        val yesterday=java.time.LocalDate.of(2026,10,31);val today=yesterday.plusDays(1)
        val monthlyCurrent=Selection(monthly=true,anchor=yesterday,compareA=yesterday,compareB=yesterday.minusMonths(1))
        val rolled=monthlyCurrent.followCurrentDate(yesterday,today)
        assertEquals(today,rolled.compareA)
        assertEquals(today.minusMonths(1),rolled.compareB)
        val manualCompareB=monthlyCurrent.copy(compareB=yesterday.minusMonths(3))
        assertEquals(yesterday.minusMonths(3),manualCompareB.followCurrentDate(yesterday,today).compareB)
    }
    @Test fun stepAnchorAndCanStepForwardRespectPeriodBoundaries(){
        val today=java.time.LocalDate.of(2026,11,15) // Sunday
        val weekSelection=Selection(period=dev.usagetime.core.Period.WEEK,anchor=java.time.LocalDate.of(2026,11,9)) // Monday of same week
        assertFalse(weekSelection.canStepForward(today))
        val prevWeek=weekSelection.stepAnchor(-1,today)
        assertEquals(java.time.LocalDate.of(2026,11,2),prevWeek.anchor)
        assertTrue(prevWeek.canStepForward(today))

        val monthSelection=Selection(period=dev.usagetime.core.Period.MONTH,anchor=today.withDayOfMonth(1))
        assertFalse(monthSelection.canStepForward(today))
        val prevMonth=monthSelection.stepAnchor(-1,today)
        assertEquals(java.time.LocalDate.of(2026,10,1),prevMonth.anchor)
        assertTrue(prevMonth.canStepForward(today))

        assertEquals(today.minusDays(30),Selection(period=dev.usagetime.core.Period.DAYS30,anchor=today).stepAnchor(-1,today).anchor)
        assertFalse(Selection(period=dev.usagetime.core.Period.ALL,anchor=today.minusDays(1)).canStepForward(today))
        assertFalse(Selection(period=dev.usagetime.core.Period.CUSTOM,anchor=today.minusDays(1)).canStepForward(today))
    }
}
