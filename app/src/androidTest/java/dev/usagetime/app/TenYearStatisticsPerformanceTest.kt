package dev.usagetime.app

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.lifecycle.*
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.usagetime.app.data.*
import dev.usagetime.core.*
import dev.usagetime.core.Period
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Requires the explicit synthetic fixture uploaded to target cache; touches no user DB. */
class TenYearStatisticsPerformanceTest {
    @Test fun actualRoomViewModelAndFirstDrawOnTenYearFixture():Unit=runBlocking{
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val source=File(context.cacheDir,"statistics-10y.sqlite")
        require(source.exists()){"Upload tools/audit_statistics_performance.py synthetic DB to cache/statistics-10y.sqlite first"}
        val name="safety-test-statistics-perf-${UUID.randomUUID()}.db"
        val target=context.getDatabasePath(name)
        target.parentFile!!.mkdirs();source.copyTo(target)
        val store=UsageStore(context,name)
        val vmStore=UsageStore(context,name)
        val zone=ZoneId.of("UTC")
        val first=Dates.midnight(LocalDate.of(2016,11,1),zone)
        val now=Dates.midnight(LocalDate.of(2026,11,1),zone)+15*3_600_000L
        val all=Window(first,now)
        val cases=linkedMapOf(
            "today" to (Dates.window(Period.TODAY,now,zone) to Grain.HOUR),
            "this_week" to (Dates.window(Period.WEEK,now,zone) to Grain.DAY),
            "rolling_7" to (Dates.window(Period.DAYS7,now,zone) to Grain.DAY),
            "this_month" to (Dates.window(Period.MONTH,now,zone) to Grain.DAY),
            "rolling_30" to (Dates.window(Period.DAYS30,now,zone) to Grain.DAY),
            "all_month" to (all to Grain.MONTH),
            "single_app_history" to (all to Grain.MONTH),
            "arbitrary_week_a" to (Dates.fullWeek(LocalDate.of(2020,1,8),zone) to Grain.DAY),
            "arbitrary_week_b" to (Dates.fullWeek(LocalDate.of(2025,5,8),zone) to Grain.DAY),
            "arbitrary_month_a" to (Dates.fullMonth(LocalDate.of(2024,2,1),zone) to Grain.DAY),
            "arbitrary_month_b" to (Dates.fullMonth(LocalDate.of(2025,3,1),zone) to Grain.DAY),
            "all_day" to (all to Grain.DAY),
            "all_week" to (all to Grain.WEEK))
        val reports=JSONObject()
        val detailedBaselines=JSONObject()
        val owner=object:ViewModelStoreOwner{override val viewModelStore=ViewModelStore()}
        val previousZone=java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try{
            assertEquals(first,store.first())
            for(metric in UsageMetric.entries){
                val oldDay=DailyMetrics.Accumulator(all,zone,Grain.DAY,includeConfirmed=false)
                val oldWeek=DailyMetrics.Accumulator(all,zone,Grain.WEEK,includeConfirmed=false)
                val baselineDb=Room.databaseBuilder(context,UsageDatabase::class.java,name).build()
                val baselineAllocated=Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
                val baselineStart=SystemClock.elapsedRealtimeNanos()
                try{
                    for(page in Reports.slots(all,zone,Grain.DAY).chunked(32)){
                        val rows=baselineDb.dual().dailyRange(page.first().start,page.last().end,zone.id,metric.name).groupBy{it.dayStart}
                        page.forEach{slot->
                            val cells=rows.getValue(slot.start).filter{it.dayEnd==slot.end}.map{
                                MetricDayCell(it.dayStart,it.dayEnd,it.app,Evidence.valueOf(it.evidence),it.millis,it.provisionalMillis)
                            }
                            oldDay.addDay(cells);oldWeek.addDay(cells)
                        }
                    }
                }finally{baselineDb.close()}
                val expectedDay=oldDay.finish();val expectedWeek=oldWeek.finish()
                detailedBaselines.put(metric.name,JSONObject()
                    .put("elapsed_ms",(SystemClock.elapsedRealtimeNanos()-baselineStart)/1_000_000.0)
                    .put("allocated_bytes",baselineAllocated?.let{before->Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()?.minus(before)}))
                val entries=JSONObject()
                for((key,value) in cases){
                    val times=JSONArray()
                    var result:UsageReport?=null
                    var allocated:Long?=null
                    repeat(3){iteration->
                        val before=if(iteration==0)Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()else null
                        val start=SystemClock.elapsedRealtimeNanos()
                        result=store.aggregate(value.first,zone,value.second,metric,
                            if(key=="single_app_history")"org.synthetic.application017"else null,false)
                        times.put((SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0)
                        if(before!=null)allocated=Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()?.minus(before)
                    }
                    if(key=="all_day")assertEquals("Every daily report field must match detailed Room rows",expectedDay,result)
                    if(key=="all_week")assertEquals("Every weekly report field must match detailed Room rows",expectedWeek,result)
                    entries.put(key,JSONObject().put("first_ms",times.getDouble(0))
                        .put("repeat_ms",times).put("first_allocated_bytes",allocated).put("total",result!!.total).put("buckets",result!!.buckets.size))
                }
                reports.put(metric.name,entries)
            }
            val vmTimings=JSONArray()
            ActivityScenario.launch(MainActivity::class.java).use{scenario->
                lateinit var model:UsageModel
                scenario.onActivity{activity->
                    val factory=object:ViewModelProvider.Factory{
                        @Suppress("UNCHECKED_CAST")
                        override fun <T:ViewModel> create(modelClass:Class<T>)=UsageModel(activity.application,vmStore){now} as T
                    }
                    model=ViewModelProvider(owner,factory)[UsageModel::class.java]
                    activity.setContent{UsageApp(model)}
                }
                fun awaitReport(requireDraw:Boolean=true){
                    val deadline=SystemClock.elapsedRealtime()+30_000
                    var complete=false
                    while(!complete&&SystemClock.elapsedRealtime()<deadline){
                        instrumentation.runOnMainSync{complete=!model.busy&&!model.computing&&model.display.report!=null&&(!requireDraw||model.lastCpuDrawMillis!=null)}
                        if(!complete)SystemClock.sleep(5)
                    }
                    assertTrue("ViewModel/CPU draw did not complete: ${model.loadError}",complete)
                }
                // Fixed-clock refresh can publish identical content twice, which correctly causes no redraw.
                awaitReport(requireDraw=false)
                for(grain in listOf(Grain.MONTH,Grain.DAY,Grain.WEEK)){
                    scenario.onActivity{model.change{it.copy(period=Period.ALL,grain=grain)}}
                    awaitReport()
                    scenario.onActivity{vmTimings.put(JSONObject().put("grain",grain.name)
                        .put("load_ms",model.lastLoadMillis).put("request_to_cpu_draw_ms",model.lastCpuDrawMillis)
                        .put("total",model.display.report!!.total))}
                    assertEquals("ViewModel must use the fixture's civil timezone",
                        reports.getJSONObject("PRIMARY").getJSONObject("all_month").getLong("total"),model.display.report!!.total)
                }
            }
            val cache=store.reportCacheStats()
            val vmCache=vmStore.reportCacheStats()
            val body=JSONObject().put("device",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT)
                .put("scope","Debug emulator; synthetic ten-year DB; actual Room/ViewModel/Compose CPU draw; excludes GPU presentation")
                .put("clock_ms",now).put("view_model_clock","Pinned to synthetic fixture; independent empty report cache")
                .put("queries",reports).put("view_model",vmTimings)
                .put("detailed_day_and_week_baseline",detailedBaselines)
                .put("cache",JSONObject().put("hits",cache.hits).put("misses",cache.misses)
                    .put("evictions",cache.evictions).put("entries",cache.entries).put("estimated_bytes",cache.estimatedBytes))
                .put("view_model_cache",JSONObject().put("hits",vmCache.hits).put("misses",vmCache.misses)
                    .put("evictions",vmCache.evictions).put("entries",vmCache.entries).put("estimated_bytes",vmCache.estimatedBytes))
                .put("process_pss_bytes",Debug.getPss()*1024L)
            File(context.cacheDir,"statistics-ten-year-device.json").writeText(body.toString(2))
            Log.i("StatisticsPerformance",body.toString())
        }finally{
            instrumentation.runOnMainSync{owner.viewModelStore.clear()}
            vmStore.close()
            store.close()
            check(name.startsWith("safety-test-statistics-perf-"))
            context.deleteDatabase(name)
            java.util.TimeZone.setDefault(previousZone)
        }
    }
}
