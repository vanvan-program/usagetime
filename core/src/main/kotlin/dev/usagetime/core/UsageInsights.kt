package dev.usagetime.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class AppCategory(val label: String) {
    STUDY("学习"), WORK("工作"), SOCIAL("社交"), VIDEO("影音"), GAME("游戏"), OTHER("其他");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.name == key } ?: OTHER
    }
}

data class CategoryTotal(val category: AppCategory, val millis: Long, val apps: Int)
data class TimelineEntry(
    val app: String?, val start: Long, val end: Long, val evidence: Evidence,
    val provisional: Boolean, val missing: Boolean = false,
)
data class CalendarDay(val date: LocalDate, val millis: Long, val available: Boolean, val complete: Boolean)

object UsageInsights {
    fun categories(apps: List<AppTotal>, assignments: Map<String, AppCategory>): List<CategoryTotal> =
        apps.groupBy { assignments[it.app] ?: AppCategory.OTHER }
            .map { (category, values) -> CategoryTotal(category, values.sumOf { it.millis }, values.size) }
            .sortedWith(compareByDescending<CategoryTotal> { it.millis }.thenBy { it.category.ordinal })

    fun timeline(slices: List<UsageSlice>, metric: UsageMetric, window: Window, app: String? = null): List<TimelineEntry> {
        val entries = slices.filter {
            it.metric == metric && it.end > window.start && it.start < window.end &&
                (app == null || it.app == app || it.app == null)
        }.map {
            TimelineEntry(it.app, maxOf(it.start, window.start), minOf(it.end, window.end), it.evidence, it.provisional)
        }
        val merged = entries.groupBy { Triple(it.app, it.evidence, it.provisional) }.values.flatMap { group ->
            val result = mutableListOf<TimelineEntry>()
            for (entry in group.sortedBy { it.start }) {
                val previous = result.lastOrNull()
                if (previous != null && entry.start <= previous.end) {
                    result[result.lastIndex] = previous.copy(end = maxOf(previous.end, entry.end))
                } else result += entry
            }
            result
        }
        val gaps = mutableListOf<TimelineEntry>()
        var cursor = window.start
        for (entry in merged.sortedBy { it.start }) {
            if (entry.start > cursor) gaps += TimelineEntry(null, cursor, entry.start, Evidence.UNKNOWN, false, true)
            cursor = maxOf(cursor, entry.end)
        }
        if (cursor < window.end) gaps += TimelineEntry(null, cursor, window.end, Evidence.UNKNOWN, false, true)
        return (merged + gaps).sortedWith(compareBy<TimelineEntry> { it.start }.thenBy { it.end }.thenBy { it.app.orEmpty() })
    }

    fun calendar(buckets: List<Bucket>, zone: ZoneId): Map<LocalDate, CalendarDay> =
        buckets.associate { bucket ->
            val date = Dates.date(bucket.start, zone)
            date to CalendarDay(date, bucket.millis, bucket.available, bucket.complete)
        }

    fun monthGrid(month: YearMonth): List<LocalDate?> {
        val first = month.atDay(1)
        val cells = List<LocalDate?>(first.dayOfWeek.value - 1) { null } +
            (1..month.lengthOfMonth()).map { month.atDay(it) }
        return cells + List((7 - cells.size % 7) % 7) { null }
    }

    fun intensity(millis: Long, maximum: Long): Int = when {
        millis <= 0 || maximum <= 0 -> 0
        millis * 1.0 / maximum < 0.25 -> 1
        millis * 1.0 / maximum < 0.5 -> 2
        millis * 1.0 / maximum < 0.75 -> 3
        else -> 4
    }
}
