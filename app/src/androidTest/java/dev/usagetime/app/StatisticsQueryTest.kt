package dev.usagetime.app

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
                changed.openHelper.writableDatabase.execSQL("DELETE FROM daily_usage WHERE dayStart=? AND app=?",arrayOf(slots[130].start,DailyMetrics.SUMMARY))
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
