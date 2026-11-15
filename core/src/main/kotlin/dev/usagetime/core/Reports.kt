package dev.usagetime.core

import java.time.*
import java.time.format.DateTimeFormatter

object Reports {
    private val HOUR_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_FORMATTER = DateTimeFormatter.ofPattern("M/d")
    private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("yy年M月")

    fun unionDuration(windows: List<Window>, within: Window): Long {
        if (windows.isEmpty() || within.duration <= 0L) return 0L
        if (windows.size == 1) {
            val w = windows[0]
            val s = maxOf(w.start, within.start)
            val e = minOf(w.end, within.end)
            return if (e > s) e - s else 0L
        }
        var end = within.start
        var sum = 0L
        windows.sortedBy { it.start }.forEach {
            val s = maxOf(it.start, within.start, end)
            val e = minOf(it.end, within.end)
            if (e > s) { sum += e - s; end = e }
        }
        return sum
    }

    private fun mergeSorted(windows: List<Window>): List<Window> {
        if (windows.size <= 1) return windows
        val sorted = windows.sortedBy { it.start }
        val merged = ArrayList<Window>(sorted.size)
        for (w in sorted) {
            if (w.end <= w.start) continue
            val last = merged.lastOrNull()
            if (last != null && w.start <= last.end) {
                if (w.end > last.end) merged[merged.lastIndex] = Window(last.start, w.end)
            } else {
                merged += w
            }
        }
        return merged
    }

    private fun overlapMerged(disjointSorted: List<Window>, within: Window): Long {
        if (disjointSorted.isEmpty() || within.duration <= 0L) return 0L
        var index = disjointSorted.binarySearch {
            if (it.end <= within.start) -1 else if (it.start >= within.start) 1 else 0
        }
        if (index < 0) index = (-index - 1).coerceAtLeast(0)
        var sum = 0L
        while (index < disjointSorted.size && disjointSorted[index].start < within.end) {
            val w = disjointSorted[index++]
            val s = maxOf(w.start, within.start)
            val e = minOf(w.end, within.end)
            if (e > s) sum += e - s
        }
        return sum
    }

    fun build(intervals: List<Interval>, coverage: List<Coverage>, window: Window, zone: ZoneId, grain: Grain, app: String? = null): UsageReport {
        val selected = UsageParser.normalize(intervals.filter { (app == null || app == it.app) && it.end > window.start && it.start < window.end })
        val totals = selected.groupBy { it.app }.map { (pkg, items) -> AppTotal(pkg, unionDuration(items.map { it.window }, window)) }.sortedByDescending { it.millis }
        val reliableMerged = mergeSorted(coverage.filter { it.reliable }.map { Window(it.start, it.end) })
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
        val formatter = when (grain) {
            Grain.HOUR -> HOUR_FORMATTER
            Grain.DAY, Grain.WEEK -> DAY_FORMATTER
            Grain.MONTH -> MONTH_FORMATTER
        }
        val buckets = slots.mapIndexed { i, slot ->
            val label = Instant.ofEpochMilli(slot.start).atZone(zone).format(formatter)
            val reliableOverlap = overlapMerged(reliableMerged, slot)
            val complete = reliableOverlap >= slot.duration
            val available = complete || values[i] > 0 || reliableOverlap > 0
            Bucket(slot.start, slot.end, label, values[i], complete, available)
        }
        val covered = overlapMerged(reliableMerged, window)
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
