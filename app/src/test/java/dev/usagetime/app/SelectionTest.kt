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
}
