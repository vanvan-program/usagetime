package dev.usagetime.app

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import dev.usagetime.app.data.*
import dev.usagetime.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Explicit isolated Room check; never opens the production database. */
class StatisticsQueryTest {
    @Test fun sqlReducedPagesPreserveEveryReportFieldAndRankingTies()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="safety-test-statistics-${UUID.randomUUID()}.db"
        val zone=ZoneId.of("America/New_York")
        val window=Window(Dates.midnight(LocalDate.of(2026,2,1),zone),Dates.midnight(LocalDate.of(2026,11,6),zone))
        val slots=Reports.slots(window,zone,Grain.DAY)
        val oracle=UsageMetric.entries.associateWith{metric->slots.mapIndexed{i,w->
            val value=if(metric==UsageMetric.VISIBLE)2000L else 1000L
            // Unknown app evidence contributes provisional quality, never attributed time.
            val rows=listOf(
                MetricDayCell(w.start,w.end,"fixture.b",Evidence.INFERRED,value,50),
                MetricDayCell(w.start,w.end,"fixture.a",Evidence.CONFIRMED,value,0),
                MetricDayCell(w.start,w.end,"fixture.a",Evidence.UNKNOWN,500,30),
                MetricDayCell(w.start,w.end,null,Evidence.UNKNOWN,100,10),
                MetricDayCell(w.start,w.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,w.duration-2*value,90),
                MetricDayCell(w.start,w.end,DailyMetrics.OBSERVED,Evidence.CONFIRMED,value,0))
            if(i==0)rows else rows.reversed()
        }}
        val db=Room.databaseBuilder(context,UsageDatabase::class.java,name).build()
        try{
            db.withTransaction{
                for((metric,days) in oracle)for(cells in days){
                    db.dual().daily(cells.map{c->DailyUsage(UUID.randomUUID().toString(),c.start,c.end,zone.id,metric.name,c.app,c.evidence.name,c.millis,c.provisional,3)})
                }
                for(metric in UsageMetric.entries){
                    HistoryRetention.save(db,HistoryRetention.rows(oracle.getValue(metric)[130],metric,zone),"LOCAL")
                }
            }
        }finally{db.close()}
        var store=UsageStore(context,name)
        suspend fun verify(){
            for(metric in UsageMetric.entries)for(grain in listOf(Grain.DAY,Grain.WEEK))
                for(app in listOf(null,"fixture.a","fixture.b","fixture.absent"))for(confirmed in listOf(true,false)){
                    val expected=DailyMetrics.Accumulator(window,zone,grain,app,includeConfirmed=confirmed)
                    oracle.getValue(metric).forEach(expected::addDay)
                    assertEquals("$metric/$grain/$app/$confirmed",expected.finish(),store.aggregate(window,zone,grain,metric,app,confirmed))
                }
        }
        try{
            verify()
            store.close()
            val changed=Room.databaseBuilder(context,UsageDatabase::class.java,name).build()
            try{
                changed.withTransaction {
                    changed.openHelper.writableDatabase.execSQL("DELETE FROM daily_usage WHERE dayStart=? AND app=?",arrayOf<Any?>(slots[130].start,DailyMetrics.SUMMARY))
                    // A partial start inside a cached day must be excluded from the app totals.
                    val day=slots[170]
                    changed.dual().daily(UsageMetric.entries.map{metric->
                        DailyUsage(UUID.randomUUID().toString(),day.start+1,day.end,zone.id,metric.name,"fixture.extra",Evidence.INFERRED.name,999,0,3)
                    })
                }
            }finally{changed.close()}
            store=UsageStore(context,name)
            verify()
            store.close()
            val corrupt=Room.databaseBuilder(context,UsageDatabase::class.java,name).build()
            try{
                corrupt.openHelper.writableDatabase.execSQL("UPDATE daily_usage SET evidence='INVALID' WHERE dayStart=? AND app='fixture.a'",arrayOf(slots.first().start))
            }finally{corrupt.close()}
            store=UsageStore(context,name)
            assertTrue("Malformed cache evidence must still be rejected",runCatching{
                store.aggregate(window,zone,Grain.DAY,UsageMetric.PRIMARY,null)
            }.isFailure)
        }finally{
            store.close()
            check(name.startsWith("safety-test-statistics-"))
            context.deleteDatabase(name)
        }
    }
    @Test fun partialCacheKeepsDenseHistoryAndAnArchivedDayAccurate():Unit=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="safety-test-statistics-${UUID.randomUUID()}.db"
        val zone=ZoneId.of("UTC")
        val start=LocalDate.of(2026,1,1)
        val window=Window(Dates.midnight(start,zone),Dates.midnight(start.plusDays(128),zone))
        val metric=UsageMetric.PRIMARY
        val days=Reports.slots(window,zone,Grain.DAY).map{slot->
            (0 until 96).map{index->
                MetricDayCell(slot.start,slot.end,"fixture.app$index",Evidence.INFERRED,1000L+index,0)
            }+listOf(
                MetricDayCell(slot.start,slot.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,slot.duration,0),
                MetricDayCell(slot.start,slot.end,DailyMetrics.OBSERVED,Evidence.CONFIRMED,0,0)
            )
        }
        val db=Room.databaseBuilder(context,UsageDatabase::class.java,name).build()
        try{
            try{
                db.withTransaction{
                    days.forEachIndexed{index,cells->
                        if(index==112){
                            HistoryRetention.save(db,HistoryRetention.rows(cells,metric,zone),"LOCAL")
                        }else{
                            db.dual().daily(cells.map{cell->
                                DailyUsage("${cell.start}-${cell.app}",cell.start,cell.end,zone.id,metric.name,
                                    cell.app,cell.evidence.name,cell.millis,cell.provisional,3)
                            })
                        }
                    }
                }
            }finally{db.close()}
            val oracle=DailyMetrics.Accumulator(window,zone,Grain.DAY).also{days.forEach(it::addDay)}.finish()
            val times=(0 until 9).map{
                val store=UsageStore(context,name)
                try{
                    store.first()
                    val began=SystemClock.elapsedRealtimeNanos()
                    val actual=store.aggregate(window,zone,Grain.DAY,metric,null)
                    val millis=(SystemClock.elapsedRealtimeNanos()-began)/1_000_000.0
                    assertEquals(oracle,actual)
                    millis
                }finally{store.close()}
            }
            Log.i("UsageLoadingBench","mixed cache, one warmup plus eight samples ms: ${times.joinToString(",")}")
        }finally{context.deleteDatabase(name)}
    }

    @Test fun pagesMatchPointQueriesAcrossDstAndImportedEvidenceAndRejectCorruptHistory()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="safety-test-statistics-${UUID.randomUUID()}.db"
        val zone=ZoneId.of("America/New_York")
        val window=Window(Dates.midnight(LocalDate.of(2026,9,1),zone),Dates.midnight(LocalDate.of(2026,11,6),zone))
        val slots=Reports.slots(window,zone,Grain.DAY)
        val oracle=mutableMapOf<UsageMetric,List<List<MetricDayCell>>>()
        val db=Room.databaseBuilder(context,UsageDatabase::class.java,name).build()
        try{
            db.withTransaction{
                for(metric in UsageMetric.entries){
                    oracle[metric]=slots.mapIndexed{i,w->
                        val confirmedMillis=if(metric==UsageMetric.VISIBLE)2000L else 1000L
                        val cells=listOf(
                            MetricDayCell(w.start,w.end,"fixture.a",Evidence.CONFIRMED,confirmedMillis,0),
                            MetricDayCell(w.start,w.end,"fixture.b",Evidence.INFERRED,2000,100),
                            MetricDayCell(w.start,w.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,w.duration-confirmedMillis-2000,100),
                            MetricDayCell(w.start,w.end,DailyMetrics.OBSERVED,Evidence.CONFIRMED,confirmedMillis,0))
                        val imported=i%11==0
                        HistoryRetention.save(db,HistoryRetention.rows(cells.reversed(),metric,zone),if(imported)"IMPORTED"else"LOCAL")
                        if(imported)cells.map{when{
                            it.app==DailyMetrics.OBSERVED->it.copy(millis=0)
                            it.evidence==Evidence.CONFIRMED->it.copy(evidence=Evidence.INFERRED)
                            else->it
                        }}else cells
                    }
                }
            }
        }finally{db.close()}
        var store=UsageStore(context,name)
        try{
            for(metric in UsageMetric.entries)for(grain in listOf(Grain.DAY,Grain.WEEK,Grain.MONTH))
                for(app in listOf(null,"fixture.a","fixture.b"))for(confirmed in listOf(true,false)){
                    val expected=DailyMetrics.Accumulator(window,zone,grain,app,includeConfirmed=confirmed)
                    oracle.getValue(metric).forEach(expected::addDay)
                    val actual=store.aggregate(window,zone,grain,metric,app,confirmed)
                    assertEquals(expected.finish(),actual)
                    assertSame(actual,store.aggregate(window,zone,grain,metric,app,confirmed))
                }
            repeat(40){store.aggregate(window,zone,Grain.DAY,UsageMetric.PRIMARY,null)}
            val stats=store.reportCacheStats()
            assertTrue(stats.hits>=36);assertTrue(stats.entries<=12);assertTrue(stats.estimatedBytes<=4*1024*1024)
            store.close()
            val changed=Room.databaseBuilder(context,UsageDatabase::class.java,name).build()
            try{
                changed.openHelper.writableDatabase.execSQL("DELETE FROM daily_usage")
                changed.openHelper.writableDatabase.execSQL("DELETE FROM month_usage")
                changed.openHelper.writableDatabase.execSQL("UPDATE history_seals SET digest='invalid'")
            }finally{changed.close()}
            store=UsageStore(context,name)
            assertTrue(runCatching{store.aggregate(window,zone,Grain.DAY,UsageMetric.PRIMARY,null)}.isFailure)
        }finally{
            store.close()
            check(name.startsWith("safety-test-statistics-"))
            context.deleteDatabase(name)
        }
    }
}
