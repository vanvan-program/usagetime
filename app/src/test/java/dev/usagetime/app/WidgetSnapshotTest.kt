package dev.usagetime.app

import dev.usagetime.core.AppCategory
import dev.usagetime.core.AppTotal
import dev.usagetime.core.Bucket
import dev.usagetime.core.Dates
import dev.usagetime.core.UsageReport
import dev.usagetime.core.Window
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WidgetSnapshotTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 11, 8)
    private val now = Dates.midnight(date, zone) + 12 * 3600000
    private fun report(millis: Long, available: Boolean = true, complete: Boolean = true): UsageReport =
        UsageReport(
            Window(now - 3600000, now),
            listOf(Bucket(now - 3600000, now, "11", millis, complete, available)),
            if (millis == 0L) emptyList() else listOf(AppTotal("fixture.app", millis)),
            if (complete) 3600000 else 0, complete
        )
    private fun snapshot(today: UsageReport, yesterday: UsageReport = report(0, false)): WidgetSnapshot =
        WidgetSnapshot(now, zone, today, yesterday, null, emptyMap(), "主要使用时间", null, true)

    @Test fun zeroIsDifferentFromMissing() {
        assertEquals("0分", snapshot(report(0)).totalText)
        assertEquals("未记录", snapshot(report(0, false)).totalText)
    }

    @Test fun partialCoverageNeverClaimsAValidIncreaseOrDecrease() {
        val partial = snapshot(report(120000, complete = false), report(60000))
        assertEquals("昨日同期 1分 · 部分记录", partial.comparison)
        assertFalse(partial.comparison.contains("多"))
        assertFalse(partial.comparison.contains("少"))
    }

    @Test fun missingYesterdayNeverBecomesAZeroComparison() {
        assertEquals("昨日同时段未记录", snapshot(report(120000)).comparison)
        assertEquals("比昨日同期多 1分", snapshot(report(120000), report(60000)).comparison)
        assertEquals("比昨日同期少 1分", snapshot(report(60000), report(120000)).comparison)
    }

    @Test fun weeklyAverageUsesOnlyRecordedDaysIncludingRealZero() {
        val start = Dates.midnight(date.minusDays(6), zone)
        val buckets = listOf(
            Bucket(start, start + 86400000, "11/2", 120000, true, true),
            Bucket(start + 86400000, start + 2 * 86400000, "11/3", 0, true, true),
            Bucket(start + 2 * 86400000, start + 3 * 86400000, "11/4", 0, false, false),
        )
        val week = UsageReport(Window(start, now), buckets, listOf(AppTotal("fixture.app", 120000)), 0, false)
        val data = snapshot(report(0, false)).copy(week = week)
        assertEquals(7, data.days.size)
        assertEquals("已记录 2/7天 · 日均 1分", data.weekSummary)
        assertFalse(data.days[2].available)
        assertTrue(data.days[1].available)
        assertEquals(0L, data.days[1].millis)
    }

    @Test fun unassignedAppsRemainOtherAndShareHandlesEmptyTotals() {
        val data = snapshot(report(120000)).copy(assignments = mapOf("different.app" to AppCategory.STUDY))
        assertEquals(AppCategory.OTHER, data.categories.single().category)
        assertEquals(0, widgetShare(0, 0))
        assertEquals(50, widgetShare(500000000000, 1000000000000))
        assertEquals(100, widgetShare(200, 100))
    }

    @Test fun compactDurationsKeepSubMinuteAndLongDurationsReadable() {
        assertEquals("<1分", compactWidgetDuration(30000))
        assertEquals("0分", compactWidgetDuration(0))
        assertEquals("3时12分", compactWidgetDuration(192 * 60000L))
        assertEquals("48时", compactWidgetDuration(48 * 3600000L))
    }
}
