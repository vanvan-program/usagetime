package dev.usagetime.core

import java.lang.management.ManagementFactory
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.measureNanoTime

/** Host JVM evidence only. Run explicitly, outside the normal unit-test suite. */
object StatisticsPerformanceProbe {
    @JvmStatic fun main(args:Array<String>){
        val zone=ZoneId.of("UTC")
        val first=LocalDate.of(2016,11,1);val last=LocalDate.of(2026,11,1)
        val window=Window(Dates.midnight(first,zone),Dates.midnight(last,zone))
        val days=Reports.slots(window,zone,Grain.DAY)
        val bean=ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled=true
        fun allocated()=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
        for(metric in UsageMetric.entries){
            val dayCells=days.mapIndexed{index,w->
                val rows=(0 until 100).map{i->
                    MetricDayCell(w.start,w.end,"fixture.$i",if(i%5==0)Evidence.CONFIRMED else Evidence.INFERRED,
                        if(index%97==0)0 else 15000L+(index*37+i*11+metric.ordinal*5)%40*1000L,if(index%97!=0&&i%13==0)100 else 0)
                }
                rows+MetricDayCell(w.start,w.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,w.duration-rows.sumOf{it.millis},rows.sumOf{it.provisional})+
                    MetricDayCell(w.start,w.end,DailyMetrics.OBSERVED,Evidence.CONFIRMED,rows.filter{it.evidence==Evidence.CONFIRMED}.sumOf{it.millis},0)
            }
            val monthCells=Reports.slots(window,zone,Grain.MONTH).map{slot->
                slot to DailyMetrics.mergeCells(dayCells.filter{it.first().start>=slot.start&&it.first().end<=slot.end},slot)
            }
            for(includeConfirmed in listOf(true,false))for(app in listOf(null,"fixture.17")){
                val accumulator=DailyMetrics.Accumulator(window,zone,Grain.MONTH,app,includeConfirmed=includeConfirmed)
                dayCells.forEach(accumulator::addDay)
                val expected=accumulator.finish()
                val actual=DailyMetrics.monthReport(monthCells,window,zone,app,expected.recordedDays!!.toLong(),includeConfirmed)
                check(expected==actual){"Ten-year month/day equivalence failed: $metric, $app, $includeConfirmed"}
            }
            fun probe(name:String,build:()->UsageReport){
                repeat(3){build()}
                val samples=mutableListOf<Double>();val allocations=mutableListOf<Long>()
                var report:UsageReport?=null
                repeat(11){
                    val before=allocated()
                    samples+=measureNanoTime{report=build()}/1_000_000.0
                    allocations+=allocated()-before
                }
                val sorted=samples.sorted()
                println("$metric $name median_ms=${sorted[5]} p95_ms=${sorted[10]} allocated_bytes_median=${allocations.sorted()[5]} buckets=${report!!.buckets.size} total=${report!!.total}")
            }
            probe("ten_year_month_report"){
                DailyMetrics.monthReport(monthCells,window,zone,null,dayCells.count{it.any{c->c.app=="fixture.0"&&c.millis>0}}.toLong())
            }
            probe("ten_year_day_stream"){
                val accumulator=DailyMetrics.Accumulator(window,zone,Grain.DAY)
                dayCells.forEach(accumulator::addDay);accumulator.finish()
            }
            val report=DailyMetrics.monthReport(monthCells,window,zone,null,3652)
            val cache=ReportCache<String>()
            check(cache["all"]==null);cache.put("all",report)
            repeat(100){check(cache["all"]===report)}
            println("$metric cache hits=${cache.hits} misses=${cache.misses} estimated_bytes=${cache.weight} entries=${cache.size}")
            val navigation=ReportCache<String>()
            repeat(21){repeat(12){index->
                val key="period-$index"
                if(navigation[key]==null)navigation.put(key,report)
            }}
            println("$metric cache_navigation hits=${navigation.hits} misses=${navigation.misses} evictions=${navigation.evictions} entries=${navigation.size}")
            val repetitions=10000
            val originalTotal=measureNanoTime{repeat(repetitions){check(report.apps.sumOf{it.millis}==report.total)}}
            val savedTotal=measureNanoTime{repeat(repetitions){check(report.total>0)}}
            println("$metric total_lookup $repetitions old_ms=${originalTotal/1_000_000.0} new_ms=${savedTotal/1_000_000.0}")
        }
        println("TEN_YEAR_EQUIVALENCE_OK: two metrics, app/all, confirmed on/off; complete UsageReport equality")
        println("JVM_HEAP used_bytes=${ManagementFactory.getMemoryMXBean().heapMemoryUsage.used}; not Android PSS or UI latency")
    }
}
