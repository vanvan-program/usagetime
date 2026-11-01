package dev.usagetime.app.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.os.PowerManager
import android.app.KeyguardManager
import android.provider.Settings
import java.time.ZoneId
import android.os.UserManager
import androidx.room.*
import dev.usagetime.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

@Entity(tableName="intervals", primaryKeys=["app","start","end"], indices=[Index("start"),Index("end")])
data class SavedInterval(val app:String,val start:Long,val end:Long)
@Entity(tableName="coverage", primaryKeys=["start","end"], indices=[Index("end")])
data class SavedCoverage(val start:Long,val end:Long,val reliable:Boolean)
@Entity(tableName="cursor")
data class Cursor(@PrimaryKey val id:Int=0,val time:Long,val app:String,val activity:String,val since:Long,val blocked:Boolean,val known:Boolean)
@Entity(tableName="apps")
data class SavedApp(@PrimaryKey val app:String,val label:String)
@Dao
interface UsageDao {
    @Query("SELECT * FROM intervals WHERE `end` > :start AND start < :end ORDER BY start")
    suspend fun intervals(start:Long,end:Long):List<SavedInterval>
    @Query("SELECT EXISTS(SELECT 1 FROM intervals WHERE `end`>:start AND start<:end LIMIT 1)")
    suspend fun hasIntervals(start:Long,end:Long):Boolean
    @Query("SELECT * FROM coverage WHERE `end` > :start AND start < :end ORDER BY start")
    suspend fun coverage(start:Long,end:Long):List<SavedCoverage>
    @Query("SELECT MIN(start) FROM (SELECT start FROM coverage UNION ALL SELECT start FROM intervals)") suspend fun first():Long?
    @Query("SELECT * FROM cursor WHERE id=0") suspend fun cursor():Cursor?
    @Query("SELECT * FROM apps") suspend fun apps():List<SavedApp>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun intervals(values:List<SavedInterval>)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun coverage(values:List<SavedCoverage>)
    @Query("UPDATE coverage SET reliable=1 WHERE start=:start AND `end`=:end") suspend fun upgradeCoverage(start:Long,end:Long)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun cursor(value:Cursor)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun apps(values:List<SavedApp>)
}
@Database(entities=[SavedInterval::class,SavedCoverage::class,Cursor::class,SavedApp::class,StoredEvent::class,StoredSlice::class,DailyUsage::class,HourUsage::class,MonthUsage::class,SyncCheckpoint::class,DiagnosticLog::class,HistoryDay::class,HistorySeal::class,RetentionState::class],version=6,exportSchema=true)
abstract class UsageDatabase:RoomDatabase(){abstract fun usage():UsageDao;abstract fun dual():DualDao;abstract fun history():HistoryDao}
data class Records(val intervals:List<Interval>,val coverage:List<Coverage>,val slices:List<UsageSlice> = emptyList())

class UsageStore(private val context:Context,databaseName:String="usage-history.db") {
    private val databaseFile=context.getDatabasePath(databaseName)
    private val missingGuard=File(databaseFile.path+".missing-guard")
    private var recoveryIssue:String?=null
    private fun syncFolder(dir:File){
        val fd=Os.open(dir.absolutePath,OsConstants.O_RDONLY,0)
        try{Os.fsync(fd)}finally{Os.close(fd)}
    }
    private val recovery=LocalRecovery(File(context.noBackupFilesDir,"recovery-$databaseName"),databaseFile,
        available={StatFs(context.filesDir.absolutePath).availableBytes},syncDirectory=::syncFolder)
    private fun openDatabase(name:String)=Room.databaseBuilder(context,UsageDatabase::class.java,name)
        .addMigrations(DualMigration.FROM_1_TO_2,DualMigration.FROM_2_TO_3,DualMigration.FROM_3_TO_4,DualMigration.FROM_4_TO_5,HistoryMigration.FROM_5_TO_6).openHelperFactory(PreservingOpenHelperFactory()).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addCallback(object:RoomDatabase.Callback(){
            private fun guard(){
                if(name==databaseFile.name&&recoveryIssue!=null)throw android.database.sqlite.SQLiteDatabaseCorruptException("恢复尚未完成，原文件已保留：$recoveryIssue")
                if(name==databaseFile.name&&missingGuard.exists())throw android.database.sqlite.SQLiteDatabaseCorruptException("主数据库缺失或被截断，已停止空库创建；请在数据安全页面恢复副本")}
            override fun onCreate(db:androidx.sqlite.db.SupportSQLiteDatabase){guard()}
            override fun onOpen(db:androidx.sqlite.db.SupportSQLiteDatabase){
            guard()
            db.execSQL("PRAGMA synchronous=FULL")
            db.query("PRAGMA wal_autocheckpoint=256").close()
        }}).build()
    private var database:UsageDatabase
    init{
        // Keep settings/recovery UI accessible even if startup rollback cannot finish.
        try{recovery.recoverInterrupted()}catch(e:Exception){recoveryIssue=e.message?:e.javaClass.simpleName}
        if(recoveryIssue==null&&(!databaseFile.exists()||databaseFile.length()<512)&&recovery.list().isNotEmpty()){
            databaseFile.parentFile!!.mkdirs()
            java.io.FileOutputStream(missingGuard).use{it.write("restore-required".toByteArray());it.fd.sync()};syncFolder(databaseFile.parentFile!!)
        }
        database=openDatabase(databaseName)
    }
    private val dao get()=database.usage()
    private val dual get()=database.dual()
    private val dualHistory get()=database.history()
    private val mutex=Mutex()
    private data class ReportKey(val window:Window,val zone:String,val grain:Grain,val metric:UsageMetric?,val app:String?,val includeConfirmed:Boolean=true)
    private val reportCache=ReportCache<ReportKey>()
    private val revision=kotlinx.coroutines.flow.MutableStateFlow(0L)
    val reportRevision:kotlinx.coroutines.flow.StateFlow<Long> get()=revision
    // Called while holding mutex, before a writer can change or replace the database.
    // Readers cannot repopulate until the write finishes (including rollback on failure).
    private fun invalidateReports(){reportCache.clear();revision.value=revision.value+1}
    private suspend fun cachedReport(key:ReportKey,build:suspend ()->UsageReport):UsageReport=
        withContext(Dispatchers.IO){mutex.withLock{
            reportCache[key]?:build().also{currentCoroutineContext().ensureActive();reportCache.put(key,it)}
        }}
    private val preferences=context.getSharedPreferences(if(databaseName=="usage-history.db")"usage-settings"else"usage-settings-$databaseName",Context.MODE_PRIVATE)
    private val names=ConcurrentHashMap<String,String>()
    private var testQuery:((Long,Long,String)->Sequence<StoredEvent>)?=null
    private var testClock:(()->ClockSample)?=null
    private var collectPhaseListener:((String)->Unit)?=null
    internal fun useSyntheticSourceForTests(query:(Long,Long,String)->Sequence<StoredEvent>,clock:()->ClockSample){
        require(databaseFile.name.startsWith("safety-test-")&&databaseFile.name.endsWith(".db"))
        testQuery=query;testClock=clock
    }
    internal fun onCollectPhaseForTests(listener:((String)->Unit)?){
        require(databaseFile.name.startsWith("safety-test-")&&databaseFile.name.endsWith(".db"))
        collectPhaseListener=listener
    }
    internal suspend fun retryPendingForTests():String{
        require(databaseFile.name.startsWith("safety-test-")&&databaseFile.name.endsWith(".db"))
        return withContext(Dispatchers.IO){mutex.withLock{processPendingInsideLock()}}
    }
    private suspend fun saveProgress(value:ReplayProgress){
        // Reserved metadata row, excluded from engine checkpoint queries and compaction.
        dual.checkpoint(SyncCheckpoint(-1,DualCodec.encodeProgress(value),0,-1,"","",0))
    }
    private suspend fun markReplayDirty(at:Long){saveProgress(DualCodec.decodeProgress(dual.replayMetadata()).markDirty(at))}
    private suspend fun finishReplay(end:Long){
        val progress=DualCodec.decodeProgress(dual.replayMetadata())
        // Events exactly at the right boundary have not been consumed by the half-open replay.
        saveProgress(ReplayProgress(true,dual.firstRelevantEventFrom(end),progress.queried?.takeIf{it.end>end}))
    }

    data class StorageInfo(val databaseBytes:Long,val snapshotBytes:Long,val freeBytes:Long,val snapshots:Int,val latest:Long?,val canUndo:Boolean,val warning:String?,val copies:List<Long>,val lastExport:Long?)
    fun storageInfo():StorageInfo{
        val copies=recovery.list()
        val dbBytes=databaseFile.parentFile!!.listFiles().orEmpty().filter{it.isFile&&it.name.startsWith(databaseFile.name)}.sumOf{it.length()}
        return StorageInfo(dbBytes,copies.sumOf{it.file.length()},StatFs(context.filesDir.absolutePath).availableBytes,copies.size,copies.firstOrNull()?.time,recovery.hasRollback(),recoveryIssue?.let{"恢复尚未完成，原文件已保留：$it"}?:preferences.getString("safety-warning",null),copies.map{it.time},preferences.getLong("last-full-export",0).takeIf{it>0})
    }
    fun noteFullExport(){preferences.edit().putLong("last-full-export",System.currentTimeMillis()).apply()}
    private fun ensureWriteSpace(){
        if(StatFs(context.filesDir.absolutePath).availableBytes<LocalRecovery.RESERVE){
            preferences.edit().putBoolean("interrupted",true).apply()
            throw java.io.IOException("存储空间不足64MB，已暂停新写入；已有记录保留，请释放空间并导出备份")
        }
    }
    private fun checkpoint(){
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use{c->
            require(c.moveToFirst()&&c.getInt(0)==0){"数据库正在使用，稍后再创建安全副本"}
        }
    }
    private suspend fun snapshotInsideLock(force:Boolean):LocalRecovery.Snapshot?{
        val now=SystemClock.elapsedRealtime();val boot=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
        if(!force&&recovery.list().isNotEmpty()&&preferences.getInt("safety-boot",-2)==boot&&now-preferences.getLong("safety-elapsed",-1) in 0 until 12*3_600_000L)return null
        ensureWriteSpace()
        // A checkpoint is required; never copy a bare DB while committed data remains only in WAL.
        database.openHelper.readableDatabase.query("PRAGMA quick_check(1)").use{c->require(c.moveToFirst()&&c.getString(0)=="ok"){"数据库完整性异常，已保留旧安全副本"}}
        checkpoint();database.close()
        return try{
            require(File(databaseFile.path+"-wal").length()==0L){"数据库日志尚未合并，未创建不完整副本"}
            val task=currentCoroutineContext()
            val result=recovery.create{task.ensureActive()}
            preferences.edit().putInt("safety-boot",boot).putLong("safety-elapsed",now).remove("safety-warning").apply()
            result
        }finally{database=openDatabase(databaseFile.name)}
    }
    private suspend fun archiveBeforeCleanup(now:Long,zone:ZoneId){
        val progress=DualCodec.decodeProgress(dual.replayMetadata())
        if(!progress.initialized||progress.dirtyFrom!=null)return
        val latest=dual.latest()?:return
        val cutoff=minOf(HistoryRetention.cutoff(now,zone),Dates.midnight(Dates.date(latest.time,zone),zone))
        val first=listOfNotNull<Long>(dualHistory.first(),dual.first(),dual.firstEvent()).minOrNull()?.let{Dates.midnight(Dates.date(it,zone),zone)}?:return
        var day=first
        while(day<cutoff){
            currentCoroutineContext().ensureActive()
            for(metric in UsageMetric.entries){
                if(dualHistory.seal(zone.id,day,metric.name)!=null)continue
                val window=Window(day,Dates.midnight(Dates.date(day,zone).plusDays(1),zone))
                // Summary persistence is in this same maintenance transaction as deletion.
                HistoryRetention.save(database,HistoryRetention.rows(calculateDay(window,metric),metric,zone))
            }
            day=Dates.midnight(Dates.date(day,zone).plusDays(1),zone)
        }
    }
    private suspend fun maintainAndSnapshot(force:Boolean=false){
        try{
            val elapsed=SystemClock.elapsedRealtime()
            val boot=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
            // Sync remains immediate. Only housekeeping scans use a six-hour cadence.
            if(force||ElapsedMaintenance.due(elapsed,boot,preferences.getLong("maintenance-elapsed",-1),preferences.getInt("maintenance-boot",-2))){
                val now=System.currentTimeMillis();val zone=ZoneId.systemDefault()
                database.withTransaction{archiveBeforeCleanup(now,zone)}
                try{database.withTransaction{HistoryRetention.compact(database,now,zone)}}
                catch(e:IllegalArgumentException){log(now,"CLEANUP_DEFERRED",e.message?:"保留明细")}
                catch(e:IllegalStateException){log(now,"CLEANUP_DEFERRED",e.message?:"保留明细")}
                database.withTransaction{
                    dual.trimCache(ZoneId.systemDefault().id)
                    dual.trimHourCache(ZoneId.systemDefault().id)
                    dual.trimMonthCache(ZoneId.systemDefault().id)
                    // Keep civil-midnight replay anchors, including a previously committed retention floor.
                    database.openHelper.writableDatabase.execSQL("DELETE FROM sync_checkpoints WHERE algorithm=2 AND time<? AND time NOT IN (SELECT MAX(time) FROM sync_checkpoints WHERE algorithm=2 GROUP BY time/86400000) AND time NOT IN (SELECT MIN(time) FROM sync_checkpoints WHERE algorithm=2 GROUP BY time/86400000) AND time NOT IN (SELECT floor FROM retention_state)",arrayOf(now-100*86400000L))
                    dual.trimDiagnostics(listOf("STATE_RESET","RETENTION_GAP","BOOT_CHANGED","WALL_CLOCK_BACKWARD","WALL_CLOCK_DISCONTINUITY","PERMISSION_INTERRUPTION","PERMISSION_DENIED","SYNC_DELAY","EMPTY_SYSTEM_QUERY","HISTORY_REBUILT","BACKUP_IMPORTED","SYNC_COMMITTED","RECOVERY_SNAPSHOT"))
                }
                preferences.edit().putLong("maintenance-elapsed",elapsed).putInt("maintenance-boot",boot).apply()
            }
            snapshotInsideLock(force)
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){preferences.edit().putString("safety-warning","安全副本尚未更新：${e.message}").apply()}
    }
    suspend fun createSafetyCopy():String=withContext(Dispatchers.IO){mutex.withLock{snapshotInsideLock(true);"已创建并校验本机安全副本"}}
    private fun validateCandidate(file:File){
        // Never let Room turn an empty/unversioned candidate into an apparently healthy new DB.
        android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY,android.database.DatabaseErrorHandler{throw android.database.sqlite.SQLiteDatabaseCorruptException("恢复候选损坏，原文件保留")}).use{raw->
            val required=mutableSetOf("intervals","coverage","cursor","apps")
            require(raw.version in 1..6){"安全副本数据库版本不受支持"}
            if(raw.version>=2)required+=listOf("usage_events","usage_intervals","daily_usage","sync_checkpoints","diagnostic_logs")
            if(raw.version>=3)required+=listOf("hour_usage","month_usage")
            if(raw.version>=6)required+=listOf("history_days","history_seals","retention_state")
            raw.rawQuery("SELECT name FROM sqlite_master WHERE type='table'",null).use{c->while(c.moveToNext())required.remove(c.getString(0))}
            require(required.isEmpty()){ "安全副本缺少历史数据表，已停止替换" }
        }
        val candidateDb=openDatabase(file.name)
        try{
            candidateDb.openHelper.readableDatabase.query("PRAGMA quick_check(1)").use{c->require(c.moveToFirst()&&c.getString(0)=="ok"){"安全副本数据库校验失败"}}
            candidateDb.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use{c->require(c.moveToFirst()&&c.getInt(0)==0)}
        }finally{candidateDb.close()}
    }
    suspend fun restoreSafetyCopy(undo:Boolean=false,snapshotTime:Long?=null):String=withContext(Dispatchers.IO){mutex.withLock{
        ensureWriteSpace()
        // Readers and writers share this mutex, so no Room operation uses a closed connection.
        invalidateReports()
        database.close()
        try{
            if(recoveryIssue!=null){recovery.recoverInterrupted();recoveryIssue=null}
            val task=currentCoroutineContext()
            if(undo)recovery.undo(check={task.ensureActive()},validate=::validateCandidate)else{
                val selected=recovery.list().firstOrNull{snapshotTime==null||it.time==snapshotTime}?:error("所选安全副本不存在，请重新选择")
                recovery.restore(selected,check={task.ensureActive()},validate=::validateCandidate)
            }
            if(missingGuard.exists()){require(missingGuard.delete()){ "无法解除数据库缺失保护" };syncFolder(databaseFile.parentFile!!)}
            names.clear()
        }finally{database=openDatabase(databaseFile.name)}
        database.withTransaction{dual.latest()?.let{saved->dual.events(listOf(DualCodec.event(saved.time,Signal.GAP,source=EvidenceSource.OWN_WINDOW,zone=ZoneId.systemDefault().id)));markReplayDirty(saved.time)}}
        "已恢复安全副本；恢复前数据库已保留，校验正常时可以撤销"
    }}
    suspend fun close()=withContext(Dispatchers.IO){mutex.withLock{database.close()}}
    var recording:Boolean
        get()=preferences.getBoolean("recording",true)
        set(value){preferences.edit().putBoolean("recording",value).apply()}
    fun permitted():Boolean {
        val ops=context.getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),context.packageName)==AppOpsManager.MODE_ALLOWED
    }
    suspend fun first():Long?=withContext(Dispatchers.IO){mutex.withLock{listOfNotNull<Long>(dao.first(),dual.first(),dualHistory.first()).minOrNull()}}
    suspend fun last():Long?=withContext(Dispatchers.IO){mutex.withLock{dual.latest()?.time?:dao.cursor()?.time}}
    suspend fun hasLegacy(window:Window):Boolean=withContext(Dispatchers.IO){mutex.withLock{dao.hasIntervals(window.start,window.end)}}
    suspend fun readLegacy(window:Window):Records=withContext(Dispatchers.IO){mutex.withLock{database.withTransaction{
        Records(dao.intervals(window.start,window.end).map{Interval(it.app,it.start,it.end)},dao.coverage(window.start,window.end).map{Coverage(it.start,it.end,it.reliable)})
    }}}
    private suspend fun readInsideTransaction(window:Window,metric:UsageMetric):Records{
        val first=dual.first();val last=dual.latest()?.time
        // Prefer the nearest indexed edge: forcing an end range for old dates scans all newer detail.
        val values=if(first!=null&&last!=null&&window.start>=first+(last-first)/2)
            dual.recentSlices(window.start,window.end,metric.name)else dual.slices(window.start,window.end,metric.name)
        val observations=DualCodec.effective(values)
        return Records(observations.filter{it.app!=null&&it.evidence in setOf(Evidence.CONFIRMED,Evidence.INFERRED)}.map{Interval(it.app!!,it.start,it.end)},observations.filter{it.evidence==Evidence.CONFIRMED&&!it.provisional}.map{Coverage(it.start,it.end,true)},observations)
    }
    suspend fun read(window:Window,metric:UsageMetric=UsageMetric.PRIMARY):Records=withContext(Dispatchers.IO){mutex.withLock{database.withTransaction{readInsideTransaction(window,metric)}}}
    private suspend fun calculateDay(window:Window,metric:UsageMetric):List<MetricDayCell>{
        // One indexed read per civil day; bucket cells are additive measures, so fragmenting adds no precision.
        val data=readInsideTransaction(window,metric)
        return DailyMetrics.cells(data.slices,metric,window)
    }
    suspend fun aggregateLegacy(window:Window,zone:ZoneId,grain:Grain,app:String?):UsageReport=cachedReport(ReportKey(window,zone.id,grain,null,app)){database.withTransaction{
        suspend fun records(w:Window)=Records(dao.intervals(w.start,w.end).map{Interval(it.app,it.start,it.end)},dao.coverage(w.start,w.end).map{Coverage(it.start,it.end,it.reliable)})
        if(grain==Grain.HOUR){val r=records(window);return@withTransaction Reports.build(r.intervals,r.coverage,window,zone,grain,app)}
        val accumulator=DailyMetrics.Accumulator(window,zone,grain,app,legacyCoverage=true)
        for(slot in Reports.slots(window,zone,Grain.DAY)){
            currentCoroutineContext().ensureActive();val r=records(slot)
            val day=Reports.build(r.intervals,r.coverage,slot,zone,Grain.DAY)
            val cells=day.apps.map{MetricDayCell(slot.start,slot.end,it.app,Evidence.INFERRED,it.millis,0)}+
                MetricDayCell(slot.start,slot.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,slot.duration-day.coveredMillis,0)+
                MetricDayCell(slot.start,slot.end,DailyMetrics.OBSERVED,Evidence.CONFIRMED,day.coveredMillis,0)
            accumulator.addDay(cells)
        }
        val result=accumulator.finish()
        result.copy(quality=null,appQuality=emptyMap(),fullyCovered=window.duration>0&&result.coveredMillis>=window.duration)
    }}
    suspend fun discardRecoveryRollback():String=withContext(Dispatchers.IO){mutex.withLock{recovery.discardRollback();"恢复前副本已删除，当前记录保留"}}
    private fun dayRows(cells:List<MetricDayCell>,metric:UsageMetric,zone:ZoneId)=cells.map{c->DailyUsage(DualCodec.hash("${c.start}|${zone.id}|$metric|${c.app}|${c.evidence}"),c.start,c.end,zone.id,metric.name,c.app,c.evidence.name,c.millis,c.provisional,3)}
    private fun hourRows(cells:List<MetricDayCell>,metric:UsageMetric,zone:ZoneId)=cells.map{c->HourUsage(DualCodec.hash("hour|${c.start}|${zone.id}|$metric|${c.app}|${c.evidence}"),c.start,c.end,zone.id,metric.name,c.app,c.evidence.name,c.millis,c.provisional,3)}
    private fun monthRows(cells:List<MetricDayCell>,metric:UsageMetric,zone:ZoneId,recordedDays:Long)=cells.map{c->MonthUsage(DualCodec.hash("month|${c.start}|${zone.id}|$metric|${c.app}|${c.evidence}"),c.start,c.end,zone.id,metric.name,c.app,c.evidence.name,c.millis,c.provisional,recordedDays,3)}
    /** Per-report cache capacity; counts and floor stay in memory until a trim forces a refresh. */
    private class CacheBudget(val cap:Int){
        var rows:Long=0L;var floor:Long?=null
        fun allow(slotStart:Long)=rows<cap||floor?.let{slotStart>=it}==true
        fun record(inserted:Int,slotStart:Long){rows+=inserted;if(floor==null||slotStart<floor!!)floor=slotStart}
    }
    private fun storageWritable()=StatFs(context.filesDir.absolutePath).availableBytes>=LocalRecovery.RESERVE
    /** Day cells from the day cache; missing days are computed from slices and written back. */
    private suspend fun dailyCells(slot:Window,metric:UsageMetric,zone:ZoneId,budget:CacheBudget):List<MetricDayCell>{
        val stored=dual.daily(slot.start,slot.end,zone.id,metric.name)
        if(stored.any{it.app==DailyMetrics.SUMMARY})return stored.map{MetricDayCell(it.dayStart,it.dayEnd,it.app,Evidence.valueOf(it.evidence),it.millis,it.provisionalMillis)}
        // Durable rows outlive both cache trimming and the 90-day detail window.
        val seal=dualHistory.seal(zone.id,slot.start,metric.name)
        if(seal!=null&&seal.dayEnd==slot.end){
            val rows=dualHistory.rows(zone.id,slot.start,metric.name)
            HistoryRetention.validate(rows,seal)
            return rows.map{
                val grade=if(seal.origin=="IMPORTED"&&it.app!=DailyMetrics.OBSERVED&&it.evidence==Evidence.CONFIRMED.name)Evidence.INFERRED else Evidence.valueOf(it.evidence)
                MetricDayCell(it.dayStart,it.dayEnd,it.app,grade,if(seal.origin=="IMPORTED"&&it.app==DailyMetrics.OBSERVED)0 else it.millis,it.provisionalMillis)
            }
        }
        require(slot.start>=(dualHistory.state()?.floor?:0)){"此历史范围仅保留原记录时区的完整每日汇总，无法还原部分日期"}
        val calculated=calculateDay(slot,metric)
        if(storageWritable()&&budget.allow(slot.start)){
            dual.daily(dayRows(calculated,metric,zone))
            budget.record(calculated.size,slot.start)
            if(budget.rows>500000){dual.trimCache(zone.id);budget.rows=dual.dailyCount();budget.floor=dual.cacheFirst(zone.id)}
        }
        return calculated
    }
    /** Hour cells from the hour cache; the current partial hour never matches exact bounds and is recomputed live. */
    private suspend fun hourlyCells(slot:Window,metric:UsageMetric,zone:ZoneId,budget:CacheBudget):List<MetricDayCell>{
        val stored=dual.hourly(slot.start,slot.end,zone.id,metric.name)
        if(stored.any{it.app==DailyMetrics.SUMMARY})return stored.map{MetricDayCell(it.hourStart,it.hourEnd,it.app,Evidence.valueOf(it.evidence),it.millis,it.provisionalMillis)}
        require(slot.start>=(dualHistory.state()?.floor?:0)){"90天以前请查看每日或每月汇总"}
        val data=readInsideTransaction(slot,metric)
        val calculated=DailyMetrics.cells(data.slices,metric,slot)
        if(storageWritable()&&budget.allow(slot.start)){
            dual.hourly(hourRows(calculated,metric,zone))
            budget.record(calculated.size,slot.start)
            if(budget.rows>200000){dual.trimHourCache(zone.id);budget.rows=dual.hourCount();budget.floor=dual.hourFirst(zone.id)}
        }
        return calculated
    }
    /** Month cells merged from day cells; per-month recorded days survive day-cache trimming. */
    private suspend fun monthlyCells(slot:Window,metric:UsageMetric,zone:ZoneId,dayBudget:CacheBudget,monthBudget:CacheBudget):Pair<List<MetricDayCell>,Long>{
        val stored=dual.monthly(slot.start,slot.end,zone.id,metric.name)
        if(stored.any{it.app==DailyMetrics.SUMMARY})return stored.map{MetricDayCell(it.monthStart,it.monthEnd,it.app,Evidence.valueOf(it.evidence),it.millis,it.provisionalMillis)} to stored.first().recordedDays
        val dayGroups=Reports.slots(slot,zone,Grain.DAY).map{day->day to dailyCells(day,metric,zone,dayBudget)}
        val cells=DailyMetrics.mergeCells(dayGroups.map{it.second},slot)
        val days=dayGroups.sumOf{(w,c)->DailyMetrics.recordedDays(w,c,zone).toLong()}
        if(storageWritable()&&monthBudget.allow(slot.start)){
            dual.monthly(monthRows(cells,metric,zone,days))
            monthBudget.record(cells.size,slot.start)
            if(monthBudget.rows>500000){dual.trimMonthCache(zone.id);monthBudget.rows=dual.monthCount();monthBudget.floor=dual.monthFirst(zone.id)}
        }
        return cells to days
    }
    suspend fun aggregate(window:Window,zone:ZoneId,grain:Grain,metric:UsageMetric,app:String?,includeConfirmed:Boolean=true):UsageReport=cachedReport(ReportKey(window,zone.id,grain,metric,app,includeConfirmed)){database.withTransaction{
        if(grain==Grain.HOUR){
            val budget=CacheBudget(200000).apply{rows=dual.hourCount();floor=dual.hourFirst(zone.id)}
            val slotCells=Reports.slots(window,zone,Grain.HOUR).map{slot->slot to hourlyCells(slot,metric,zone,budget)}
            return@withTransaction DailyMetrics.hourReport(slotCells,window,zone,app,includeConfirmed)
        }
        if(grain==Grain.MONTH){
            val dayBudget=CacheBudget(500000).apply{rows=dual.dailyCount();floor=dual.cacheFirst(zone.id)}
            val monthBudget=CacheBudget(500000).apply{rows=dual.monthCount();floor=dual.monthFirst(zone.id)}
            val monthCells=Reports.slots(window,zone,Grain.MONTH).map{slot->slot to monthlyCells(slot,metric,zone,dayBudget,monthBudget)}
            return@withTransaction DailyMetrics.monthReport(monthCells.map{(slot,cells)->slot to cells.first},window,zone,app,monthCells.sumOf{it.second.second},includeConfirmed)
        }
        val budget=CacheBudget(500000).apply{rows=dual.dailyCount();floor=dual.cacheFirst(zone.id)}
        val accumulator=DailyMetrics.Accumulator(window,zone,grain,app,includeConfirmed=includeConfirmed)
        for(slot in Reports.slots(window,zone,Grain.DAY)){
            currentCoroutineContext().ensureActive()
            accumulator.addDay(dailyCells(slot,metric,zone,budget))
        }
        accumulator.finish()
    }}
    suspend fun diagnostics()=withContext(Dispatchers.IO){mutex.withLock{dual.diagnostics()}}
    private suspend fun log(time:Long,code:String,detail:String){dual.diagnostics(listOf(DiagnosticLog(DualCodec.hash("$time|$code|$detail"),time,code,detail.take(512))))}
    /** Own-window evidence uses only public callbacks; it never observes another app's focus. */
    suspend fun ownEvent(signal:Signal,activity:String,instance:String,observedAt:Long,interactive:Boolean,isLocked:Boolean,processSession:String?=null,processStartedAt:Long=observedAt,clock:ClockSample?=null)=withContext(Dispatchers.IO){mutex.withLock{
        if(!recording||!permitted())return@withLock
        ensureWriteSpace()
        val now=observedAt;val zone=ZoneId.systemDefault().id
        if((dual.latest()?.time?:0)>System.currentTimeMillis()){log(now,"OWN_EVENT_CLOCK_QUARANTINED","Wall clock is behind saved boundary");return@withLock}
        val own=DualCodec.event(now,signal,context.packageName,activity,instance,EvidenceSource.OWN_WINDOW,zone).let{row->
            if(clock==null)row else row.copy(origin=DualCodec.encodeClock(clock))
        }
        val screen=if(interactive)Signal.SCREEN_ON else Signal.NON_INTERACTIVE
        val lock=if(isLocked)Signal.LOCK else Signal.UNLOCK
        database.withTransaction{
            if(clock!=null){
                val previous=DualCodec.decodeClock(dual.ownClock())
                // IO callbacks may arrive out of order; only chronological samples diagnose clocks.
                if(previous==null||previous.boot!=clock.boot||clock.elapsed>=previous.elapsed){
                    previous?.let{old->
                        ClockDiagnosis.inspect(old,clock)?.let{diagnosis->
                            val start=minOf(old.wall,clock.wall);val end=maxOf(old.wall,clock.wall)
                            if(end>start){
                                dual.events(listOf(DualCodec.event(start,Signal.CLOCK_UNCERTAIN,source=EvidenceSource.OWN_WINDOW,zone=zone),
                                    DualCodec.event(end,Signal.CLOCK_TRUSTED,source=EvidenceSource.OWN_WINDOW,zone=zone)))
                                markReplayDirty(start)
                            }
                            log(now,diagnosis,"Own observations do not share a continuous clock domain")
                        }
                    }
                    dual.checkpoint(SyncCheckpoint(-3,DualCodec.encodeClock(clock),0,-1,"","",0))
                }
            }
            if(processSession!=null){
                val session=DualCodec.event(minOf(processStartedAt,now),Signal.OWN_SESSION_START,context.packageName,instance=processSession,source=EvidenceSource.OWN_WINDOW,zone=zone)
                if(dual.events(listOf(session)).single()!=-1L)markReplayDirty(session.time)
            }
            dual.events(listOf(own,DualCodec.event(now,screen,source=EvidenceSource.OWN_WINDOW,zone=zone),DualCodec.event(now,lock,source=EvidenceSource.OWN_WINDOW,zone=zone)));markReplayDirty(now)
        }
    }}
    fun label(pkg:String):String=names.getOrPut(pkg){
        runCatching{context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg,0)).toString()}.getOrDefault(pkg.substringAfterLast('.'))
    }
    suspend fun loadNames()=withContext(Dispatchers.IO){mutex.withLock{dao.apps().forEach{names[it.app]=it.label}}}
    fun cachedLabel(pkg:String)=names[pkg]
    suspend fun warmLabel(pkg:String)=withContext(Dispatchers.IO){label(pkg)}
    private fun platformEvents(from:Long,until:Long,zone:String):Sequence<StoredEvent>{
        testQuery?.let{return it(from,until,zone)}
        return sequence{
            val stream=context.getSystemService(UsageStatsManager::class.java).queryEvents(from,until)
                ?:throw SyncUnavailable("系统未提供事件，请解锁后重试")
            val event=UsageEvents.Event()
            while(stream.hasNextEvent()){
                stream.getNextEvent(event)
                    val signal=when(event.eventType){
                        UsageEvents.Event.ACTIVITY_RESUMED->Signal.RESUME
                        UsageEvents.Event.ACTIVITY_PAUSED->Signal.PAUSE
                        UsageEvents.Event.ACTIVITY_STOPPED->Signal.STOP
                        UsageEvents.Event.SCREEN_NON_INTERACTIVE->Signal.NON_INTERACTIVE
                        UsageEvents.Event.SCREEN_INTERACTIVE->Signal.SCREEN_ON
                        UsageEvents.Event.KEYGUARD_SHOWN->Signal.LOCK
                        UsageEvents.Event.KEYGUARD_HIDDEN->Signal.UNLOCK
                        UsageEvents.Event.DEVICE_SHUTDOWN->Signal.SHUTDOWN
                        UsageEvents.Event.DEVICE_STARTUP->Signal.STARTUP
                        else->Signal.OTHER
                    }
                    if(event.timeStamp>=from&&event.timeStamp<until){
                        val row=DualCodec.event(event.timeStamp,signal,event.packageName?:"",event.className?:"",zone=zone)
                        yield(row.copy(id=DualCodec.hash("${row.id}|${event.eventType}"),rawType=event.eventType))
                    }
            }
        }
    }
    /** Phase A persists raw events and the pending replay boundary; phase B recomputes derived rows and can be retried from local evidence alone. */
    suspend fun collect():String=withContext(Dispatchers.IO){
        val requestedAt=SystemClock.elapsedRealtime()
        mutex.withLock{
        val acquiredAt=SystemClock.elapsedRealtime()
        if(recording&&dual.evidenceRevision()!="strict-evidence-0311"){
            if(dual.latest()!=null&&dual.firstEvent()!=null)rebuildHistoryInsideLock()
            else database.withTransaction{saveEvidenceRevision()}
        }
        if(!permitted()){
            preferences.edit().putBoolean("interrupted",true).apply()
            log(System.currentTimeMillis(),"PERMISSION_DENIED","Usage access unavailable; continuity not assumed")
            // Pending local evidence does not need platform access; finish it even without permission.
            processPendingInsideLock()
            return@withLock "请先授予使用情况访问权限"
        }
        if(!recording)return@withLock "自动记录已暂停"
        if(!context.getSystemService(UserManager::class.java).isUserUnlocked)throw SyncUnavailable("设备解锁后再补记")
        ensureWriteSpace()
        val sample=testClock?.invoke()?:ClockSampling.capture(System::currentTimeMillis,SystemClock::elapsedRealtime,Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1).toString())
        val now=sample.wall;val elapsed=sample.elapsed;val boot=sample.boot
        val zone=ZoneId.systemDefault();val latest=dual.latest();val day=86_400_000L
        val savedQuery=DualCodec.decodeProgress(dual.replayMetadata()).queried
        val clockReference=savedQuery?.let{ClockSample(it.end,it.elapsed,it.boot,it.uncertainty)}?:DualCodec.decodeClock(dual.collectionClock())?:latest?.let{ClockSample(it.wall,it.elapsed,it.boot)}
        val clock=clockReference?.let{ClockDiagnosis.inspect(it,sample)}
        if(now<=(latest?.time?:-1)||now<(savedQuery?.end?:-1)){
            log(now,"WALL_CLOCK_BACKWARD","Sync quarantined, saved history unchanged")
            return@withLock "设备时间早于上次记录；本次未写入使用时长"
        }
        val interrupted=preferences.getBoolean("interrupted",false)
        val queryStart=maxOf(0,if(latest==null)now-7*day else maxOf(latest.time-2*day,now-7*day))
        val homes=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0).map{it.activityInfo.packageName}.toSet()+"com.android.systemui"
        var queryMs=0L
        val timings=SyncTimings()
        invalidateReports()
        var rawCount=0L;var hasLifecycle=false;var earliestNew:Long?=null
        val observedApps=linkedSetOf<String>()
        try{
        // Phase A: raw evidence, continuity markers and the durable replay intent commit first.
        database.withTransaction {
            val queryStarted=SystemClock.elapsedRealtime()
            var from=queryStart
            // Query the platform in bounded windows; never retain a week-sized parcel/list.
            while(from<now){
                currentCoroutineContext().ensureActive();ensureWriteSpace()
                val until=minOf(now,from+6*3_600_000L)
                val batch=ArrayList<StoredEvent>(1000)
                suspend fun flush(){
                    if(batch.isEmpty())return
                    val inserted=dual.events(batch)
                    inserted.forEachIndexed{index,id->
                        val row=batch[index]
                        if(id!=-1L&&row.signal!=Signal.OTHER.name)earliestNew=minOf(earliestNew?:row.time,row.time)
                    }
                    batch.clear();currentCoroutineContext().ensureActive();ensureWriteSpace()
                }
                for(row in platformEvents(from,until,zone.id)){
                    batch+=row
                    if(row.app.isNotBlank())observedApps+=row.app
                    rawCount++;if(row.signal!=Signal.OTHER.name)hasLifecycle=true
                    if(batch.size>=1000)flush()
                }
                flush()
                from=until
            }
            queryMs=SystemClock.elapsedRealtime()-queryStarted
            if(interrupted||clock!=null||latest!=null&&queryStart>latest.time){
                val boundary=latest?.time?:savedQuery?.end?:queryStart
                val markers=if(clock!=null)listOf(
                    DualCodec.event(boundary,Signal.CLOCK_UNCERTAIN,source=EvidenceSource.OWN_WINDOW,zone=zone.id),
                    DualCodec.event(now,Signal.CLOCK_TRUSTED,source=EvidenceSource.OWN_WINDOW,zone=zone.id))
                else listOf(DualCodec.event(boundary,Signal.GAP,source=EvidenceSource.OWN_WINDOW,zone=zone.id))
                dual.events(markers)
                earliestNew=minOf(earliestNew?:boundary,boundary)
                log(now,clock?:if(interrupted)"PERMISSION_INTERRUPTION"else"RETENTION_GAP","Continuity reset; no synthetic durations")
            }
            if(latest!=null&&now-latest.time>2*day)log(now,"SYNC_DELAY","Delay ${now-latest.time}ms; retention completeness cannot be proven")
            if(!hasLifecycle)log(now,"EMPTY_SYSTEM_QUERY","Empty response is not proof of zero use")
            val progress=DualCodec.decodeProgress(dual.replayMetadata())
            val replayStart=progress.start(latest?.time,queryStart,earliestNew)
            saveProgress(progress.markDirty(replayStart).copy(queried=QueryBoundary(queryStart,now,elapsed,boot,zone.id,sample.uncertainty)))
        }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){
            // Committed pending boundaries survive this rollback; finish them locally without platform access.
            try{processPendingInsideLock()}catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
            catch(local:Exception){throw SyncUnavailable("系统查询失败，本地待算仍保留：${local.message}")}
            throw SyncUnavailable("系统事件查询失败：${e.message?:e.javaClass.simpleName}")
        }
        collectPhaseListener?.invoke("raw-saved")
        try{
            // Phase B: replay saved evidence and rebuild layered summaries; failure leaves raw events saved for local retry.
            database.withTransaction {
            val progress=DualCodec.decodeProgress(dual.replayMetadata())
            val requestedStart=progress.start(latest?.time,queryStart,earliestNew)
            dao.apps(observedApps.map{SavedApp(it,label(it))})
            recomputeInsideTransaction(now,elapsed,boot,zone,requestedStart,queryStart,homes,timings,sample.uncertainty)
            log(now,"SYNC_COMMITTED","query=$queryStart..$now; raw=$rawCount; replay=${timings.replayStart}; incremental=${progress.initialized}; algorithm=${DualUsageEngine.VERSION}; zone=${zone.id}")
        }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){
            log(now,"REPLAY_PENDING","Raw events saved; statistics retry pending: ${e.message?.take(200)?:e.javaClass.simpleName}")
            throw SyncUnavailable("原始事件已保存，统计将在下次补记时继续：${e.message?:e.javaClass.simpleName}")
        }
        collectPhaseListener?.invoke("committed")
        preferences.edit().putBoolean("interrupted",false).apply()
        val safetyStarted=SystemClock.elapsedRealtime()
        maintainAndSnapshot()
        if(dev.usagetime.app.BuildConfig.DEBUG)android.util.Log.i("UsageTimeSync","complete total_ms=${SystemClock.elapsedRealtime()-requestedAt} wait_ms=${acquiredAt-requestedAt} query_ms=$queryMs replay_ms=${timings.replayMs} summary_ms=${timings.summaryMs} safety_ms=${SystemClock.elapsedRealtime()-safetyStarted} replay_span_ms=${timings.replaySpan}")
        "已保存最新记录"
    }}
    private class SyncTimings{var replayMs=0L;var summaryMs=0L;var replaySpan=0L;var replayStart=0L}
    /** Replay saved evidence from requestedStart and rebuild hour/day/month summaries inside the caller's transaction. */
    private suspend fun recomputeInsideTransaction(now:Long,elapsed:Long,boot:String,zone:ZoneId,requestedStart:Long,cutCandidate:Long,homes:Set<String>,timings:SyncTimings,clockUncertainty:Long=0){
        val previousEnd=dual.latest()?.time
        require(previousEnd==null||now>=previousEnd){"重放终点不能早于已处理边界"}
        val anchor=dual.checkpoint(requestedStart)
        val start=anchor?.time?:requestedStart
        timings.replayStart=start;timings.replaySpan=now-start
        val replayStarted=SystemClock.elapsedRealtime()
        val initial=anchor?.let{DualCodec.decode(it.state)}?:EngineState()
        val cut=cutCandidate.coerceIn(start,now)
        // Forward-only collection appends to the existing checkpoint; only a rewind replaces history.
        if(previousEnd!=null&&start<previousEnd){
            dual.deleteRecent(start);dual.deleteCheckpointsAfter(start)
        }
        // A changed replay domain invalidates both caches and every durable day it can touch.
        HistoryRetention.invalidate(database,Dates.midnight(Dates.date(start,zone),zone))
        collectPhaseListener?.invoke("stats")
        var at=start
        var state=initial
        // Bound live event/interval objects to one civil day; keep the commit atomic.
        while(at<now){
            currentCoroutineContext().ensureActive();ensureWriteSpace()
            val midnight=Dates.midnight(Dates.date(at,zone).plusDays(1),zone)
            val next=minOf(now,midnight,at+6*3_600_000L,if(at<cut)cut else now)
            val result=DualUsageEngine.parse(dual.events(at,next).map{it.event()},Window(at,next),state,homes)
            result.slices.chunked(1000).forEach{batch->dual.slices(batch.map{DualCodec.stored(it)})}
            applyClosures(result.closures)
            state=result.checkpoint
            dual.checkpoint(SyncCheckpoint(next,DualCodec.encode(state),next,if(next==now)elapsed else -1,boot,zone.id))
            collectPhaseListener?.invoke("stats-written")
            dual.diagnostics(result.diagnostics.map{DiagnosticLog(DualCodec.hash("${it.time}|${it.code}|${it.detail}"),it.time,it.code,it.detail.take(512))})
            at=next
        }
        timings.replayMs=SystemClock.elapsedRealtime()-replayStarted
        val summariesStarted=SystemClock.elapsedRealtime()
        val dayStart=Dates.midnight(Dates.date(start,zone),zone)
        val monthStart=Dates.midnight(Dates.date(start,zone).withDayOfMonth(1),zone)
        dual.deleteHoursFrom(start);dual.deleteMonthsFrom(monthStart);dual.deleteDaysFrom(dayStart)
        // One slice read per day and metric; hour cells merge into the day row they belong to.
        val hourCacheFloor=now-90*86_400_000L
        val dayBudget=CacheBudget(500000).apply{rows=dual.dailyCount();floor=dual.cacheFirst(zone.id)}
        val monthBudget=CacheBudget(500000).apply{rows=dual.monthCount();floor=dual.monthFirst(zone.id)}
        for(daySlot in Reports.slots(Window(dayStart,now),zone,Grain.DAY)){
            for(metric in UsageMetric.entries){
                currentCoroutineContext().ensureActive()
                val data=readInsideTransaction(daySlot,metric)
                val hourCells=Reports.slots(daySlot,zone,Grain.HOUR).filter{it.end>start&&it.end>hourCacheFloor}.map{h->h to DailyMetrics.cells(data.slices,metric,h)}
                // Partial tail hours keep their actual bounds; very old spans stay slice-computed to bound write amplification.
                dual.hourly(hourCells.flatMap{(_,c)->hourRows(c,metric,zone)})
                val cells=DailyMetrics.cells(data.slices,metric,daySlot)
                val rows=dayRows(cells,metric,zone)
                dual.daily(rows);dayBudget.record(rows.size,daySlot.start)
                // The durable day summary and the detail replay commit atomically.
                if(daySlot.end==Dates.midnight(Dates.date(daySlot.start,zone).plusDays(1),zone))
                    HistoryRetention.save(database,HistoryRetention.rows(cells,metric,zone))
            }
        }
        for(monthSlot in Reports.slots(Window(monthStart,now),zone,Grain.MONTH)){
            for(metric in UsageMetric.entries){
                currentCoroutineContext().ensureActive()
                monthlyCells(monthSlot,metric,zone,dayBudget,monthBudget)
            }
        }
        timings.summaryMs=SystemClock.elapsedRealtime()-summariesStarted
        finishReplay(now)
        dual.checkpoint(SyncCheckpoint(-4,DualCodec.encodeClock(ClockSample(now,elapsed,boot,clockUncertainty)),0,-1,"","",0))
    }
    /** Recompute evidence already saved locally; never contacts the platform, so permission loss cannot stall it. */
    private suspend fun processPendingInsideLock():String{
        if(!recording)return "自动记录已暂停"
        val progress=DualCodec.decodeProgress(dual.replayMetadata())
        if(progress.dirtyFrom==null)return "统计已是最新"
        ensureWriteSpace()
        val sample=testClock?.invoke()?:ClockSampling.capture(System::currentTimeMillis,SystemClock::elapsedRealtime,Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1).toString())
        val latest=dual.latest()
        val savedBoundary=latest?.let{QueryBoundary(it.time,it.time,it.elapsed,it.boot,it.zone)}
        val plan=progress.localReplay(savedBoundary,sample.wall)?:return "本地事件等待有效的采集边界"
        val boundary=plan.boundary;val zone=ZoneId.of(boundary.zone)
        // A reboot or time jump at retry time cannot extend this historical, already acquired range.
        val diagnosis=ClockDiagnosis.inspect(ClockSample(boundary.end,boundary.elapsed,boundary.boot,boundary.uncertainty),sample)
        if(diagnosis!=null)log(sample.wall,diagnosis,"Local replay limited to saved acquisition end=${boundary.end}")
        val homes=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0).map{it.activityInfo.packageName}.toSet()+"com.android.systemui"
        invalidateReports()
        val timings=SyncTimings()
        try{
            database.withTransaction{recomputeInsideTransaction(boundary.end,boundary.elapsed,boundary.boot,zone,plan.start,boundary.start,homes,timings,boundary.uncertainty)}
            log(sample.wall,"LOCAL_REPLAY_COMMITTED","local-only replay=${timings.replayStart}..${boundary.end}")
            return "已根据本地事件更新统计"
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){
            log(sample.wall,"REPLAY_PENDING","Raw events saved; statistics retry pending: ${e.message?.take(200)?:e.javaClass.simpleName}")
            throw SyncUnavailable("原始事件已保存，统计将在下次补记时继续：${e.message?:e.javaClass.simpleName}")
        }
    }
    private suspend fun applyClosures(values:List<ClosedEvidence>){
        if(values.isEmpty())return
        val pending=PendingClosures(values)
        // One bounded cursor scan replaces a MIN(refs LIKE ...) query for every closed session.
        database.openHelper.readableDatabase.query("SELECT metric,app,start,refs,reason FROM usage_intervals WHERE algorithm=2 AND provisional=1 AND app IS NOT NULL").use{cursor->
            var count=0
            while(cursor.moveToNext()){
                if(++count%256==0)currentCoroutineContext().ensureActive()
                pending.add(cursor.getString(0),cursor.getString(1),cursor.getLong(2),cursor.getString(3),cursor.getString(4))
            }
        }
        var invalidate:Long?=null
        pending.matches().forEachIndexed{index,(c,earliest)->
            if(index%64==0){currentCoroutineContext().ensureActive();ensureWriteSpace()}
            val prefix=if(c.focus)"EXPLICIT%"else"%"
            dual.finalizeEvidence(c.metric.name,c.app,c.origin,c.at,c.evidence.name,prefix,","+c.endRef)
            invalidate=minOf(invalidate?:earliest,earliest)
        }
        invalidate?.let{
            dual.deleteDaysFrom(it);dual.deleteHoursFrom(it);dual.deleteMonthsFrom(it)
            HistoryRetention.invalidate(database,it)
        }
    }
    /** Bounded-memory replay of saved evidence; never asks the platform to recover years-old events. */
    private suspend fun saveEvidenceRevision(){
        dual.checkpoint(SyncCheckpoint(-2,"strict-evidence-0311",0,-1,"","",0))
    }
    suspend fun rebuildHistory():String=withContext(Dispatchers.IO){mutex.withLock{rebuildHistoryInsideLock()}}
    private suspend fun rebuildHistoryInsideLock():String{
        ensureWriteSpace();invalidateReports();snapshotInsideLock(true)
        return database.withTransaction{
        val floor=dualHistory.state()?.floor
        val first=floor?:dual.firstEvent()?:return@withTransaction "尚无已保存原始事件"
        val last=dual.latest()?:return@withTransaction "尚无同步检查点"
        val seed=if(floor!=null)dual.checkpoint(floor)?.takeIf{it.time==floor}?.let{DualCodec.decode(it.state)}?:error("缺少压缩边界检查点")else EngineState()
        val end=last.time;var at=first;var state=seed;val step=6*3_600_000L
        val homes=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0).map{it.activityInfo.packageName}.toSet()+"com.android.systemui"
        HistoryRetention.invalidate(database,first)
        dual.deleteRecent(first);dual.deleteCheckpointsAfter(first);dual.deleteDaysFrom(first);dual.deleteHoursFrom(first);dual.deleteMonthsFrom(first)
        dual.checkpoint(SyncCheckpoint(first,DualCodec.encode(seed),first,-1,last.boot,last.zone))
        while(at<end){
            currentCoroutineContext().ensureActive();ensureWriteSpace()
            val next=minOf(end,at+step);val events=dual.events(at,next).map{it.event()}
            val result=DualUsageEngine.parse(events,Window(at,next),state,homes)
            dual.slices(result.slices.map{DualCodec.stored(it)});applyClosures(result.closures)
            state=result.checkpoint
            dual.checkpoint(SyncCheckpoint(next,DualCodec.encode(state),next,if(next==end)last.elapsed else -1,last.boot,last.zone))
            at=next
        }
        finishReplay(end)
        saveEvidenceRevision()
        log(System.currentTimeMillis(),"HISTORY_REBUILT","Saved event replay $first..$end; v1/imported history retained; algorithm=2")
        "已根据保存的原始事件重算历史"
    }}
    suspend fun writeCompressedBackup(output:java.io.OutputStream)=withContext(Dispatchers.IO){
        java.util.zip.GZIPOutputStream(output,65536).use{writeBackup(it)}
    }
    suspend fun exportJson():String {val out=java.io.ByteArrayOutputStream();writeBackup(out);return out.toString("UTF-8")}
    /** Streaming SQLite snapshot. Database/OS credentials and the active checkpoint are not restored. */
    suspend fun writeBackup(output:java.io.OutputStream)=withContext(Dispatchers.IO){mutex.withLock{database.withTransaction{
        val writer=android.util.JsonWriter(java.io.OutputStreamWriter(output,Charsets.UTF_8))
        writer.beginObject().name("format").value("usagetime-backup").name("version").value(3L).name("exportedAt").value(System.currentTimeMillis())
        val tables=listOf("intervals","coverage","apps","usage_events","usage_intervals","history_days","history_seals","retention_state","sync_checkpoints","diagnostic_logs")
        for(table in tables){
            writer.name(table).beginArray()
            var exported=0L
            database.openHelper.readableDatabase.query("SELECT * FROM $table").use{cursor->while(cursor.moveToNext()){
                if(++exported%1000==0L)currentCoroutineContext().ensureActive()
                writer.beginObject();for(i in 0 until cursor.columnCount){val name=cursor.getColumnName(i);writer.name(name)
                    when(cursor.getType(i)){
                        android.database.Cursor.FIELD_TYPE_NULL->writer.nullValue()
                        android.database.Cursor.FIELD_TYPE_INTEGER->if(name in setOf("reliable","provisional","blocked","known"))writer.value(cursor.getLong(i)!=0L)else writer.value(cursor.getLong(i))
                        else->writer.value(cursor.getString(i))
                    }
                };writer.endObject()
            }}
            writer.endArray()
        }
        writer.endObject();writer.flush()
    }}}
    suspend fun importBackup(input:java.io.InputStream):Long=withContext(Dispatchers.IO){mutex.withLock{
        ensureWriteSpace();invalidateReports();snapshotInsideLock(true)
        val importedTotal=database.withTransaction{
        val buffered=java.io.BufferedInputStream(input,65536);buffered.mark(2)
        val first=buffered.read();val second=buffered.read();buffered.reset()
        val decoded=if(first==0x1f&&second==0x8b)java.util.zip.GZIPInputStream(buffered,65536)else buffered
        val reader=android.util.JsonReader(java.io.InputStreamReader(decoded,Charsets.UTF_8));reader.isLenient=false
        var format="";var version=0;var imported=0L;var recordCount=0L;var importedDetailStart:Long?=null
        val raw=database.openHelper.writableDatabase
        raw.execSQL("CREATE TEMP TABLE IF NOT EXISTS incoming_days AS SELECT * FROM history_days WHERE 0")
        raw.execSQL("CREATE TEMP TABLE IF NOT EXISTS incoming_seals AS SELECT * FROM history_seals WHERE 0")
        raw.execSQL("DELETE FROM incoming_days");raw.execSQL("DELETE FROM incoming_seals")
        raw.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS temp.incoming_day_key ON incoming_days(zone,dayStart,metric,appKey,evidence)")
        raw.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS temp.incoming_seal_key ON incoming_seals(zone,dayStart,metric)")
        val eventBatch=ArrayList<StoredEvent>();val sliceBatch=ArrayList<StoredSlice>();val legacyBatch=ArrayList<SavedInterval>();var batchBytes=0L
        suspend fun flushBatches(){
            if(eventBatch.isNotEmpty()){dual.events(eventBatch);eventBatch.clear()}
            if(sliceBatch.isNotEmpty()){dual.slices(sliceBatch);sliceBatch.clear()}
            if(legacyBatch.isNotEmpty()){dao.intervals(legacyBatch);legacyBatch.clear()}
            batchBytes=0
        }
        val latestAllowed=System.currentTimeMillis()+86_400_000L
        fun valid(start:Long,end:Long){require(start>=0&&end>start&&end<=latestAllowed){"备份区间无效"}}
        fun objectRow(table:String):JSONObject{
            val obj=JSONObject();reader.beginObject();var fields=0
            while(reader.hasNext()){
                require(++fields<32){"备份字段过多"};val name=reader.nextName();require(name.length<=80)
                when(reader.peek()){
                    android.util.JsonToken.NULL->{reader.nextNull();obj.put(name,JSONObject.NULL)}
                    android.util.JsonToken.BOOLEAN->obj.put(name,reader.nextBoolean())
                    android.util.JsonToken.NUMBER->obj.put(name,reader.nextLong())
                    android.util.JsonToken.STRING->{val value=reader.nextString();obj.put(name,DualCodec.backupString(table,name,value))}
                    else->error("备份包含不支持的字段")
                }
            };reader.endObject();return obj
        }
        reader.beginObject()
        while(reader.hasNext())when(val key=reader.nextName()){
            "format"->format=reader.nextString()
            "version"->version=reader.nextInt()
            "exportedAt"->{reader.skipValue()}
            "intervals","coverage","apps","usage_events","usage_intervals","history_days","history_seals","retention_state","sync_checkpoints","diagnostic_logs"->{
                reader.beginArray();while(reader.hasNext()){
                    recordCount++
                    if(recordCount%1000==0L){currentCoroutineContext().ensureActive();ensureWriteSpace()}
                    val row=objectRow(key)
                    when(key){
                        "intervals"->{val pkg=row.getString("app");require(pkg.isNotBlank()&&pkg.length<=255);val a=row.getLong("start");val b=row.getLong("end");valid(a,b);legacyBatch+=SavedInterval(pkg,a,b);batchBytes+=pkg.length*2L+64;imported++}
                        "coverage"->{val a=row.getLong("start");val b=row.getLong("end");valid(a,b);val known=row.getBoolean("reliable");dao.coverage(listOf(SavedCoverage(a,b,known)));if(known)dao.upgradeCoverage(a,b)}
                        "apps"->{val pkg=row.getString("app");require(pkg.isNotBlank()&&pkg.length<=255);dao.apps(listOf(SavedApp(pkg,row.getString("label").take(120))))}
                        "usage_events"->{
                            val time=row.getLong("time");require(time>=0&&time<=latestAllowed);val signal=Signal.valueOf(row.getString("signal"))
                            // Retain raw imported evidence for audit, never splice it into this device's active state.
                            eventBatch+=StoredEvent("archive:"+row.getString("id").removePrefix("archive:"),time,signal.name,row.getString("app"),row.getString("activity"),if(row.isNull("instance"))null else row.getString("instance"),EvidenceSource.LEGACY_IMPORT.name,row.getString("zone"),DualCodec.importOrigin(row.getString("source"),row.optString("origin","LOCAL")),if(row.isNull("rawType"))null else row.getInt("rawType"));batchBytes+=row.toString().length*2L
                        }
                        "usage_intervals"->{
                            val a=row.getLong("start");val b=row.getLong("end");valid(a,b);val metric=UsageMetric.valueOf(row.getString("metric"));val original=Evidence.valueOf(row.getString("evidence"))
                            importedDetailStart=minOf(importedDetailStart?:a,a)
                            val pkg=if(row.isNull("app"))null else row.getString("app");require(pkg==null||pkg.length<=255)
                            val grade=if(original==Evidence.CONFIRMED||original==Evidence.INFERRED)Evidence.INFERRED else original
                            val id="archive:"+row.getString("id").removePrefix("archive:")
                            sliceBatch+=StoredSlice(id,metric.name,pkg,a,b,grade.name,DualCodec.importReason(row.getString("reason"),original.name,row.getString("sources")),EvidenceSource.LEGACY_IMPORT.name,row.optString("refs","").split(',').filter{it.isNotBlank()}.joinToString(","){"archive:"+it.removePrefix("archive:")},row.optBoolean("provisional",false),0);batchBytes+=row.toString().length*2L;imported++
                        }
                        "history_days"->{
                            val a=row.getLong("dayStart");val b=row.getLong("dayEnd");valid(a,b)
                            val app=if(row.isNull("app"))null else row.getString("app");require(app==null||app.length<=255)
                            require(row.getString("appKey")==HistoryRetention.appKey(app))
                            raw.execSQL("INSERT INTO incoming_days VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(
                                row.getString("zone"),a,row.getString("metric"),row.getString("appKey"),row.getString("evidence"),b,
                                app,row.getLong("millis"),row.getLong("provisionalMillis"),row.getInt("algorithm")))
                            imported++
                        }
                        "history_seals"->{
                            raw.execSQL("INSERT INTO incoming_seals VALUES(?,?,?,?,?,?,?)",arrayOf(
                                row.getString("zone"),row.getLong("dayStart"),row.getString("metric"),row.getLong("dayEnd"),
                                row.getLong("rows"),row.getString("digest"),"IMPORTED"))
                        }
                        "retention_state"->Unit // Imported metadata never authorizes local deletion.
                        "diagnostic_logs"->Unit // Incoming diagnostics remain in the backup; do not impersonate local audit entries.
                        "sync_checkpoints"->Unit // Checkpoints are device-local and must never be activated from a backup.
                    }
                    if(eventBatch.size+sliceBatch.size+legacyBatch.size>=1000||batchBytes>=1048576){flushBatches();ensureWriteSpace()}
                };flushBatches();reader.endArray()
            }
            else->reader.skipValue()
        }
        reader.endObject();require(format=="usagetime-backup"&&version in 1..3){"不是受支持的用时记备份"}
        require(reader.peek()==android.util.JsonToken.END_DOCUMENT){"备份存在额外内容"}
        importedDetailStart?.let{
            // Keep immutable compacted days; reject an ambiguous merge rather than replace a total with a fragment.
            require(it>=(dualHistory.state()?.floor?:0)){"备份明细涉及已压缩历史，请在独立安装中恢复"}
            HistoryRetention.invalidate(database,it)
        }
        HistoryRetention.mergeImport(database)
        raw.execSQL("DELETE FROM incoming_days");raw.execSQL("DELETE FROM incoming_seals")
        dual.deleteDaysFrom(0);dual.deleteHoursFrom(0);dual.deleteMonthsFrom(0)
        log(System.currentTimeMillis(),"BACKUP_IMPORTED","$imported records; imported evidence cannot become locally confirmed; live checkpoints retained")
        imported
        }
        maintainAndSnapshot(true)
        importedTotal
    }}
    suspend fun importJson(text:String):Long=importBackup(text.byteInputStream())
}

class SyncUnavailable(message:String):Exception(message)
