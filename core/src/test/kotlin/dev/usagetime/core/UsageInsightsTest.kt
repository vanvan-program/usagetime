package dev.usagetime.core

import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId

class UsageInsightsTest {
    private fun slice(app: String?, start: Long, end: Long, evidence: Evidence = Evidence.INFERRED, provisional: Boolean = false) =
        UsageSlice(UsageMetric.PRIMARY, app, start, end, evidence, "TEST", setOf(EvidenceSource.TEST), emptySet(), provisional)

    @Test fun classificationPreservesTotalsAndDefaultsUnassignedAppsToOther() {
        val result = UsageInsights.categories(listOf(AppTotal("a", 100), AppTotal("b", 40), AppTotal("c", 20)),
            mapOf("a" to AppCategory.WORK, "b" to AppCategory.WORK))
        assertEquals(160L, result.sumOf { it.millis })
        assertEquals(CategoryTotal(AppCategory.WORK, 140, 2), result.first())
        assertEquals(AppCategory.OTHER, result.last().category)
        assertEquals(AppCategory.OTHER, AppCategory.fromKey("unsupported"))
    }

    @Test fun manualOtherOverridesKnownDefaultAndRegroupsAllUse() {
        val apps = listOf(AppTotal("com.tencent.mm", 60), AppTotal("tv.danmaku.bili", 40), AppTotal("unknown.app", 20))
        assertEquals(
            listOf(CategoryTotal(AppCategory.SOCIAL, 60, 1), CategoryTotal(AppCategory.VIDEO, 40, 1),
                CategoryTotal(AppCategory.OTHER, 20, 1)),
            UsageInsights.categories(apps, emptyMap())
        )
        assertEquals(
            listOf(CategoryTotal(AppCategory.OTHER, 80, 2), CategoryTotal(AppCategory.VIDEO, 40, 1)),
            UsageInsights.categories(apps, mapOf("com.tencent.mm" to AppCategory.OTHER))
        )
    }

    @Test fun expandedCategoriesApplyToHistoricalPackagesWithManualPrecedence() {
        val browser = "org.mozilla.firefox"
        val shopping = "com.einnovation.temu"
        val tool = "com.openai.chatgpt"
        val apps = listOf(AppTotal(browser, 60), AppTotal(shopping, 30), AppTotal(tool, 10))
        assertEquals(
            listOf(CategoryTotal(AppCategory.BROWSE, 60, 1), CategoryTotal(AppCategory.LIFE, 30, 1),
                CategoryTotal(AppCategory.TOOLS, 10, 1)),
            UsageInsights.categories(apps, emptyMap())
        )
        assertEquals(AppCategory.STUDY, UsageInsights.categoryFor(tool, mapOf(tool to AppCategory.STUDY)))
        assertEquals(AppCategory.OTHER, UsageInsights.categoryFor("org.mozilla.firefox.fake", emptyMap()))
    }

    @Test fun timelineClipsMergesWithoutPromotingEvidenceAndRetainsMissingRanges() {
        val result = UsageInsights.timeline(listOf(slice("a", 5, 20), slice("a", 20, 30),
            slice("a", 30, 35, Evidence.CONFIRMED), slice(null, 40, 45, Evidence.UNKNOWN),
            slice("b", 48, 60, provisional = true)), UsageMetric.PRIMARY, Window(10, 50))
        assertTrue(result.any { it.app == "a" && it.start == 10L && it.end == 30L && it.evidence == Evidence.INFERRED })
        assertTrue(result.any { it.start == 30L && it.evidence == Evidence.CONFIRMED })
        assertTrue(result.any { it.start == 35L && it.end == 40L && it.missing })
        assertTrue(result.any { it.start == 40L && it.end == 45L && !it.missing && it.app == null })
        assertTrue(result.any { it.end == 50L && it.provisional })
    }

    @Test fun visibleOverlapsRemainSeparateRatherThanBeingAddedToWallTime() {
        val values = listOf(slice("a", 10, 40), slice("b", 20, 50)).map { it.copy(metric = UsageMetric.VISIBLE) }
        val result = UsageInsights.timeline(values, UsageMetric.VISIBLE, Window(10, 50))
        assertEquals(2, result.size)
        assertFalse(result.any { it.missing })
    }

    @Test fun unavailableAndAvailableZeroDaysStayDifferent() {
        val zone = ZoneId.of("UTC")
        val first = 1735689600000L
        val result = UsageInsights.calendar(listOf(
            Bucket(first, first + 86400000, "1", 0, true, true),
            Bucket(first + 86400000, first + 172800000, "2", 0, false, false)), zone).values.toList()
        assertTrue(result.first().available)
        assertFalse(result.last().available)
    }

    @Test fun monthGridUsesMondayAndIncludesLeapDay() {
        val grid = UsageInsights.monthGrid(YearMonth.of(2024, 2))
        assertEquals(35, grid.size)
        assertEquals(3, grid.indexOfFirst { it != null })
        assertTrue(grid.any { it?.dayOfMonth == 29 })
        assertEquals(0, UsageInsights.intensity(0, 100))
        assertEquals(4, UsageInsights.intensity(100, 100))
    }
}
