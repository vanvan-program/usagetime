package dev.usagetime.app

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.StatFs
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.usagetime.core.LocalRecovery
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real data metadata only: no restore, export, migration, deletion, or usage details. */
@RunWith(AndroidJUnit4::class)
class LiveStorageReadOnlyTest {
 @Test fun historyCountsAndPrivateCopyIntegrity(){
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val context=instrumentation.targetContext
  val file=context.getDatabasePath("usage-history.db")
  assertTrue("Existing history database is required",file.isFile)
  val summary=JSONObject().put("phase",InstrumentationRegistry.getArguments().getString("phase","check"))
  SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READONLY,DatabaseErrorHandler{throw android.database.sqlite.SQLiteDatabaseCorruptException("Read-only verification failed; file retained")}).use{db->
   assertEquals(5,db.version)
   db.rawQuery("PRAGMA quick_check(1)",null).use{c->assertTrue(c.moveToFirst());assertEquals("ok",c.getString(0))}
   db.rawQuery("SELECT COUNT(*),COALESCE(MAX(length(refs)),0) FROM usage_intervals WHERE algorithm=2 AND provisional=1",null).use{c->assertTrue(c.moveToFirst());summary.put("provisionalRows",c.getLong(0)).put("maxPendingRefsLength",c.getLong(1))}
   db.rawQuery("PRAGMA freelist_count",null).use{c->assertTrue(c.moveToFirst());summary.put("freePages",c.getLong(0))}
   db.rawQuery("PRAGMA page_size",null).use{c->assertTrue(c.moveToFirst());summary.put("pageSize",c.getLong(0))}
   for((key,table) in linkedMapOf("legacyIntervals" to "intervals","legacyCoverage" to "coverage","rawEvents" to "usage_events","metricIntervals" to "usage_intervals","dailyCache" to "daily_usage","checkpoints" to "sync_checkpoints")){
    db.rawQuery("SELECT COUNT(*) FROM $table",null).use{c->assertTrue(c.moveToFirst());summary.put(key,c.getLong(0))}
   }
  }
  val recovery=LocalRecovery(File(context.noBackupFilesDir,"recovery-usage-history.db"),file)
  val copies=recovery.list();copies.forEach{recovery.verify(it)}
  summary.put("snapshots",copies.size).put("snapshotVerification",if(copies.isEmpty())"NONE" else "PASS")
   .put("snapshotBytes",copies.sumOf{it.file.length()}).put("databaseBytes",file.length())
   .put("freeBytes",StatFs(context.filesDir.absolutePath).availableBytes)
   .put("pendingRestore",File(file.path+".restore-journal").exists())
  instrumentation.sendStatus(2,Bundle().apply{putString("usagetime_summary",summary.toString())})
 }
}
