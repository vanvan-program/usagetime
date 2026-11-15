package dev.usagetime.app

import dev.usagetime.core.AppTotal
import dev.usagetime.core.Dates
import dev.usagetime.core.Period
import dev.usagetime.core.Window
import dev.usagetime.core.durationText
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Pure presentation helpers shared across overview, apps, compare, and settings screens. */
internal object UsagePresentation {
    private val sameYearDateFormatter = DateTimeFormatter.ofPattern("M月d日")
    private val crossYearDateFormatter = DateTimeFormatter.ofPattern("yyyy.M.d")

    fun filterAndSortApps(
        apps: List<AppTotal>,
        query: String,
        sortByName: Boolean,
        label: (String) -> String
    ): List<AppTotal> {
        val filtered = if (query.isEmpty()) {
            apps
        } else {
            apps.filter { label(it.app).contains(query, ignoreCase = true) || it.app.contains(query, ignoreCase = true) }
        }
        return if (sortByName) filtered.sortedBy { label(it.app) } else filtered
    }

    fun compareAppDeltas(
        current: List<AppTotal>,
        previous: List<AppTotal>
    ): List<Pair<String, Long>> {
        val currentMap = current.associate { it.app to it.millis }
        val previousMap = previous.associate { it.app to it.millis }
        return (currentMap.keys + previousMap.keys)
            .map { pkg -> pkg to ((currentMap[pkg] ?: 0L) - (previousMap[pkg] ?: 0L)) }
            .sortedByDescending { abs(it.second) }
    }

    fun comparisonHeadline(diffMillis: Long, canCompare: Boolean, partial: Boolean): String = when {
        !canCompare -> "记录不足，暂时无法比较"
        diffMillis == 0L -> "${if (partial) "可归属用时（含推断）" else "使用时长"}相同"
        diffMillis < 0 -> "${if (partial) "可归属用时（含推断）" else ""}少用了 ${durationText(abs(diffMillis))}"
        else -> "${if (partial) "可归属用时（含推断）" else ""}多用了 ${durationText(diffMillis)}"
    }

    fun comparisonDeltaPercent(currentTotal: Long, previousTotal: Long, canCompare: Boolean): String {
        val diff = currentTotal - previousTotal
        return when {
            !canCompare -> "缺失不是零"
            previousTotal > 0 -> "%+.1f%%".format(Locale.CHINA, diff.toDouble() / previousTotal * 100)
            currentTotal > 0 -> "新增使用"
            else -> "均为零"
        }
    }

    fun periodTitle(period: Period): String = when (period) {
        Period.TODAY -> "今天"
        Period.WEEK -> "本周"
        Period.DAYS7 -> "最近 7 天"
        Period.MONTH -> "本月"
        Period.DAYS30 -> "最近 30 天"
        Period.ALL -> "全部记录"
        Period.CUSTOM -> "自选日期"
    }

    fun periodShortLabel(period: Period): String = when (period) {
        Period.DAYS7 -> "7天"
        Period.DAYS30 -> "30天"
        Period.ALL -> "全部"
        else -> period.label
    }

    fun windowLabel(
        window: Window,
        zone: ZoneId,
        referenceYear: Int = LocalDate.now(zone).year
    ): String {
        val start = Dates.date(window.start, zone)
        val end = Dates.date((window.end - 1).coerceAtLeast(window.start), zone)
        val format = if (start.year != referenceYear || start.year != end.year) {
            crossYearDateFormatter
        } else {
            sameYearDateFormatter
        }
        return if (start == end) start.format(format) else "${start.format(format)} — ${end.format(format)}"
    }

    fun percentOf(value: Long, total: Long): String =
        if (total > 0) "%.1f%%".format(Locale.CHINA, value.toDouble() / total * 100) else "0%"

    fun diagnosticsSummary(
        lastLoadMillis: Double,
        lastCpuDrawMillis: Double?,
        hits: Long,
        misses: Long,
        entries: Int,
        estimatedBytes: Long
    ): String {
        val timing = "查询 ${"%.1f".format(Locale.CHINA, lastLoadMillis)} ms · CPU 首绘 ${
            lastCpuDrawMillis?.let { "%.1f".format(Locale.CHINA, it) } ?: "待测量"
        } ms"
        val totalQueries = hits + misses
        val hitRate = if (totalQueries > 0) "%.1f".format(Locale.CHINA, hits * 100.0 / totalQueries) else "0.0"
        return "$timing\n缓存命中 $hits/$totalQueries（$hitRate%）· 条目 $entries · 约 ${estimatedBytes / 1024} KiB"
    }
}
