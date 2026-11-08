package dev.usagetime.app

import dev.usagetime.core.CalendarDay
import dev.usagetime.core.TimelineEntry
import java.time.LocalDate

data class InsightData(
    val year: Int = 0,
    val timeline: List<TimelineEntry> = emptyList(),
    val days: Map<LocalDate, CalendarDay> = emptyMap(),
)

