package dev.usagetime.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import androidx.room.withTransaction
import dev.usagetime.app.data.*
import dev.usagetime.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DualStorageTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Test fun productionMigrationRetainsAllLegacyTables()=runBlocking{
        val name="migration-${java.util.UUID.randomUUID()}.db"
        val schema=JSONObject(instrumentation.context.assets.open("dev.usagetime.app.data.UsageDatabase/1.json").bufferedReader().readText()).getJSONObject("database")
        context.openOrCreateDatabase(name,0,null).use{db->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()){
                val table=entities.getJSONObject(i);val tableName=table.getString("tableName")
                db.execSQL(table.getString("createSql").replace("\${TABLE_NAME}",tableName))
                val indexes=table.optJSONArray("indices")
                if(indexes!=null)for(j in 0 until indexes.length())db.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",tableName))
            }
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES(42,?)",arrayOf(schema.getString("identityHash")))
            db.execSQL("INSERT INTO intervals VALUES('fixture',1000,2000)")
            db.execSQL("INSERT INTO coverage VALUES(1000,2000,1)")
            db.execSQL("INSERT INTO cursor VALUES(0,2000,'fixture','A',1000,0,1)")
            db.execSQL("INSERT INTO apps VALUES('fixture','Fixture')")
            db.version=1
        }
        val db=Room.databaseBuilder(context,UsageDatabase::class.java,name).addMigrations(DualMigration.FROM_1_TO_2,DualMigration.FROM_2_TO_3,DualMigration.FROM_3_TO_4,DualMigration.FROM_4_TO_5).build()
        assertEquals(1,db.usage().intervals(0,3000).size)
        assertEquals(1,db.usage().coverage(0,3000).size)
        assertEquals(2000L,db.usage().cursor()!!.time)
        assertEquals("Fixture",db.usage().apps().single().label)
        assertTrue(db.dual().events(0,3000).isEmpty())
        db.close()
    }
    @Test fun interruptedRoomTransactionDoesNotAdvanceCheckpoint()=runBlocking{
        val db=Room.inMemoryDatabaseBuilder(context,UsageDatabase::class.java).build()
        val event=DualCodec.event(1000,Signal.RESUME,"fixture","A",zone="UTC")
        assertTrue(runCatching{db.withTransaction{
            db.dual().events(listOf(event))
            db.dual().checkpoint(SyncCheckpoint(2000,DualCodec.encode(EngineState()),2000,1000,"boot","UTC"))
            error("injected failure")
        }}.isFailure)
        assertTrue(db.dual().events(0,3000).isEmpty());assertNull(db.dual().latest());db.close()
    }
    @Test fun streamingBackupRoundTripKeepsMetricsButDoesNotPromoteImportedEvidence()=runBlocking{
        val store=UsageStore(context,"roundtrip-${java.util.UUID.randomUUID()}.db")
        val slice=DualCodec.stored(UsageSlice(UsageMetric.PRIMARY,"fixture",1000,2000,Evidence.CONFIRMED,"EXPLICIT",setOf(EvidenceSource.TEST),setOf("proof")))
        val row=JSONObject().put("id",slice.id).put("metric",slice.metric).put("app",slice.app).put("start",slice.start).put("end",slice.end).put("evidence",slice.evidence).put("reason",slice.reason).put("sources",slice.sources).put("refs",slice.refs).put("provisional",false)
        val backup=JSONObject().put("format","usagetime-backup").put("version",2).put("usage_intervals",org.json.JSONArray().put(row)).toString()
        store.importJson(backup);store.importJson(backup)
        val read=store.read(Window(0,3000),UsageMetric.PRIMARY)
        assertEquals(1000L,read.intervals.sumOf{it.end-it.start});assertTrue(read.slices.all{it.evidence!=Evidence.CONFIRMED})
        val exported=store.exportJson();assertEquals(2,JSONObject(exported).getInt("version"));assertTrue(exported.contains("originalEvidence"))
        val before=store.readLegacy(Window(0,3000)).intervals.size
        val invalid="""{"format":"usagetime-backup","version":1,"intervals":[{"app":"fixture","start":1000,"end":2000},{"app":"bad","start":1000,"end":-1}]}"""
        assertTrue(runCatching{store.importJson(invalid)}.isFailure);assertEquals(before,store.readLegacy(Window(0,3000)).intervals.size)
    }
    @Test fun checkpointCodecPreservesUnresolvedAndExplicitIdentities(){
        val node=ActivityNode("fixture","Same",null,ActivityStage.PAUSED,EvidenceSource.USAGE_EVENTS,1000,"origin")
        val state=EngineState(mapOf("unresolved" to node),Knowledge.YES,Knowledge.NO,mapOf("own" to "fixture"),setOf("r"),"screen","lock",mapOf("own" to "focus"))
        assertEquals(state,DualCodec.decode(DualCodec.encode(state)))
    }
}
