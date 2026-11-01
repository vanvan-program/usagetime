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
