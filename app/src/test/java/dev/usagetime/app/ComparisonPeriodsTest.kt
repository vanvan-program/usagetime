package dev.usagetime.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ComparisonPeriodsTest {
    private val today = LocalDate.of(2026, 11, 9)

    @Test fun weeksIncludeWholeCrossMonthPeriodsAndExcludeFutureWeeks() {
        val weeks = comparisonWeeks(YearMonth.of(2026, 11), today)
        assertEquals(
            listOf(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 2), today),
            weeks
        )
        assertTrue(comparisonWeeks(YearMonth.of(2026, 12), today).isEmpty())
    }

    @Test fun leapMonthAndCrossYearWeeksStayMondayAligned() {
        val leapWeeks = comparisonWeeks(YearMonth.of(2024, 2), today)
        assertEquals(LocalDate.of(2024, 1, 29), leapWeeks.first())
        assertEquals(LocalDate.of(2024, 2, 26), leapWeeks.last())
        assertEquals(LocalDate.of(2025, 12, 29), comparisonWeeks(YearMonth.of(2026, 1), today).first())
    }

    @Test fun currentPeriodsKeepTodayAnchorForMidnightFollowing() {
        assertEquals(today, comparisonPeriodAnchor(today.withDayOfMonth(1), true, today))
        assertEquals(today, comparisonPeriodAnchor(today.plusDays(3), false, today))
        val selection = Selection(compareA = comparisonPeriodAnchor(today, true, today), monthly = true)
        assertEquals(today.plusDays(1), selection.followCurrentDate(today, today.plusDays(1)).compareA)
    }

    @Test fun historicalSelectionUsesPeriodStart() {
        assertEquals(LocalDate.of(2026, 10, 1), comparisonPeriodAnchor(today.minusMonths(1), true, today))
        assertEquals(LocalDate.of(2026, 10, 26), comparisonPeriodAnchor(LocalDate.of(2026, 11, 1), false, today))
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureMonthsCannotBeSelected() {
        comparisonPeriodAnchor(today.plusMonths(1), true, today)
    }
}
