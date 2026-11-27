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
    @Test fun denseEvidenceReportMatchesRawDurationsAndKeepsProofReferences(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "safety-test-statistics-${UUID.randomUUID()}.db"
        val zone = ZoneId.of("UTC")
        val start = Dates.midnight(LocalDate.of(2026, 11, 16), zone)
        val day = 86_400_000L
        val window = Window(start, start + 7 * day)
        val metric = UsageMetric.PRIMARY
        val proofs = (0 until 24).map { "fixture-proof-$it-${"f".repeat(45)}" }
        val refs = proofs.joinToString(",") { DualUsageEngine.PROOF_PREFIX + it }
        val slots = Reports.slots(window, zone, Grain.DAY)
        val expected = DailyMetrics.Accumulator(window, zone, Grain.DAY)
        var expectedCachedRows = 0L
        val db = Room.databaseBuilder(context, UsageDatabase::class.java, name).build()
        try {
            db.withTransaction {
                for (slot in slots) {
                    val slices = (0 until 1500).map { i ->
                        val at = slot.start + i * 45_000L
                        UsageSlice(metric, "fixture.app${i % 35}", at, at + 20_000,
                            Evidence.entries[i % Evidence.entries.size], "FIXTURE",
                            emptySet(), emptySet(), provisional = i % 13 == 0)
                    }
                    val cells = DailyMetrics.cells(slices, metric, slot)
                    expected.addDay(cells)
                    expectedCachedRows += cells.size
                    db.dual().slices(slices.map { s ->
                        StoredSlice("${s.start}", metric.name, s.app, s.start, s.end, s.evidence.name,
                            s.reason, "USAGE_EVENTS,OWN_WINDOW", refs, s.provisional)
                    })
                }
            }
        } finally {
            db.close()
        }
        val store = UsageStore(context, name)
        try {
            store.first()
            val began = SystemClock.elapsedRealtimeNanos()
            val report = store.aggregate(window, zone, Grain.DAY, metric, null)
            val millis = (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0
            assertEquals(expected.finish(), report)
            val detail = store.read(Window(start, start + 20_000), metric).slices.single()
            assertEquals(proofs.toSet(), detail.proofRefs)
            assertEquals(setOf(EvidenceSource.USAGE_EVENTS, EvidenceSource.OWN_WINDOW), detail.sources)
            val saved = Room.databaseBuilder(context, UsageDatabase::class.java, name).build()
            try {
                assertEquals(expectedCachedRows, saved.dual().dailyCount())
            } finally {
                saved.close()
            }
            Log.i("UsageLoadingBench", "denseEvidenceRows=10500 aggregateMs=$millis")
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun projectedReportsKeepOverlapResolutionImportsRankingTiesAndValidation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "safety-test-statistics-${UUID.randomUUID()}.db"
        val zone = ZoneId.of("UTC")
        val day = 86_400_000L
        val start = Dates.midnight(LocalDate.of(2026, 11, 16), zone)
        fun slice(metric: UsageMetric, app: String, from: Long, to: Long, grade: Evidence = Evidence.INFERRED) =
            UsageSlice(metric, app, start + from, start + to, grade, "FIXTURE",
                setOf(EvidenceSource.TEST), setOf("fixture-reference"))
        val archived = slice(UsageMetric.PRIMARY, "fixture.imported", 50, 550)
        val primaryA = slice(UsageMetric.PRIMARY, "fixture.a", 100, 400, Evidence.CONFIRMED)
        val primaryB = slice(UsageMetric.PRIMARY, "fixture.b", 200, 500)
        val overlapA = primaryA.copy(start = start + day + 100, end = start + day + 400)
        val overlapB = primaryB.copy(start = start + day + 200, end = start + day + 500)
        val visibleB = slice(UsageMetric.VISIBLE, "fixture.b", 100, 500)
        val visibleA = slice(UsageMetric.VISIBLE, "fixture.a", 100, 500)
        val recentVisibleB = slice(UsageMetric.VISIBLE, "fixture.b", day * 2 + 100, day * 2 + 500)
        val recentVisibleA = slice(UsageMetric.VISIBLE, "fixture.a", day * 2 + 100, day * 2 + 500)
        val invalid = DualCodec.stored(slice(UsageMetric.PRIMARY, "fixture.invalid", day * 2 + 100, day * 2 + 200))
            .copy(sources = "INVALID")
        val db = Room.databaseBuilder(context, UsageDatabase::class.java, name).build()
        try {
            db.dual().slices(listOf(DualCodec.stored(archived, algorithm = 1)) +
                listOf(primaryA, primaryB, overlapA, overlapB, visibleB, visibleA, recentVisibleB, recentVisibleA)
                    .map { DualCodec.stored(it) } + invalid)
        } finally {
            db.close()
        }
        val store = UsageStore(context, name)
        try {
            val ranges = listOf(UsageMetric.PRIMARY to start, UsageMetric.PRIMARY to (start + day),
                UsageMetric.VISIBLE to start, UsageMetric.VISIBLE to (start + 2 * day))
            for ((metric, from) in ranges) {
                val window = Window(from, from + 600)
                val resolved = store.read(window, metric).slices
                val expected = DailyMetrics.Accumulator(window, zone, Grain.DAY).also {
                    it.addDay(DailyMetrics.cells(resolved, metric, window))
                }.finish()
                assertEquals(expected, store.aggregate(window, zone, Grain.DAY, metric, null))
                if (metric == UsageMetric.PRIMARY) {
                    assertTrue(resolved.any { it.reason == "PRIMARY_OVERLAP_CONFLICT" })
                }
            }
            val malformed = Window(start + 2 * day, start + 2 * day + 600)
            assertTrue(runCatching { store.aggregate(malformed, zone, Grain.DAY, UsageMetric.PRIMARY, null) }.isFailure)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun primarySqlTotalsPreserveSourceVariantsTiesAndDayClipping(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "safety-test-statistics-${UUID.randomUUID()}.db"
        val zone = ZoneId.of("UTC")
        val day = 86_400_000L
        val start = Dates.midnight(LocalDate.of(2026, 11, 16), zone)
        val metric = UsageMetric.PRIMARY
        fun slice(app: String?, from: Long, to: Long, sources: Set<EvidenceSource>,
                  grade: Evidence = Evidence.CONFIRMED, pending: Boolean = false) =
            DualCodec.stored(UsageSlice(metric, app, start + from, start + to, grade,
                "FIXTURE", sources, emptySet(), provisional = pending))
        val events = setOf(EvidenceSource.USAGE_EVENTS)
        val own = setOf(EvidenceSource.OWN_WINDOW)
        val db = Room.databaseBuilder(context, UsageDatabase::class.java, name).build()
        try {
            db.dual().slices(listOf(
                slice("fixture.b", 100, 200, events),
                slice("fixture.a", 220, 520, events),
                slice("fixture.b", 540, 740, own, pending = true),
                slice(null, day - 100, day + 100, events, Evidence.INFERRED, pending = true),
                slice("fixture.b", day + 100, day + 400, events, Evidence.INFERRED),
                slice("fixture.a", day + 400, day + 700, own, Evidence.INFERRED)
            ))
        } finally {
            db.close()
        }
        val store = UsageStore(context, name)
        try {
            val whole = Window(start, start + 2 * day)
            val clipped = Window(start + 150, start + day + 450)
            for ((window, app, includeConfirmed) in listOf(
                Triple(whole, null, true),
                Triple(clipped, null, true),
                Triple(clipped, "fixture.b", false)
            )) {
                val expected = DailyMetrics.Accumulator(window, zone, Grain.DAY, app, includeConfirmed = includeConfirmed)
                for (slot in Reports.slots(window, zone, Grain.DAY)) {
                    expected.addDay(DailyMetrics.cells(store.read(slot, metric).slices, metric, slot))
                }
                val report = store.aggregate(window, zone, Grain.DAY, metric, app, includeConfirmed)
                assertEquals(expected.finish(), report)
                if (window == whole) assertEquals(listOf("fixture.b", "fixture.a"), report.apps.map { it.app })
            }
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun clippedReportsPreserveResultsWithoutReplacingCompleteDiskBuckets(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "safety-test-statistics-${UUID.randomUUID()}.db"
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.of(2026, 11, 1)
        val dayStart = Dates.midnight(date, zone)
        val day = Window(dayStart, Dates.midnight(date.plusDays(1), zone))
        val month = Dates.fullMonth(date, zone)
        val minute = 60_000L
        val hour = 60 * minute
        val firstHour = dayStart + hour
        val secondHour = firstHour + hour
        val hours = Window(firstHour, secondHour + hour)
        val metric = UsageMetric.PRIMARY
        assertEquals(25 * hour, day.duration)
        val slices = listOf(
            UsageSlice(metric, "fixture.a", firstHour + minute, firstHour + 21 * minute,
                Evidence.CONFIRMED, "FIXTURE", setOf(EvidenceSource.TEST), emptySet()),
            UsageSlice(metric, "fixture.b", secondHour + minute, secondHour + 18 * minute,
                Evidence.INFERRED, "FIXTURE", setOf(EvidenceSource.TEST), emptySet(), provisional = true),
            UsageSlice(metric, "fixture.b", day.end + minute, day.end + 30 * minute,
                Evidence.INFERRED, "FIXTURE", setOf(EvidenceSource.TEST), emptySet())
        )
        val db = Room.databaseBuilder(context, UsageDatabase::class.java, name).build()
        try {
            db.dual().slices(slices.map { DualCodec.stored(it) })
        } finally {
            db.close()
        }
        val complete = listOf(Grain.HOUR to hours, Grain.DAY to day, Grain.MONTH to month)
        val clipped = (6..25).flatMap { minutes ->
            val end = secondHour + minutes * minute
            listOf(Grain.HOUR to Window(firstHour, end), Grain.DAY to Window(dayStart, end),
                Grain.MONTH to Window(dayStart, end))
        } + listOf(Grain.HOUR to Window(firstHour + minute, hours.end),
            Grain.DAY to Window(dayStart + minute, day.end), Grain.MONTH to Window(dayStart + minute, month.end))

        fun expected(grain: Grain, window: Window): UsageReport {
            fun cells(slot: Window) = DailyMetrics.cells(slices, metric, slot)
            val days = Reports.slots(window, zone, Grain.DAY)
            return when (grain) {
                Grain.HOUR -> DailyMetrics.hourReport(Reports.slots(window, zone, grain).map { it to cells(it) }, window, zone, null)
                Grain.MONTH -> {
                    val groups = days.map(::cells)
                    val recorded = days.zip(groups).sumOf { (slot, rows) -> DailyMetrics.recordedDays(slot, rows, zone).toLong() }
                    DailyMetrics.monthReport(listOf(window to DailyMetrics.mergeCells(groups, window)), window, zone, null, recorded)
                }
                else -> DailyMetrics.Accumulator(window, zone, grain, null).also { report ->
                    days.forEach { report.addDay(cells(it)) }
                }.finish()
            }
        }

        var store = UsageStore(context, name)
        try {
            for ((grain, window) in complete) {
                assertEquals(expected(grain, window), store.aggregate(window, zone, grain, metric, null))
            }
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { observer ->
                fun scalar(sql: String): Long = observer.rawQuery(sql, null).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }
                for (table in listOf("hour_usage", "daily_usage", "month_usage")) {
                    assertTrue("Full buckets should be persisted in $table", scalar("SELECT COUNT(*) FROM $table") > 0)
                }
                var revision = scalar("PRAGMA data_version")
                var writes = 0
                val times = clipped.map { (grain, window) ->
                    val started = SystemClock.elapsedRealtimeNanos()
                    val report = store.aggregate(window, zone, grain, metric, null)
                    val millis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    assertEquals(expected(grain, window), report)
                    val after = scalar("PRAGMA data_version")
                    if (after != revision) writes++
                    revision = after
                    millis
                }
                Log.i("UsageLoadingBench", "clipped report reads=${times.size} diskWrites=$writes medianMs=${times.sorted()[times.size / 2]}")
                assertEquals("Clipped reports must leave the full disk cache alone", 0, writes)
            }
            store.close()
            store = UsageStore(context, name)
            for ((grain, window) in complete) {
                assertEquals(expected(grain, window), store.aggregate(window, zone, grain, metric, null))
            }
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

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
