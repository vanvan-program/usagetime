package dev.usagetime.app

import dev.usagetime.core.AppTotal
import dev.usagetime.core.Dates
import dev.usagetime.core.Period
import dev.usagetime.core.Window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class UsagePresentationTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun filterAndSortAppsMatchesLabelOrPackageAndRespectsSortOrder() {
        val labels = mapOf(
            "com.tencent.mm" to "微信",
            "tv.danmaku.bili" to "哔哩哔哩",
            "com.example.Alpha" to "阿尔法"
        )
        val apps = listOf(
            AppTotal("com.tencent.mm", 300_000L),
            AppTotal("tv.danmaku.bili", 200_000L),
            AppTotal("com.example.Alpha", 100_000L)
        )
        assertEquals(
            apps.map { it.app },
            UsagePresentation.filterAndSortApps(apps, "", false) { labels[it] ?: it }.map { it.app }
        )
        assertEquals(
            listOf("tv.danmaku.bili", "com.tencent.mm", "com.example.Alpha"),
            UsagePresentation.filterAndSortApps(apps, "", true) { labels[it] ?: it }.map { it.app }
        )
        assertEquals(
            listOf("com.example.Alpha"),
            UsagePresentation.filterAndSortApps(apps, "alpha", false) { labels[it] ?: it }.map { it.app }
        )
        assertEquals(
            listOf("com.tencent.mm"),
            UsagePresentation.filterAndSortApps(apps, "微信", false) { labels[it] ?: it }.map { it.app }
        )
        assertTrue(UsagePresentation.filterAndSortApps(apps, "不存在", false) { labels[it] ?: it }.isEmpty())
    }

    @Test fun compareAppDeltasIncludesAddedRemovedAndSortsByMagnitude() {
        val current = listOf(
            AppTotal("same", 100_000L),
            AppTotal("gained", 300_000L),
            AppTotal("smallIncrease", 150_000L)
        )
        val previous = listOf(
            AppTotal("same", 100_000L),
            AppTotal("dropped", 500_000L),
            AppTotal("smallIncrease", 100_000L)
        )
        assertEquals(
            listOf(
                "dropped" to -500_000L,
                "gained" to 300_000L,
                "smallIncrease" to 50_000L,
                "same" to 0L
            ),
            UsagePresentation.compareAppDeltas(current, previous)
        )
    }

    @Test fun comparisonHeadlineAndPercentHandleMissingZeroAndPartialCoverage() {
        assertEquals("记录不足，暂时无法比较", UsagePresentation.comparisonHeadline(0L, canCompare = false, partial = false))
        assertEquals("缺失不是零", UsagePresentation.comparisonDeltaPercent(0L, 100L, canCompare = false))

        assertEquals("使用时长相同", UsagePresentation.comparisonHeadline(0L, canCompare = true, partial = false))
        assertEquals("可归属用时（含推断）相同", UsagePresentation.comparisonHeadline(0L, canCompare = true, partial = true))
        assertEquals("可归属用时（含推断）多用了 2分钟", UsagePresentation.comparisonHeadline(120_000L, canCompare = true, partial = true))
        assertEquals("少用了 1分钟", UsagePresentation.comparisonHeadline(-60_000L, canCompare = true, partial = false))

        assertEquals("+100.0%", UsagePresentation.comparisonDeltaPercent(200L, 100L, canCompare = true))
        assertEquals("-50.0%", UsagePresentation.comparisonDeltaPercent(50L, 100L, canCompare = true))
        assertEquals("新增使用", UsagePresentation.comparisonDeltaPercent(50L, 0L, canCompare = true))
        assertEquals("均为零", UsagePresentation.comparisonDeltaPercent(0L, 0L, canCompare = true))
    }

    @Test fun periodLabelsWindowLabelsAndPercentagesFormatConsistently() {
        assertEquals("今天", UsagePresentation.periodTitle(Period.TODAY))
        assertEquals("全部记录", UsagePresentation.periodTitle(Period.ALL))
        assertEquals("自选日期", UsagePresentation.periodTitle(Period.CUSTOM))
        assertEquals("7天", UsagePresentation.periodShortLabel(Period.DAYS7))
        assertEquals("30天", UsagePresentation.periodShortLabel(Period.DAYS30))
        assertEquals("全部", UsagePresentation.periodShortLabel(Period.ALL))
        assertEquals("今天", UsagePresentation.periodShortLabel(Period.TODAY))

        val dayStart = Dates.midnight(LocalDate.of(2026, 11, 8), zone)
        assertEquals(
            "11月8日",
            UsagePresentation.windowLabel(Window(dayStart, dayStart + 3_600_000L), zone, referenceYear = 2026)
        )
        assertEquals(
            "11月8日 — 11月10日",
            UsagePresentation.windowLabel(Window(dayStart, dayStart + 3 * 86_400_000L), zone, referenceYear = 2026)
        )
        assertEquals(
            "2025.12.30 — 2026.1.2",
            UsagePresentation.windowLabel(
                Window(
                    Dates.midnight(LocalDate.of(2025, 12, 30), zone),
                    Dates.midnight(LocalDate.of(2026, 1, 3), zone)
                ),
                zone,
                referenceYear = 2026
            )
        )
        assertEquals("0%", UsagePresentation.percentOf(10L, 0L))
        assertEquals("25.0%", UsagePresentation.percentOf(25L, 100L))
    }

    @Test fun diagnosticsSummaryFormatsHitRateAndUnmeasuredDraw() {
        assertEquals(
            "查询 12.3 ms · CPU 首绘 待测量 ms\n缓存命中 0/0（0.0%）· 条目 0 · 约 0 KiB",
            UsagePresentation.diagnosticsSummary(12.34, null, 0, 0, 0, 0)
        )
        assertEquals(
            "查询 5.5 ms · CPU 首绘 8.2 ms\n缓存命中 3/4（75.0%）· 条目 4 · 约 3 KiB",
            UsagePresentation.diagnosticsSummary(5.5, 8.2, 3, 1, 4, 3584)
        )
    }
}
