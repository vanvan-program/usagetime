package dev.usagetime.core

import java.time.*
import java.time.temporal.TemporalAdjusters

data class Window(val start: Long, val end: Long) {
    init { require(end >= start) }
    val duration: Long get() = end - start
    fun overlap(other: Window): Long = (minOf(end, other.end) - maxOf(start, other.start)).coerceAtLeast(0)
}
data class Interval(val app: String, val start: Long, val end: Long) {
    init { require(app.isNotBlank() && start >= 0 && end > start) }
    val window get() = Window(start, end)
}
enum class EventKind { RESUME, PAUSE, STOP, SCREEN_OFF, LOCK, SHUTDOWN, STARTUP, SCREEN_ON, UNLOCK }
data class Event(val time: Long, val kind: EventKind, val app: String = "", val activity: String = "")
data class CollectorState(val app: String = "", val activity: String = "", val since: Long = 0, val blocked: Boolean = false)
data class ParsedUsage(val intervals: List<Interval>, val state: CollectorState)
enum class Period(val label: String) { TODAY("今天"), WEEK("本周"), DAYS7("最近7天"), MONTH("本月"), DAYS30("最近30天"), ALL("全部记录"), CUSTOM("自选日期") }
enum class Grain(val label: String) { HOUR("按小时"), DAY("按天"), WEEK("按周"), MONTH("按月") }
data class Bucket(val start: Long, val end: Long, val label: String, val millis: Long, val complete: Boolean, val available: Boolean)
data class AppTotal(val app: String, val millis: Long)
data class Coverage(val start: Long, val end: Long, val reliable: Boolean)
data class UsageReport(val window: Window, val buckets: List<Bucket>, val apps: List<AppTotal>, val coveredMillis: Long, val fullyCovered: Boolean, val quality: MetricSummary? = null, val appQuality: Map<String,MetricSummary> = emptyMap(), val recordedDays: Int? = null) {
    val total: Long get() = apps.sumOf { it.millis }
}

object Dates {
    fun midnight(date: LocalDate, zone: ZoneId) = date.atStartOfDay(zone).toInstant().toEpochMilli()
    fun date(time: Long, zone: ZoneId) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    fun week(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun window(period: Period, now: Long, zone: ZoneId, earliest: Long = now, custom: Pair<LocalDate, LocalDate>? = null): Window {
        val today = date(now, zone)
        val start = when (period) {
            Period.TODAY -> midnight(today, zone)
            Period.WEEK -> midnight(week(today), zone)
            Period.DAYS7 -> midnight(today.minusDays(6), zone)
            Period.MONTH -> midnight(today.withDayOfMonth(1), zone)
            Period.DAYS30 -> midnight(today.minusDays(29), zone)
            Period.ALL -> minOf(earliest, now)
            Period.CUSTOM -> midnight(requireNotNull(custom).first, zone)
        }
        val end = if (period == Period.CUSTOM) minOf(now, midnight(requireNotNull(custom).second.plusDays(1), zone)) else now
        return Window(minOf(start, end), end)
    }
    fun fullWeek(date: LocalDate, zone: ZoneId) = Window(midnight(week(date), zone), midnight(week(date).plusWeeks(1), zone))
    fun fullMonth(date: LocalDate, zone: ZoneId): Window {
        val first = date.withDayOfMonth(1)
        return Window(midnight(first, zone), midnight(first.plusMonths(1), zone))
    }
    fun alignedComparison(current: Window, target: Window, now: Long, zone: ZoneId, sameProgress: Boolean): Pair<Window, Window> {
        val endA = minOf(current.end, now).coerceAtLeast(current.start)
        if (!sameProgress || now >= current.end) return Window(current.start, endA) to target
        val localA = Instant.ofEpochMilli(endA).atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(date(current.start, zone), localA.toLocalDate())
        val targetEnd = date(target.start, zone).plusDays(days).atTime(localA.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
        return Window(current.start, endA) to Window(target.start, targetEnd.coerceIn(target.start, target.end))
    }
}

fun durationText(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) / 60_000)
    return when { ms in 1..59_999 -> "不足1分钟"; minutes < 60 -> "${minutes}分钟"; minutes % 60 == 0L -> "${minutes / 60}小时"; else -> "${minutes / 60}小时${minutes % 60}分" }
}
