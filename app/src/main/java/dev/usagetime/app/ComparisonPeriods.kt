package dev.usagetime.app

import dev.usagetime.core.Dates
import java.time.LocalDate
import java.time.YearMonth

internal fun comparisonPeriodStart(date: LocalDate, monthly: Boolean): LocalDate =
    if (monthly) date.withDayOfMonth(1) else Dates.week(date)

internal fun comparisonPeriodAnchor(date: LocalDate, monthly: Boolean, today: LocalDate): LocalDate {
    val start = comparisonPeriodStart(date, monthly)
    require(start <= today)
    return if (start == comparisonPeriodStart(today, monthly)) today else start
}

internal fun comparisonWeeks(month: YearMonth, today: LocalDate): List<LocalDate> =
    generateSequence(Dates.week(month.atDay(1))) { it.plusWeeks(1) }
        .takeWhile { it <= month.atEndOfMonth() && it <= today }
        .toList()
