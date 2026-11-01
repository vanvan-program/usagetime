package dev.usagetime.core

import java.time.*
import java.time.format.DateTimeFormatter

object Reports {
    fun unionDuration(windows: List<Window>, within: Window): Long {
        var end = within.start
        var sum = 0L
        windows.sortedBy { it.start }.forEach {
            val s = maxOf(it.start, within.start, end)
            val e = minOf(it.end, within.end)
            if (e > s) { sum += e - s; end = e }
        }
        return sum
    }
    fun build(intervals: List<Interval>, coverage: List<Coverage>, window: Window, zone: ZoneId, grain: Grain, app: String? = null): UsageReport {
        val selected = UsageParser.normalize(intervals.filter { (app == null || app == it.app) && it.end > window.start && it.start < window.end })
        val totals = selected.groupBy { it.app }.map { (pkg, items) -> AppTotal(pkg, unionDuration(items.map { it.window }, window)) }.sortedByDescending { it.millis }
        val reliable = coverage.filter { it.reliable }.map { Window(it.start, it.end) }
        val anyCoverage = reliable // Uncertain spans do not establish a zero-usage reading.
        val slots = slots(window, zone, grain)
        // Sweep intervals into buckets instead of scanning all records once for every date.
        val values = LongArray(slots.size)
        selected.forEach { interval ->
            var index = slots.binarySearch { if (it.end <= interval.start) -1 else if (it.start > interval.start) 1 else 0 }
            if (index < 0) index = (-index - 1).coerceAtLeast(0)
            while (index < slots.size && slots[index].start < interval.end) {
                values[index] += slots[index].overlap(interval.window)
                index++
            }
        }
        val buckets = slots.mapIndexed { i, slot ->
            val z = Instant.ofEpochMilli(slot.start).atZone(zone)
            val label = when (grain) {
                Grain.HOUR -> z.format(DateTimeFormatter.ofPattern("HH:mm"))
                Grain.DAY -> z.format(DateTimeFormatter.ofPattern("M/d"))
                Grain.WEEK -> z.format(DateTimeFormatter.ofPattern("M/d"))
                Grain.MONTH -> z.format(DateTimeFormatter.ofPattern("yy年M月"))
            }
            val complete = unionDuration(reliable, slot) >= slot.duration
            val available = complete || values[i] > 0 || unionDuration(anyCoverage, slot) > 0
            Bucket(slot.start, slot.end, label, values[i], complete, available)
        }
        val covered = unionDuration(reliable, window)
        return UsageReport(window, buckets, totals, covered, window.duration > 0 && covered >= window.duration)
    }
    fun slots(window: Window, zone: ZoneId, grain: Grain): List<Window> {
        if (window.duration == 0L) return emptyList()
        var cursor = Instant.ofEpochMilli(window.start).atZone(zone).let {
            when (grain) {
                Grain.HOUR -> it.withMinute(0).withSecond(0).withNano(0)
                Grain.DAY -> it.toLocalDate().atStartOfDay(zone)
                Grain.WEEK -> Dates.week(it.toLocalDate()).atStartOfDay(zone)
                Grain.MONTH -> it.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
            }
        }
        val result = mutableListOf<Window>()
        while (cursor.toInstant().toEpochMilli() < window.end) {
            val next = when (grain) { Grain.HOUR -> cursor.plusHours(1); Grain.DAY -> cursor.plusDays(1); Grain.WEEK -> cursor.plusWeeks(1); Grain.MONTH -> cursor.plusMonths(1) }
            result += Window(maxOf(window.start, cursor.toInstant().toEpochMilli()), minOf(window.end, next.toInstant().toEpochMilli()))
            cursor = next
        }
        return result
    }
}
