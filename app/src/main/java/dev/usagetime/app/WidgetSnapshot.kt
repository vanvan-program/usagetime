package dev.usagetime.app

import dev.usagetime.core.AppCategory
import dev.usagetime.core.CategoryTotal
import dev.usagetime.core.Dates
import dev.usagetime.core.UsageInsights
import dev.usagetime.core.UsageReport
import java.time.LocalDate
import java.time.ZoneId

data class WidgetDay(val date: LocalDate, val millis: Long, val available: Boolean, val complete: Boolean)

data class WidgetSnapshot(
    val now: Long,
    val zone: ZoneId,
    val today: UsageReport,
    val yesterday: UsageReport,
    val week: UsageReport?,
    val assignments: Map<String, AppCategory>,
    val metricLabel: String,
    val lastCollected: Long?,
    val permitted: Boolean,
) {
    val available: Boolean get() = today.buckets.any { it.available }
    val totalText: String get() = if (available) compactWidgetDuration(today.total) else "未记录"
    val categories: List<CategoryTotal> get() = UsageInsights.categories(today.apps, assignments)
    val comparison: String get() = when {
        !available -> if (permitted) "打开用时记补记" else "请开启使用情况访问"
        yesterday.buckets.none { it.available } -> "昨日同时段未记录"
        !today.fullyCovered || !yesterday.fullyCovered ->
            "昨日同期 ${compactWidgetDuration(yesterday.total)} · 部分记录"
        yesterday.total == today.total -> "与昨日同时段持平"
        today.total > yesterday.total -> "比昨日同期多 ${compactWidgetDuration(today.total - yesterday.total)}"
        else -> "比昨日同期少 ${compactWidgetDuration(yesterday.total - today.total)}"
    }
    val days: List<WidgetDay> get() {
        val todayDate = Dates.date(now, zone)
        val saved = UsageInsights.calendar(week?.buckets.orEmpty(), zone)
        return (6L downTo 0L).map { offset ->
            val date = todayDate.minusDays(offset)
            val day = saved[date]
            WidgetDay(date, day?.millis ?: 0, day?.available == true, day?.complete == true)
        }
    }
    val weekSummary: String get() {
        val recorded = days.count { it.available }
        return if (recorded == 0) "7天均未记录"
        else "已记录 $recorded/7天 · 日均 ${compactWidgetDuration((week?.total ?: 0) / recorded)}"
    }
}

fun compactWidgetDuration(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60000
    return when {
        millis in 1..59999 -> "<1分"
        minutes < 60 -> "${minutes}分"
        minutes % 60 == 0L -> "${minutes / 60}时"
        else -> "${minutes / 60}时${minutes % 60}分"
    }
}

fun widgetShare(millis: Long, total: Long): Int =
    if (total <= 0) 0 else (millis.toDouble() / total * 100).toInt().coerceIn(0, 100)
