package dev.usagetime.app

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.usagetime.app.data.*
import dev.usagetime.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** No production database names, installs, data clears or restore targets. */
@RunWith(AndroidJUnit4::class)
class HistoryRetentionTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val zone=ZoneId.of("UTC")
    private val now=Dates.midnight(LocalDate.of(2026,11,1),zone)
    private val floor get()=HistoryRetention.cutoff(now,zone)
    private fun database()=Room.inMemoryDatabaseBuilder(context,UsageDatabase::class.java).build()
    private fun rows(start:Long,metric:UsageMetric)=HistoryRetention.rows(
        DailyMetrics.cells(listOf(UsageSlice(metric,"fixture.app",start,start+1000,Evidence.INFERRED,"TEST",setOf(EvidenceSource.TEST),emptySet())),
            metric,Window(start,start+86400000)),metric,zone)
    private suspend fun seed(db:UsageDatabase){
        val day=floor-86400000
        db.dual().events(listOf(DualCodec.event(day,Signal.RESUME,"fixture.app",zone=zone.id)))
        db.dual().slices(listOf(DualCodec.stored(UsageSlice(UsageMetric.PRIMARY,"fixture.app",day,day+1000,Evidence.INFERRED,"TEST",setOf(EvidenceSource.TEST),emptySet()))))
        db.dual().checkpoint(SyncCheckpoint(floor,DualCodec.encode(EngineState()),floor,1,"test",zone.id))
        db.dual().checkpoint(SyncCheckpoint(-1,DualCodec.encodeProgress(ReplayProgress(true)),0,-1,"","",0))
        for(metric in UsageMetric.entries)HistoryRetention.save(db,rows(day,metric))
    }
    @Test fun everySupportedSchemaUpgradesWithoutLosingLegacyOrDualHistory()=runBlocking{
        for(version in 1..5){
            val name="safety-test-migration-${UUID.randomUUID()}.db"
            val schema=JSONObject(instrumentation.context.assets.open("dev.usagetime.app.data.UsageDatabase/$version.json").bufferedReader().use{it.readText()}).getJSONObject("database")
            context.openOrCreateDatabase(name,0,null).use{raw->
                val tables=schema.getJSONArray("entities")
                for(i in 0 until tables.length()){
                    val table=tables.getJSONObject(i);val tableName=table.getString("tableName")
                    raw.execSQL(table.getString("createSql").replace("\${TABLE_NAME}",tableName))
                    val indexes=table.optJSONArray("indices")
                    if(indexes!=null)for(j in 0 until indexes.length())raw.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",tableName))
                }
                raw.execSQL("INSERT INTO intervals VALUES('fixture',1000,2000)")
                if(version>=2)raw.execSQL("INSERT INTO usage_events VALUES('fixture',1000,'RESUME','fixture','A',NULL,'USAGE_EVENTS','UTC','LOCAL',NULL)")
                raw.version=version
            }
            val db=Room.databaseBuilder(context,UsageDatabase::class.java,name).addMigrations(
                DualMigration.FROM_1_TO_2,DualMigration.FROM_2_TO_3,DualMigration.FROM_3_TO_4,DualMigration.FROM_4_TO_5,HistoryMigration.FROM_5_TO_6).build()
            try{
                assertEquals(1,db.usage().intervals(0,3000).size)
                assertEquals(if(version>=2)1 else 0,db.dual().events(0,3000).size)
                assertNull(db.history().first())
                db.withTransaction{HistoryRetention.save(db,rows(floor,UsageMetric.PRIMARY))}
                assertNotNull(db.history().seal("UTC",floor,"PRIMARY"))
            }finally{db.close();check(name.startsWith("safety-test-"));context.deleteDatabase(name)}
        }
    }
    @Test fun repeatedDailyWritesReplaceTheWholeBucketAndNeverAddTotals()=runBlocking{
        val db=database()
        try{
            db.withTransaction{repeat(3){HistoryRetention.save(db,rows(floor,UsageMetric.PRIMARY))}}
            val rows=db.history().rows("UTC",floor,"PRIMARY")
            assertEquals(1000L,rows.single{it.app=="fixture.app"}.millis)
            assertEquals(3,rows.size)
            HistoryRetention.validate(rows,db.history().seal("UTC",floor,"PRIMARY")!!)
        }finally{db.close()}
    }
    @Test fun missingMetricCorruptSealDirtyReplayOpenTailAndMissingAnchorAllBlockDeletion()=runBlocking{
        for(fault in listOf("metric","digest","dirty","open","anchor")){
            val db=database()
            try{
                db.withTransaction{
                    seed(db);val raw=db.openHelper.writableDatabase
                    when(fault){
                        "metric"->raw.execSQL("DELETE FROM history_seals WHERE metric='VISIBLE'")
                        "digest"->raw.execSQL("UPDATE history_seals SET digest='bad'")
                        "dirty"->db.dual().checkpoint(SyncCheckpoint(-1,DualCodec.encodeProgress(ReplayProgress(true,floor-1)),0,-1,"","",0))
                        "open"->raw.execSQL("UPDATE usage_intervals SET provisional=1")
                        "anchor"->raw.execSQL("DELETE FROM sync_checkpoints WHERE time=?",arrayOf(floor))
                    }
                }
                assertTrue(fault,runCatching{db.withTransaction{HistoryRetention.compact(db,now,zone)}}.isFailure)
                assertEquals(1,db.dual().events(0,floor).size)
                assertEquals(1,db.dual().slices(0,floor,"PRIMARY").size)
                assertNull(db.history().state())
            }finally{db.close()}
        }
    }
    @Test fun deletionAndFloorRollbackTogetherThenRetryIsIdempotentAndBoundaryIsRetained()=runBlocking{
        val db=database()
        try{
            db.withTransaction{seed(db);db.dual().events(listOf(DualCodec.event(floor,Signal.PAUSE,"fixture.app",zone="UTC")))}
            assertTrue(runCatching{db.withTransaction{HistoryRetention.compact(db,now,zone);error("power loss before commit")}}.isFailure)
            assertEquals(1,db.dual().events(0,floor).size);assertNull(db.history().state())
            db.withTransaction{assertEquals(floor,HistoryRetention.compact(db,now,zone))}
            assertTrue(db.dual().events(0,floor).isEmpty())
            assertEquals(1,db.dual().events(floor,floor+1).size)
            assertEquals(1000L,db.history().rows("UTC",floor-86400000,"PRIMARY").single{it.app=="fixture.app"}.millis)
            db.withTransaction{assertEquals(0L,HistoryRetention.compact(db,now,zone))}
            assertTrue(runCatching{db.withTransaction{HistoryRetention.invalidate(db,floor-1)}}.isFailure)
        }finally{db.close()}
    }
    @Test fun summaryOnlyCompressedBackupRestoresWithoutActivatingRetentionOrLocalConfidence()=runBlocking{
        val sourceName="safety-test-history-source-${UUID.randomUUID()}.db"
        val targetName="safety-test-history-target-${UUID.randomUUID()}.db"
        val seedDb=Room.databaseBuilder(context,UsageDatabase::class.java,sourceName).build()
        seedDb.withTransaction{for(metric in UsageMetric.entries)HistoryRetention.save(seedDb,rows(floor-86400000,metric))}
        seedDb.close()
        val source=UsageStore(context,sourceName);val target=UsageStore(context,targetName)
        try{
            val out=java.io.ByteArrayOutputStream();source.writeCompressedBackup(out)
            repeat(2){target.importBackup(out.toByteArray().inputStream())}
            val report=target.aggregate(Window(floor-86400000,floor),zone,Grain.DAY,UsageMetric.PRIMARY,null)
            assertEquals(1000L,report.apps.single().millis)
            assertEquals(0L,report.quality!!.confirmed)
            assertEquals(3,JSONObject(target.exportJson()).getInt("version"))
            assertEquals(0,JSONObject(target.exportJson()).getJSONArray("retention_state").length())
            val truncated=out.toByteArray().copyOf(out.size()-6)
            assertTrue(runCatching{target.importBackup(truncated.inputStream())}.isFailure)
        }finally{
            source.close();target.close()
            for(name in listOf(sourceName,targetName)){
                check(name.startsWith("safety-test-history-"));context.deleteDatabase(name)
                val dir=java.io.File(context.noBackupFilesDir,"recovery-$name")
                check(dir.canonicalFile.parentFile==context.noBackupFilesDir.canonicalFile);dir.deleteRecursively()
                context.getSharedPreferences("usage-settings-$name",0).edit().commit()
                context.deleteSharedPreferences("usage-settings-$name")
            }
        }
    }
}
