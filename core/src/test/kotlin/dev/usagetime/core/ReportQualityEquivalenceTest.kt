package dev.usagetime.core

import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** Compare full ordered reports to the frozen pre-refactor implementation. */
class ReportQualityEquivalenceTest {
    private fun same(expected: UsageReport, actual: UsageReport) {
        assertEquals(expected, actual)
        assertEquals(expected.total, actual.total)
        assertEquals(expected.appQuality.keys.toList(), actual.appQuality.keys.toList())
    }
    private fun data(window: Window, metric: UsageMetric, seed: Int): List<UsageSlice> {
        val random = Random(seed)
        var at = window.start
        val slices = mutableListOf<UsageSlice>()
        while (at < window.end) {
            val stop = minOf(window.end, at + random.nextLong(1, 3_600_001))
            val evidence = Evidence.entries[random.nextInt(Evidence.entries.size)]
            val app = if (evidence == Evidence.UNKNOWN) null else "app${random.nextInt(3)}"
            slices += UsageSlice(metric, app, at, stop, evidence, "FIXTURE",
                setOf(EvidenceSource.TEST), emptySet(), random.nextInt(4) == 0)
            at = minOf(window.end, stop + random.nextLong(0, 60_001))
        }
        return slices
    }
    @Test fun allFieldsMatchAcrossMetricGrainAppEvidenceAndCivilTime() {
        var cases = 0
        for (zoneName in listOf("UTC", "Asia/Shanghai", "America/New_York", "Europe/Berlin")) {
            val zone = ZoneId.of(zoneName)
            for (date in listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1))) {
                val day = Dates.midnight(date, zone)
                val end = Dates.midnight(date.plusDays(2), zone)
                for (window in listOf(Window(day, end), Window(day + 12345, end - 6789), Window(end, end))) {
                    for (metric in UsageMetric.entries) for (seed in 0..2) {
                        val slices = data(window, metric, seed)
                        for (grain in Grain.entries) {
                            val cells = Reports.slots(window, zone, if (grain == Grain.HOUR) Grain.HOUR else Grain.DAY)
                                .map { it to Phase7DailyMetricsOracle.cells(slices, metric, it) }
                            for (app in listOf(null, "app0", "absent")) for (include in listOf(true, false)) {
                                val expected: UsageReport
                                val actual: UsageReport
                                if (grain == Grain.HOUR) {
                                    expected = Phase7DailyMetricsOracle.hourReport(cells, window, zone, app, include)
                                    actual = DailyMetrics.hourReport(cells, window, zone, app, include)
                                } else if (grain == Grain.MONTH) {
                                    val months = Reports.slots(window, zone, grain).map { slot ->
                                        slot to Phase7DailyMetricsOracle.mergeCells(
                                            cells.filter { it.first.start >= slot.start && it.first.end <= slot.end }.map { it.second }, slot)
                                    }
                                    val count = cells.sumOf { (slot, rows) ->
                                        Phase7DailyMetricsOracle.recordedDays(slot, rows, zone).toLong()
                                    }
                                    expected = Phase7DailyMetricsOracle.monthReport(months, window, zone, app, count, include)
                                    actual = DailyMetrics.monthReport(months, window, zone, app, count, include)
                                } else {
                                    val old = Phase7DailyMetricsOracle.Accumulator(window, zone, grain, app, includeConfirmed = include)
                                    val new = DailyMetrics.Accumulator(window, zone, grain, app, includeConfirmed = include)
                                    cells.forEach { old.addDay(it.second); new.addDay(it.second) }
                                    expected = old.finish(); actual = new.finish()
                                }
                                same(expected, actual); cases++
                            }
                        }
                    }
                }
            }
        }
        assertEquals(3456, cases)
        println("PHASE7_FULL_REPORT_MATRIX=$cases")
    }
    @Test fun missingDuplicateSentinelsPendingOnlyRowsAndStableTiesKeepOldSemantics() {
        val zone = ZoneId.of("UTC"); val window = Window(0, 1000)
        fun cell(app: String?, evidence: Evidence, value: Long, pending: Long = 0) =
            MetricDayCell(0, 1000, app, evidence, value, pending)
        val attributed = listOf(cell("z", Evidence.INFERRED, 100), cell("a", Evidence.INFERRED, 100),
            cell("z", Evidence.CONFIRMED, 50), cell("pending", Evidence.UNKNOWN, 0, 23),
            cell(null, Evidence.CONFIRMED, 0, 19))
        val variants = listOf(emptyList(), attributed,
            attributed + cell(DailyMetrics.SUMMARY, Evidence.UNKNOWN, 0, 12),
            attributed + cell(DailyMetrics.SUMMARY, Evidence.UNKNOWN, 100, 12) +
                cell(DailyMetrics.SUMMARY, Evidence.UNKNOWN, 200, 13) +
                cell(DailyMetrics.OBSERVED, Evidence.CONFIRMED, 1000))
        var cases = 0
        for (rows in variants) for (app in listOf(null, "z", "pending", "absent")) for (include in listOf(true, false)) {
            val cells = listOf(window to rows)
            same(Phase7DailyMetricsOracle.hourReport(cells, window, zone, app, include),
                DailyMetrics.hourReport(cells, window, zone, app, include))
            same(Phase7DailyMetricsOracle.monthReport(cells, window, zone, app, 0, include),
                DailyMetrics.monthReport(cells, window, zone, app, 0, include))
            cases += 2
        }
        assertEquals(64, cases)
        println("PHASE7_EDGE_REPORT_MATRIX=$cases")
    }
    @Test fun tenYearsOfSyntheticCellsKeepEveryFieldAndRanking() {
        val zone = ZoneId.of("America/New_York")
        val window = Window(Dates.midnight(LocalDate.of(2016, 1, 1), zone),
            Dates.midnight(LocalDate.of(2026, 1, 1), zone))
        val days = Reports.slots(window, zone, Grain.DAY).mapIndexed { index, slot ->
            val rows = listOf(
                MetricDayCell(slot.start, slot.end, "z", Evidence.INFERRED, 100, 0),
                MetricDayCell(slot.start, slot.end, "a", Evidence.CONFIRMED, 100, 0),
                MetricDayCell(slot.start, slot.end, DailyMetrics.SUMMARY, Evidence.UNKNOWN, (index % 5).toLong(), 0),
                MetricDayCell(slot.start, slot.end, DailyMetrics.OBSERVED, Evidence.CONFIRMED, slot.duration, 0))
            slot to rows
        }
        var cases = 0
        for (grain in listOf(Grain.DAY, Grain.WEEK, Grain.MONTH)) for (app in listOf(null, "a", "absent")) for (include in listOf(true, false)) {
            if (grain == Grain.MONTH) {
                val months = Reports.slots(window, zone, grain).map { slot ->
                    slot to Phase7DailyMetricsOracle.mergeCells(days.filter {
                        it.first.start >= slot.start && it.first.end <= slot.end
                    }.map { it.second }, slot)
                }
                same(Phase7DailyMetricsOracle.monthReport(months, window, zone, app, days.size.toLong(), include),
                    DailyMetrics.monthReport(months, window, zone, app, days.size.toLong(), include))
            } else {
                val old = Phase7DailyMetricsOracle.Accumulator(window, zone, grain, app, includeConfirmed = include)
                val new = DailyMetrics.Accumulator(window, zone, grain, app, includeConfirmed = include)
                days.forEach { old.addDay(it.second); new.addDay(it.second) }
                same(old.finish(), new.finish())
            }
            cases++
        }
        assertEquals(18, cases)
        println("PHASE7_TEN_YEAR_REPORT_MATRIX=$cases")
    }
}
