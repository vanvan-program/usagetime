package dev.usagetime.app
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.usagetime.app.data.UsageStore
import dev.usagetime.core.Window
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Isolated databases only. Never restore, corrupt or clear the user's real usage-history.db. */
@RunWith(AndroidJUnit4::class)
class StorageSafetyTest{
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
 private suspend fun fixture(action:suspend (UsageStore,String)->Unit){
  val name="safety-test-${UUID.randomUUID()}.db";val store=UsageStore(context,name)
  try{action(store,name)}finally{
   runCatching{store.close()}
   context.databaseList().filter{it.startsWith(name)}.forEach{context.deleteDatabase(it)}
   val dir=File(context.noBackupFilesDir,"recovery-$name")
   require(dir.canonicalFile.parentFile==context.noBackupFilesDir.canonicalFile);dir.deleteRecursively()
   // Wait for this isolated fixture's outstanding apply() writes before removing its XML.
   check(context.getSharedPreferences("usage-settings-$name",0).edit().commit())
   check(context.deleteSharedPreferences("usage-settings-$name"))
  }
 }
 private fun backup(pkg:String,start:Long)="""{"format":"usagetime-backup","version":1,"intervals":[{"app":"$pkg","start":$start,"end":${start+1000}}]}"""
 @Test fun cachedLegacyReportInvalidatesOnImportRestoreAndUndo()=runBlocking{fixture{store,_->
  val w=Window(0,5000);val z=java.time.ZoneId.of("UTC")
  suspend fun report()=store.aggregateLegacy(w,z,dev.usagetime.core.Grain.DAY,null)
  store.importJson(backup("first",1000));val first=report();assertSame(first,report())
  store.createSafetyCopy();val saved=store.storageInfo().copies.first()
  store.importJson(backup("second",3000));val merged=report()
  assertNotSame(first,merged);assertEquals(2,merged.apps.size)
  store.restoreSafetyCopy(snapshotTime=saved);assertEquals(1,report().apps.size)
  store.restoreSafetyCopy(true);assertEquals(2,report().apps.size)
 }}
 @Test fun failedImportInvalidatesCacheAndRebuildsRolledBackReport()=runBlocking{fixture{store,_->
  store.importJson(backup("original",1000));val w=Window(0,5000);val z=java.time.ZoneId.of("UTC")
  val before=store.aggregateLegacy(w,z,dev.usagetime.core.Grain.HOUR,null)
  assertTrue(runCatching{store.importJson("invalid json")}.isFailure)
  val after=store.aggregateLegacy(w,z,dev.usagetime.core.Grain.HOUR,null)
  assertNotSame(before,after);assertEquals(before,after)
 }}
 @Test fun verifiedBackupRestoresAndUndoPreservesBothStates()=runBlocking{fixture{store,_->
  store.importJson(backup("first",1000));store.createSafetyCopy()
  store.importJson(backup("second",3000))
  assertEquals(2,store.readLegacy(Window(0,5000)).intervals.size)
  store.restoreSafetyCopy(snapshotTime=store.storageInfo().copies.last());assertEquals(1,store.readLegacy(Window(0,5000)).intervals.size)
  assertTrue(store.storageInfo().canUndo)
  store.restoreSafetyCopy(true);assertEquals(2,store.readLegacy(Window(0,5000)).intervals.size)
 }}
 @Test fun damagedDatabaseIsNotDeletedByOpenHelper()=runBlocking{fixture{store,name->
  val file=context.getDatabasePath(name);file.parentFile!!.mkdirs();val bytes="damaged isolated test database".toByteArray();file.writeBytes(bytes)
  assertTrue(runCatching{store.first()}.isFailure)
  assertTrue(file.exists());assertArrayEquals(bytes,file.readBytes())
 }}
 @Test fun invalidImportRollsBackAndLeavesSafetyCopyUsable()=runBlocking{fixture{store,_->
  store.importJson(backup("original",1000))
  val bad="""{"format":"usagetime-backup","version":1,"intervals":[{"app":"new","start":3000,"end":4000},{"app":"bad","start":100,"end":1}]}"""
  assertTrue(runCatching{store.importJson(bad)}.isFailure)
  assertEquals(1,store.readLegacy(Window(0,5000)).intervals.size)
  assertTrue(store.storageInfo().snapshots>0)
  store.restoreSafetyCopy();assertEquals(1,store.readLegacy(Window(0,5000)).intervals.size)
 }}
 @Test fun intervalOnlyLegacyAllOriginIsRetainedAcrossSafetyRestore()=runBlocking{fixture{store,_->
  store.importJson(backup("historical",1000));assertEquals(1000L,store.first())
  store.createSafetyCopy();store.restoreSafetyCopy();assertEquals(1000L,store.first())
 }}
 @Test fun compressedBackupImportsWithoutWholeFileString()=runBlocking{fixture{source,_->
  source.importJson(backup("compressed",1000));val out=java.io.ByteArrayOutputStream();source.writeCompressedBackup(out)
  fixture{target,_->target.importBackup(out.toByteArray().inputStream());assertEquals(1,target.readLegacy(Window(0,3000)).intervals.size)}
 }}
 @Test fun corruptCompressedBackupCannotPartiallyImport()=runBlocking{fixture{source,_->
  source.importJson(backup("incoming",3000));val out=java.io.ByteArrayOutputStream();source.writeCompressedBackup(out);val bytes=out.toByteArray();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte()
  fixture{target,_->target.importJson(backup("original",1000));assertTrue(runCatching{target.importBackup(bytes.inputStream())}.isFailure);assertEquals(1,target.readLegacy(Window(0,5000)).intervals.size)}
 }}

 @Test fun supportedVersionsAndUnknownFieldsKeepRepeatedImportIdempotent()=runBlocking{
  for(version in 1..3)fixture{store,_->
   val body=org.json.JSONObject(backup("version-$version",1000)).put("version",version)
   body.put("futureTable",org.json.JSONArray().put(org.json.JSONObject().put("nested",true)))
   body.getJSONArray("intervals").getJSONObject(0).put("futureScalar","ignored")
   repeat(2){store.importJson(body.toString())}
   val rows=store.readLegacy(Window(0,5000)).intervals
   assertEquals(1,rows.size);assertEquals("version-$version",rows.single().app)
  }
 }
 @Test fun compressedRoundTripPreservesAllFiveRecordTablesAfterInitialDowngrade()=runBlocking{fixture{source,_->
  val body=org.json.JSONObject(backup("legacy",1000)).put("version",3)
  body.put("coverage",org.json.JSONArray("""[{"start":1000,"end":2000,"reliable":true}]"""))
  body.put("apps",org.json.JSONArray("""[{"app":"legacy","label":"fixture"}]"""))
  body.put("usage_events",org.json.JSONArray("""[{"id":"event","time":1000,"signal":"RESUME","app":"legacy","activity":"Main","instance":"instance","source":"USAGE_EVENTS","zone":"UTC","origin":"LOCAL","rawType":1}]"""))
  body.put("usage_intervals",org.json.JSONArray("""[{"id":"slice","metric":"PRIMARY","app":"legacy","start":1000,"end":2000,"evidence":"CONFIRMED","reason":"original","sources":"USAGE_EVENTS","refs":"event","provisional":true}]"""))
  source.importJson(body.toString())
  val before=org.json.JSONObject(source.exportJson())
  val compressed=java.io.ByteArrayOutputStream();source.writeCompressedBackup(compressed)
  fixture{target,_->
   repeat(2){target.importBackup(compressed.toByteArray().inputStream())}
   val after=org.json.JSONObject(target.exportJson())
   for(table in listOf("intervals","coverage","apps","usage_events","usage_intervals")){
    val expected=before.getJSONArray(table);val actual=after.getJSONArray(table)
    assertEquals(table,1,expected.length());assertEquals(table,1,actual.length())
    val left=expected.getJSONObject(0);val right=actual.getJSONObject(0)
    assertEquals(table,left.keys().asSequence().toSet(),right.keys().asSequence().toSet())
    left.keys().forEach{key->assertEquals("$table.$key",left.get(key),right.get(key))}
   }
   val slice=after.getJSONArray("usage_intervals").getJSONObject(0)
   assertEquals("INFERRED",slice.getString("evidence"));assertEquals("LEGACY_IMPORT",slice.getString("sources"))
  }
 }}
 private fun batchedBackup():String{
  val rows=(0..1000).joinToString(","){i->"""{"app":"batch-$i","start":${10000+i*2},"end":${10001+i*2}}"""}
  return """{"format":"usagetime-backup","intervals":[$rows],"version":1}"""
 }
 @Test fun invalidEnvelopeAfterBatchFlushRollsBackEveryIncomingRow()=runBlocking{fixture{store,_->
  store.importJson(backup("original",1000))
  val invalid=batchedBackup().replace("\"version\":1","\"version\":99")
  assertTrue(runCatching{store.importJson(invalid)}.isFailure)
  val rows=store.readLegacy(Window(0,20000)).intervals
  assertEquals(1,rows.size);assertEquals("original",rows.single().app)
  assertTrue(store.storageInfo().snapshots>0)
 }}
 @Test fun cancellationAfterBatchFlushRollsBackEveryIncomingRow()=runBlocking{fixture{store,_->
  store.importJson(backup("original",1000))
  val body=batchedBackup();val bytes=body.toByteArray(Charsets.UTF_8)
  val cutoff=body.indexOf("\"version\"");var position=0
  val cancelled=object:java.io.InputStream(){
   override fun read():Int{
    if(position>=cutoff)throw kotlinx.coroutines.CancellationException("isolated backup fixture")
    return bytes[position++].toInt() and 255
   }
   override fun read(buffer:ByteArray,offset:Int,length:Int):Int{
    if(length==0)return 0
    if(position>=cutoff)throw kotlinx.coroutines.CancellationException("isolated backup fixture")
    val size=minOf(length,64,cutoff-position);bytes.copyInto(buffer,offset,position,position+size);position+=size;return size
   }
  }
  val failure=runCatching{store.importBackup(cancelled)}.exceptionOrNull()
  assertTrue(failure is kotlinx.coroutines.CancellationException)
  val rows=store.readLegacy(Window(0,20000)).intervals
  assertEquals(1,rows.size);assertEquals("original",rows.single().app)
 }}

 @Test fun missingOrEmptyMainDbRequiresExplicitRecoveryInsteadOfSilentRecreation()=runBlocking{
  for(empty in listOf(false,true))fixture{store,name->
   store.importJson(backup("surviving",1000));store.createSafetyCopy();store.close()
   context.deleteDatabase(name);if(empty)context.getDatabasePath(name).writeBytes(byteArrayOf())
   val reopened=UsageStore(context,name)
   try{
    assertTrue(runCatching{reopened.first()}.isFailure)
    assertTrue(reopened.storageInfo().snapshots>0)
    reopened.restoreSafetyCopy();assertEquals(1,reopened.readLegacy(Window(0,3000)).intervals.size)
   }finally{reopened.close()}
  }
 }
 @Test fun unversionedSqliteCandidateCannotBecomeAnEmptyHistory()=runBlocking{fixture{store,name->
  store.importJson(backup("current",1000));store.close()
  val empty=context.getDatabasePath(name+".empty-fixture")
  android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(empty,null).use{it.execSQL("CREATE TABLE unrelated(x INTEGER)")}
  val copies=File(context.noBackupFilesDir,"recovery-$name")
  dev.usagetime.core.LocalRecovery(copies,empty,{Long.MAX_VALUE},{System.currentTimeMillis()+10000}).create()
  val reopened=UsageStore(context,name)
  try{assertTrue(runCatching{reopened.restoreSafetyCopy()}.isFailure);assertEquals(1,reopened.readLegacy(Window(0,3000)).intervals.size)}finally{reopened.close()}
 }}


 @Test fun interruptedRecoveryErrorKeepsSafetyStatusAccessibleWithoutOpeningDb()=runBlocking{fixture{store,name->
  store.importJson(backup("original",1000));store.close()
  val marker=java.io.File(context.getDatabasePath(name).path+".restore-journal");marker.writeText("invalid journal")
  val reopened=UsageStore(context,name)
  try{
   assertNotNull(reopened.storageInfo().warning)
   assertTrue(runCatching{reopened.first()}.isFailure)
   assertTrue(marker.exists())
  }finally{reopened.close()}
 }}
}
