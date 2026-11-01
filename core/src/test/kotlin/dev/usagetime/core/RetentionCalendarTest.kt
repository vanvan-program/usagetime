package dev.usagetime.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class RetentionCalendarTest {
    @Test fun cutoffKeepsNinetyCivilDaysAcrossDstLeapYearsAndFiveTimeZones(){
        for(name in listOf("UTC","Asia/Shanghai","America/New_York","Europe/Berlin","Pacific/Apia")){
            val zone=ZoneId.of(name)
            for(date in listOf(LocalDate.of(2024,3,1),LocalDate.of(2026,11,1),LocalDate.of(2026,6,15))){
                val now=date.atTime(13,45).atZone(zone).toInstant().toEpochMilli()
                val cutoff=RetentionCalendar.cutoff(now,zone)
                assertEquals(date.minusDays(90),Dates.date(cutoff,zone))
                assertEquals(cutoff,Dates.midnight(date.minusDays(90),zone))
                assertEquals(91,Reports.slots(Window(cutoff,now),zone,Grain.DAY).size)
            }
        }
    }
    @Test fun tenYearsOfCrossMidnightIntervalsConserveBothMetricsWithLongTotals(){
        for(zone in listOf(ZoneId.of("Asia/Shanghai"),ZoneId.of("America/New_York"))){
            var date=LocalDate.of(2016,11,1);val end=LocalDate.of(2026,11,1)
            val totals=LongArray(2);var days=0L
            while(date<end){
                val midnight=Dates.midnight(date.plusDays(1),zone)
                val window=Window(midnight-60_000,midnight+60_000)
                for(metric in UsageMetric.entries){
                    val slice=UsageSlice(metric,"fixture",window.start,window.end,Evidence.INFERRED,"SYNTHETIC",setOf(EvidenceSource.TEST),emptySet())
                    val slots=Reports.slots(window,zone,Grain.DAY)
                    assertEquals(2,slots.size)
                    val cells=slots.flatMap{DailyMetrics.cells(listOf(slice),metric,it)}
                    assertEquals(120_000L,cells.filter{it.app=="fixture"}.sumOf{it.millis})
                    totals[metric.ordinal]+=cells.filter{it.app=="fixture"}.sumOf{it.millis}
                }
                days++;date=date.plusDays(1)
            }
            assertEquals(3652L,days)
            assertEquals(days*120_000,totals[0]);assertEquals(totals[0],totals[1])
        }
    }
    @Test fun leapDayAndRepeatedDstHourHaveActualDayLengths(){
        val zone=ZoneId.of("America/New_York")
        for((date,hours) in listOf(LocalDate.of(2026,3,8) to 23,LocalDate.of(2026,11,1) to 25,LocalDate.of(2024,2,29) to 24)){
            val window=Window(Dates.midnight(date,zone),Dates.midnight(date.plusDays(1),zone))
            assertEquals(hours*3_600_000L,window.duration)
            for(metric in UsageMetric.entries){
                val slice=UsageSlice(metric,"fixture",window.start,window.end,Evidence.INFERRED,"TEST",setOf(EvidenceSource.TEST),emptySet())
                assertEquals(window.duration,DailyMetrics.cells(listOf(slice),metric,window).single{it.app=="fixture"}.millis)
            }
        }
    }
    @Test fun exactMidnightEndNeverAppearsInFollowingDay(){
        val zone=ZoneId.of("UTC");val midnight=Dates.midnight(LocalDate.of(2026,11,1),zone)
        for(metric in UsageMetric.entries){
            val slice=UsageSlice(metric,"fixture",midnight-1000,midnight,Evidence.INFERRED,"TEST",setOf(EvidenceSource.TEST),emptySet())
            assertFalse(DailyMetrics.cells(listOf(slice),metric,Window(midnight,midnight+86400000)).any{it.app=="fixture"})
        }
    }
    @Test fun tenYearContinuousTotalsExceedIntWithoutOverflow(){
        val zone=ZoneId.of("UTC")
        val window=Window(Dates.midnight(LocalDate.of(2016,11,1),zone),Dates.midnight(LocalDate.of(2026,11,1),zone))
        val accumulator=DailyMetrics.Accumulator(window,zone,Grain.MONTH)
        for(day in Reports.slots(window,zone,Grain.DAY)){
            accumulator.addDay(listOf(
                MetricDayCell(day.start,day.end,"fixture",Evidence.INFERRED,day.duration,0),
                MetricDayCell(day.start,day.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,0,0),
                MetricDayCell(day.start,day.end,DailyMetrics.OBSERVED,Evidence.CONFIRMED,0,0)))
        }
        assertEquals(315_532_800_000L,accumulator.finish().apps.single().millis)
    }
}
