package dev.usagetime.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.usagetime.app.data.*
import dev.usagetime.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic event source is restricted to random safety-test databases by UsageStore. */
@RunWith(AndroidJUnit4::class)
class IncrementalSyncTest {
 @Test fun inferredAndCombinedReportsUseSeparateCachesAcrossGrains()=runBlocking{fixture{store,_->
  val base=System.currentTimeMillis()-600000;val wall=base+200
  val raw=events(base)+listOf(
   DualCodec.event(base+100,Signal.RESUME,"fixture.a","Own","instance",EvidenceSource.OWN_WINDOW,"UTC"),
   DualCodec.event(base+100,Signal.FOCUS_GAIN,"fixture.a","Own","instance",EvidenceSource.OWN_WINDOW,"UTC"),
   DualCodec.event(base+150,Signal.FOCUS_LOSS,"fixture.a","Own","instance",EvidenceSource.OWN_WINDOW,"UTC"),
   DualCodec.event(base+150,Signal.STOP,"fixture.a","Own","instance",EvidenceSource.OWN_WINDOW,"UTC"))
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,1000,"fixture-boot")})
  store.collect()
  for(grain in listOf(Grain.HOUR,Grain.DAY,Grain.MONTH)){
   val w=Window(base,wall);val z=java.time.ZoneId.of("UTC")
   val inferred=store.aggregate(w,z,grain,UsageMetric.PRIMARY,"fixture.a",false)
   val combined=store.aggregate(w,z,grain,UsageMetric.PRIMARY,"fixture.a",true)
   assertEquals(30L,inferred.total);assertEquals(80L,combined.total)
   assertSame(inferred,store.aggregate(w,z,grain,UsageMetric.PRIMARY,"fixture.a",false))
   assertSame(combined,store.aggregate(w,z,grain,UsageMetric.PRIMARY,"fixture.a",true))
   assertEquals(80L,total(store,base,wall,UsageMetric.PRIMARY))
  }
 }}
 @Test fun upgradeReplaysLegacyOwnWindowEvidenceOnceAndPreservesRawEvents()=runBlocking{fixture{store,name->
  val base=System.currentTimeMillis()-600000;var wall=base+100;var elapsed=1000L
  val raw=listOf(
   DualCodec.event(base,Signal.SCREEN_ON,zone="UTC"),DualCodec.event(base,Signal.UNLOCK,zone="UTC"),
   DualCodec.event(base+1,Signal.RESUME,context.packageName,"kotlinx.coroutines.StandaloneCoroutine","old",EvidenceSource.OWN_WINDOW,"UTC"),
   DualCodec.event(base+10,Signal.PAUSE,context.packageName,"${context.packageName}.MainActivity",zone="UTC"),
   DualCodec.event(base+11,Signal.STOP,context.packageName,"${context.packageName}.MainActivity",zone="UTC"),
   DualCodec.event(base+12,Signal.RESUME,"fixture.a","A",zone="UTC"),
   DualCodec.event(base+80,Signal.STOP,"fixture.a","A",zone="UTC"))
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,elapsed,"fixture-boot")})
  store.collect();assertEquals(68L,total(store,base,wall,UsageMetric.PRIMARY))
  val eventCount=count(name)
  SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use{db->
   db.execSQL("DELETE FROM sync_checkpoints WHERE time=-2")
   db.execSQL("UPDATE usage_intervals SET evidence='UNKNOWN' WHERE metric='PRIMARY' AND app='fixture.a'")
  }
  // Even a same-time query must repair old derived rows before the normal clock guard.
  store.collect()
  assertEquals(68L,total(store,base,wall,UsageMetric.PRIMARY));assertEquals(eventCount,count(name))
  val rebuilt=store.diagnostics().count{it.code=="HISTORY_REBUILT"}
  wall+=100;elapsed+=100;store.collect()
  assertEquals(rebuilt,store.diagnostics().count{it.code=="HISTORY_REBUILT"})
  assertEquals(eventCount,count(name))
 }}
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private suspend fun fixture(action:suspend(UsageStore,String)->Unit){
  val name="safety-test-${UUID.randomUUID()}.db";val store=UsageStore(context,name)
  try{assumeTrue(store.permitted());action(store,name)}finally{
   store.close();context.databaseList().filter{it.startsWith(name)}.forEach{context.deleteDatabase(it)}
   val dir=File(context.noBackupFilesDir,"recovery-$name");require(dir.canonicalFile.parentFile==context.noBackupFilesDir.canonicalFile);dir.deleteRecursively()
   check(context.getSharedPreferences("usage-settings-$name",0).edit().commit());check(context.deleteSharedPreferences("usage-settings-$name"))
  }
 }
 private fun events(base:Long)=listOf(
  DualCodec.event(base,Signal.SCREEN_ON,zone="UTC"),DualCodec.event(base,Signal.UNLOCK,zone="UTC"),
  DualCodec.event(base+10,Signal.RESUME,"fixture.a","A",zone="UTC"),
  DualCodec.event(base+40,Signal.PAUSE,"fixture.a","A",zone="UTC"),
  DualCodec.event(base+80,Signal.STOP,"fixture.a","A",zone="UTC"))
 private fun progress(name:String):JSONObject=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use{db->
  db.rawQuery("SELECT state FROM sync_checkpoints WHERE time=-1 AND algorithm=0",null).use{c->assertTrue(c.moveToFirst());JSONObject(c.getString(0))}
 }
 private fun count(name:String)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use{db->db.rawQuery("SELECT COUNT(*) FROM usage_events",null).use{c->assertTrue(c.moveToFirst());c.getLong(0)}}
 private fun latestTime(name:String)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use{db->db.rawQuery("SELECT MAX(time) FROM sync_checkpoints WHERE algorithm=2",null).use{c->assertTrue(c.moveToFirst());if(c.isNull(0))null else c.getLong(0)}}
 private suspend fun total(store:UsageStore,base:Long,end:Long,metric:UsageMetric,pkg:String="fixture.a")=store.read(Window(base,end),metric).intervals.filter{it.app==pkg}.sumOf{it.end-it.start}
 @Test fun sameWindowCacheInvalidatesAfterLateEvidenceAndRebuild()=runBlocking{fixture{store,_->
  val base=System.currentTimeMillis()-600000;var wall=base+100;var elapsed=1000L;val raw=events(base).toMutableList()
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,elapsed,"fixture-boot")})
  store.collect();val w=Window(base,base+1000);val z=java.time.ZoneId.of("UTC")
  suspend fun report(g:Grain,m:UsageMetric)=store.aggregate(w,z,g,m,"fixture.a")
  val before=report(Grain.HOUR,UsageMetric.PRIMARY);assertSame(before,report(Grain.HOUR,UsageMetric.PRIMARY));assertEquals(30L,before.total)
  assertEquals(70L,report(Grain.DAY,UsageMetric.VISIBLE).total)
  raw+=DualCodec.event(base+20,Signal.STOP,"fixture.a","A",zone="UTC");wall+=100;elapsed+=100;store.collect()
  val after=report(Grain.HOUR,UsageMetric.PRIMARY);assertNotSame(before,after);assertEquals(10L,after.total)
  assertEquals(50L,report(Grain.DAY,UsageMetric.VISIBLE).total)
  store.rebuildHistory();assertNotSame(after,report(Grain.HOUR,UsageMetric.PRIMARY));assertEquals(after,report(Grain.HOUR,UsageMetric.PRIMARY))
 }}
 @Test fun repeatedSyncIsIdempotentAndMatchesFullHistoryReplay()=runBlocking{fixture{store,name->
  val base=System.currentTimeMillis()-600000;var wall=base+100;var elapsed=1000L;val raw=events(base)
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,elapsed,"fixture-boot")})
  store.collect();val count=count(name);wall+=100;elapsed+=100;store.collect()
  assertEquals(count,count(name));assertTrue(progress(name).getBoolean("ready"));assertTrue(progress(name).isNull("dirtyFrom"))
  assertEquals(30L,total(store,base,wall,UsageMetric.PRIMARY));assertEquals(70L,total(store,base,wall,UsageMetric.VISIBLE))
  store.rebuildHistory();assertEquals(30L,total(store,base,wall,UsageMetric.PRIMARY));assertEquals(70L,total(store,base,wall,UsageMetric.VISIBLE))
 }}
 @Test fun lateSystemCloseCorrectsAlreadySavedTimeAndMatchesFullReplay()=runBlocking{fixture{store,_->
  val base=System.currentTimeMillis()-600000;var wall=base+100;var elapsed=1000L;val raw=events(base).toMutableList()
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,elapsed,"fixture-boot")})
  store.collect();raw+=DualCodec.event(base+20,Signal.STOP,"fixture.a","A",zone="UTC");wall+=100;elapsed+=100;store.collect()
  val primary=total(store,base,wall,UsageMetric.PRIMARY);val visible=total(store,base,wall,UsageMetric.VISIBLE)
  // STOP at20 closes the first candidate; orphan PAUSE at40 establishes a new inferred visible candidate until80.
  assertEquals(10L,primary);assertEquals(50L,visible)
  store.rebuildHistory();assertEquals(primary,total(store,base,wall,UsageMetric.PRIMARY));assertEquals(visible,total(store,base,wall,UsageMetric.VISIBLE))
 }}
 @Test fun ownEventsOlderThanCheckpointPersistTheirReplayBoundary()=runBlocking{fixture{store,name->
  val base=System.currentTimeMillis()-600000;var wall=base+100;var elapsed=1000L;val raw=events(base)
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,elapsed,"fixture-boot")})
  store.collect()
  for(signal in listOf(Signal.RESUME,Signal.FOCUS_GAIN))store.ownEvent(signal,"Fixture","own",base+50,true,false)
  for(signal in listOf(Signal.FOCUS_LOSS,Signal.STOP))store.ownEvent(signal,"Fixture","own",base+150,true,false)
  assertEquals(base+50,progress(name).getLong("dirtyFrom"))
  wall+=100;elapsed+=100;store.collect();assertEquals(100L,total(store,base,wall,UsageMetric.PRIMARY,context.packageName))
  store.rebuildHistory();assertEquals(100L,total(store,base,wall,UsageMetric.PRIMARY,context.packageName))
 }}
 @Test fun failedSyncRollsBackNewEventsWithoutClearingDirtyBoundary()=runBlocking{fixture{store,name->
  val base=System.currentTimeMillis()-600000;var wall=base+100;var elapsed=1000L;val raw=events(base)
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,elapsed,"fixture-boot")})
  store.collect();store.ownEvent(Signal.RESUME,"Fixture","own",base+25,true,false);val count=count(name)
  wall+=100;elapsed+=100
  store.useSyntheticSourceForTests({a,b,_->sequence{
   if(base+20>=a&&base+20<b){repeat(1000){i->yield(DualCodec.event(base+20,Signal.OTHER,zone="UTC").copy(id="rollback-$i"))};throw java.io.IOException("Synthetic query failure")}
  }},{ClockSample(wall,elapsed,"fixture-boot")})
  assertTrue(runCatching{store.collect()}.isFailure);assertEquals(count,count(name))
  // Query failure falls back to processing the previously committed own evidence locally, so the pending boundary clears.
  assertTrue(progress(name).isNull("dirtyFrom"))
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall+100,elapsed+100,"fixture-boot")})
  assertEquals("已保存最新记录",store.collect());assertTrue(progress(name).isNull("dirtyFrom"))
 }}

 @Test fun continuousUseWithoutNewEventsExtendsTheOpenSessionExactly()=runBlocking{fixture{store,_->
  val base=System.currentTimeMillis()-600000;var wall=base+100;var elapsed=1000L;val raw=events(base).take(3)
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,elapsed,"fixture-boot")})
  store.collect();wall+=100;elapsed+=100;store.collect()
  assertEquals(190L,total(store,base,wall,UsageMetric.PRIMARY));assertEquals(190L,total(store,base,wall,UsageMetric.VISIBLE))
  store.rebuildHistory();assertEquals(190L,total(store,base,wall,UsageMetric.PRIMARY));assertEquals(190L,total(store,base,wall,UsageMetric.VISIBLE))
 }}
 @Test fun midStatsFailureRollsBackDerivedRowsAndLocalOnlyRetryCompletes()=runBlocking{fixture{store,name->
  val base=System.currentTimeMillis()-600000;var wall=base+100;var elapsed=1000L;val raw=events(base).toMutableList()
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,elapsed,"fixture-boot")})
  store.collect();assertEquals(30L,total(store,base,base+100,UsageMetric.PRIMARY))
  raw+=DualCodec.event(base+20,Signal.STOP,"fixture.a","A",zone="UTC");wall+=100;elapsed+=100
  // Fault fires inside the statistics transaction after old derived rows were deleted; everything must roll back.
  store.onCollectPhaseForTests{phase->if(phase=="stats-written")throw java.io.IOException("Synthetic statistics failure")}
  assertTrue(runCatching{store.collect()}.isFailure)
  assertEquals(base+20,progress(name).getLong("dirtyFrom"))
  assertEquals(30L,total(store,base,base+100,UsageMetric.PRIMARY));assertEquals(70L,total(store,base,base+100,UsageMetric.VISIBLE))
  // Simulate process death, then retry while the platform is unavailable: local evidence alone completes the statistics.
  store.close();val reopened=UsageStore(context,name)
  reopened.useSyntheticSourceForTests({_,_,_->throw java.io.IOException("Platform unavailable")},{ClockSample(wall+100,elapsed+100,"fixture-boot")})
  assertTrue(runCatching{reopened.collect()}.isFailure)
  assertTrue(progress(name).isNull("dirtyFrom"))
  assertEquals(10L,total(reopened,base,base+100,UsageMetric.PRIMARY));assertEquals(50L,total(reopened,base,base+100,UsageMetric.VISIBLE))
  reopened.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall+200,elapsed+200,"fixture-boot")})
  assertEquals("已保存最新记录",reopened.collect())
  assertEquals(10L,total(reopened,base,base+100,UsageMetric.PRIMARY));assertEquals(50L,total(reopened,base,base+100,UsageMetric.VISIBLE))
  reopened.close()
 }}
 @Test fun localRetryAfterDaysAndRebootNeverExtendsAnOpenTail()=runBlocking{fixture{store,name->
  val base=System.currentTimeMillis()-600000;var wall=base+100;var elapsed=1000L
  val raw=events(base).take(3)
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,elapsed,"boot-a")})
  store.collect();wall=base+200;elapsed+=100
  store.onCollectPhaseForTests{if(it=="stats-written")throw java.io.IOException("failed after first checkpoint write")}
  assertTrue(runCatching{store.collect()}.isFailure)
  assertEquals(base+100,latestTime(name));assertEquals(base+200,progress(name).getJSONObject("queried").getLong("end"))
  store.onCollectPhaseForTests(null)
  store.useSyntheticSourceForTests({_,_,_->throw java.io.IOException("platform unavailable")},{ClockSample(base+3*86400000L,50,"boot-b")})
  assertTrue(runCatching{store.collect()}.isFailure)
  assertEquals(base+200,latestTime(name))
  assertEquals(190L,total(store,base,base+3*86400000L,UsageMetric.PRIMARY))
  assertEquals(190L,total(store,base,base+3*86400000L,UsageMetric.VISIBLE))
  assertTrue(store.read(Window(base+200,base+3*86400000L)).slices.isEmpty())
  assertEquals(3*86400000L-200,store.aggregate(Window(base+200,base+3*86400000L),java.time.ZoneId.of("UTC"),Grain.DAY,UsageMetric.PRIMARY,null).quality!!.unknown)
 }}
 @Test fun backwardClockKeepsPendingAndDerivedRowsUntouched()=runBlocking{fixture{store,name->
  val base=System.currentTimeMillis()-600000;var wall=base+100;var elapsed=1000L
  val raw=events(base).take(3)
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,elapsed,"boot-a")})
  store.collect();wall=base+200;elapsed+=100
  store.onCollectPhaseForTests{if(it=="stats-written")throw java.io.IOException("statistics unavailable")}
  assertTrue(runCatching{store.collect()}.isFailure)
  val pending=progress(name).toString();store.onCollectPhaseForTests(null);wall=base+150
  store.retryPendingForTests()
  assertEquals(pending,progress(name).toString());assertEquals(base+100,latestTime(name))
  assertEquals(90L,total(store,base,base+1000,UsageMetric.PRIMARY))
  // Same-time retry is safe: the original acquisition was already completed at this exact wall time.
  wall=base+200;assertEquals("已根据本地事件更新统计",store.retryPendingForTests())
  assertEquals(190L,total(store,base,base+1000,UsageMetric.PRIMARY));assertEquals(base+200,latestTime(name))
 }}
 @Test fun firstFailedSyncRetriesFromSavedQueryStartRatherThanEpoch()=runBlocking{fixture{store,name->
  val base=System.currentTimeMillis()-600000;val raw=events(base).take(3);var wall=base+100
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,1000,"boot-a")})
  store.onCollectPhaseForTests{if(it=="stats-written")throw java.io.IOException("first statistics failed")}
  assertTrue(runCatching{store.collect()}.isFailure);assertNull(latestTime(name))
  val queryStart=progress(name).getJSONObject("queried").getLong("start")
  store.onCollectPhaseForTests(null);wall+=100
  assertEquals("已根据本地事件更新统计",store.retryPendingForTests())
  assertEquals(base+100,latestTime(name));assertEquals(queryStart,store.first())
  assertEquals(90L,total(store,base,base+1000,UsageMetric.PRIMARY))
 }}
 @Test fun ownEvidenceBeyondAcquisitionRemainsPendingUntilNextQuery()=runBlocking{fixture{store,name->
  val base=System.currentTimeMillis()-600000;var wall=base+100;val raw=events(base).take(3)
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,wall-base+900,"boot-a")})
  store.collect();wall=base+200
  store.onCollectPhaseForTests{if(it=="stats-written")throw java.io.IOException("pending statistics")}
  assertTrue(runCatching{store.collect()}.isFailure);store.onCollectPhaseForTests(null)
  store.ownEvent(Signal.RESUME,"Fixture","future-own",base+250,true,false)
  wall=base+400;store.retryPendingForTests()
  assertEquals(base+200,latestTime(name));assertEquals(base+250,progress(name).getLong("dirtyFrom"))
  assertTrue(store.read(Window(base+200,base+400)).slices.isEmpty())
  assertEquals("已保存最新记录",store.collect());assertTrue(progress(name).isNull("dirtyFrom"))
 }}
 @Test fun layeredHourDayMonthCachesMatchSlicesAndInvalidateTogether()=runBlocking{fixture{store,_->
  val base=System.currentTimeMillis()-600000;var wall=base+100;var elapsed=1000L;val raw=events(base).toMutableList()
  store.useSyntheticSourceForTests({a,b,_->raw.asSequence().filter{it.time>=a&&it.time<b}},{ClockSample(wall,elapsed,"fixture-boot")})
  store.collect();val z=java.time.ZoneId.of("UTC");val w=Window(base,base+100)
  suspend fun reference(metric:UsageMetric):Triple<Map<String,Long>,Long,MetricSummary>{
   val data=store.read(w,metric)
   val totals=data.intervals.groupBy{it.app}.mapValues{(_,items)->Reports.unionDuration(items.map{Window(it.start,it.end)},w)}
   return Triple(totals,Reports.unionDuration(data.coverage.filter{it.reliable}.map{Window(it.start,it.end)},w),DualReports.summary(data.slices,metric,w,null))
  }
  suspend fun checkLayer(g:Grain,metric:UsageMetric,expectedTotal:Long){
   val r=store.aggregate(w,z,g,metric,"fixture.a");val (totals,covered,q)=reference(metric)
   assertEquals(totals.filterKeys{it=="fixture.a"},r.apps.associate{it.app to it.millis})
   assertEquals(expectedTotal,r.apps.singleOrNull()?.millis);assertEquals(covered,r.coveredMillis);assertEquals(q,r.quality)
  }
  for(metric in listOf(UsageMetric.PRIMARY,UsageMetric.VISIBLE))for(g in listOf(Grain.HOUR,Grain.DAY,Grain.MONTH)){
   checkLayer(g,metric,if(metric==UsageMetric.PRIMARY)30L else 70L)
  }
  raw+=DualCodec.event(base+20,Signal.STOP,"fixture.a","A",zone="UTC");wall+=100;elapsed+=100;store.collect()
  for(metric in listOf(UsageMetric.PRIMARY,UsageMetric.VISIBLE))for(g in listOf(Grain.HOUR,Grain.DAY,Grain.MONTH)){
   checkLayer(g,metric,if(metric==UsageMetric.PRIMARY)10L else 50L)
  }
 }}
}
