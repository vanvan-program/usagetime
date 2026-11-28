package dev.usagetime.app.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
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
    @Query("SELECT EXISTS(SELECT 1 FROM intervals LIMIT 1)")
    suspend fun hasAnyIntervals():Boolean
    @Query("SELECT * FROM coverage WHERE `end` > :start AND start < :end ORDER BY start")
    suspend fun coverage(start:Long,end:Long):List<SavedCoverage>
    @Query("SELECT MIN(value) FROM (SELECT MIN(start) AS value FROM coverage UNION ALL SELECT MIN(start) AS value FROM intervals)") suspend fun first():Long?
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
    private val databaseFile by lazy{context.getDatabasePath(databaseName)}
    private val missingGuard by lazy{File(databaseFile.path+".missing-guard")}
    private var recoveryIssue:String?=null
    private fun syncFolder(dir:File){
        val fd=Os.open(dir.absolutePath,OsConstants.O_RDONLY,0)
        try{Os.fsync(fd)}finally{Os.close(fd)}
    }
    private val recovery by lazy{
        LocalRecovery(File(context.noBackupFilesDir,"recovery-$databaseName"),databaseFile,
            available={StatFs(context.filesDir.absolutePath).availableBytes},syncDirectory=::syncFolder)
    }
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
    private var recoveryChecked=false
    // Recovery and Room initialization are reached only from storage operations under the IO mutex.
    private fun checkRecovery(){
        if(recoveryChecked)return
        // Keep settings/recovery UI accessible even if startup rollback cannot finish.
        try{recovery.recoverInterrupted()}catch(e:Exception){recoveryIssue=e.message?:e.javaClass.simpleName}
        if(recoveryIssue==null&&(!databaseFile.exists()||databaseFile.length()<512)&&recovery.list().isNotEmpty()){
            databaseFile.parentFile!!.mkdirs()
            java.io.FileOutputStream(missingGuard).use{it.write("restore-required".toByteArray());it.fd.sync()};syncFolder(databaseFile.parentFile!!)
        }
        recoveryChecked=true
    }
    private var openedDatabase:UsageDatabase?=null
    private var database:UsageDatabase
        get(){
            openedDatabase?.let{return it}
            checkRecovery()
            return openDatabase(databaseFile.name).also{openedDatabase=it}
        }
        set(value){openedDatabase=value}
    private val dao get()=database.usage()
    private val dual get()=database.dual()
    private val dualHistory get()=database.history()
    private val mutex=Mutex()
    private data class ReportKey(val window:Window,val zone:String,val grain:Grain,val metric:UsageMetric?,val app:String?,val includeConfirmed:Boolean=true)
    private val reportCache=ReportCache<ReportKey>()
    data class ReportCacheStats(val hits:Long,val misses:Long,val evictions:Long,val invalidations:Long,val entries:Int,val estimatedBytes:Long)
    suspend fun reportCacheStats()=withContext(Dispatchers.IO){mutex.withLock{
        ReportCacheStats(reportCache.hits,reportCache.misses,reportCache.evictions,reportCache.invalidations,reportCache.size,reportCache.weight)
    }}
    private val revision=kotlinx.coroutines.flow.MutableStateFlow(0L)
    val reportRevision:kotlinx.coroutines.flow.StateFlow<Long> get()=revision
    // Clear memory cache under mutex before writes; bump UI revision only after the write commits.
    private fun clearReportCache(){reportCache.clear()}
    private fun publishReportRevision(){reportCache.clear();revision.value=revision.value+1}
    /** Drop only reports whose window can include data at or after [from]; untouched history stays cached. */
    private fun invalidateReportsFrom(from:Long){
        reportCache.invalidateWhere{it.window.end>from}
        revision.value=revision.value+1
    }
    private fun continuityHealthy()=preferences.getBoolean("sync-healthy",false)
    private fun setContinuityHealthy(value:Boolean){preferences.edit{putBoolean("sync-healthy",value)}}
    private fun lastDeepSweep()=preferences.getLong("last-deep-sweep",0L)
    private fun markDeepSweep(at:Long){preferences.edit{putLong("last-deep-sweep",at)}}
    private suspend fun cachedReport(key:ReportKey,build:suspend ()->UsageReport):UsageReport=
        withContext(Dispatchers.IO){mutex.withLock{
            reportCache[key]?:build().also{currentCoroutineContext().ensureActive();reportCache.put(key,it)}
        }}
    private val preferences=context.getSharedPreferences(if(databaseName=="usage-history.db")"usage-settings"else"usage-settings-$databaseName",Context.MODE_PRIVATE)
    private val categoryPreferences=CategoryPreferences(context,if(databaseName=="usage-history.db")"usage-categories"else"usage-categories-$databaseName")
    private val watermark by lazy{SyncWatermarkTracker.decode(preferences.getString("sync-watermark",null))}
    @android.annotation.SuppressLint("ApplySharedPref") // Persisted on IO immediately after DB commit to avoid DB/watermark crash skew.
    private fun persistWatermark(){preferences.edit(commit=true){putString("sync-watermark",watermark.encode())}}
    internal fun syncWatermark():SyncWatermarkSnapshot=watermark.snapshot()
    internal fun dirtyFrom(sinceSeq:Long):Long?=watermark.dirtyFrom(sinceSeq)
    private val names=ConcurrentHashMap<String,String>()
    private val persistedApps=ConcurrentHashMap<String,String>()
    private var namesLoaded=false
    private var hasKnownSnapshot=false
    private var knownNoLegacy:Boolean?=null
    private var cachedHomes:Set<String>?=null
    private var cachedHomesAt:Long=-1L
    private fun launcherPackages():Set<String>{
        val nowElapsed=SystemClock.elapsedRealtime()
        val cached=cachedHomes
        if(cached!=null&&nowElapsed-cachedHomesAt in 0 until 5*60_000L)return cached
        val resolved=context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0
        ).mapTo(linkedSetOf()){it.activityInfo.packageName}.apply{add("com.android.systemui")}
        cachedHomes=resolved
        cachedHomesAt=nowElapsed
        return resolved
    }
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
    suspend fun storageInfo():StorageInfo=withContext(Dispatchers.IO){mutex.withLock{
        checkRecovery()
        val copies=recovery.list()
        val dbBytes=databaseFile.parentFile!!.listFiles().orEmpty().filter{it.isFile&&it.name.startsWith(databaseFile.name)}.sumOf{it.length()}
        StorageInfo(dbBytes,copies.sumOf{it.file.length()},StatFs(context.filesDir.absolutePath).availableBytes,copies.size,copies.firstOrNull()?.time,recovery.hasRollback(),recoveryIssue?.let{"恢复尚未完成，原文件已保留：$it"}?:preferences.getString("safety-warning",null),copies.map{it.time},preferences.getLong("last-full-export",0).takeIf{it>0})
    }}
    fun noteFullExport(){preferences.edit { putLong("last-full-export",System.currentTimeMillis()) }}
    private fun ensureWriteSpace(){
        if(StatFs(context.filesDir.absolutePath).availableBytes<LocalRecovery.RESERVE){
            preferences.edit { putBoolean("interrupted",true) }
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
        if(!force&&preferences.getInt("safety-boot",-2)==boot&&now-preferences.getLong("safety-elapsed",-1) in 0 until 12*3_600_000L&&(hasKnownSnapshot||recovery.list().isNotEmpty().also{if(it)hasKnownSnapshot=true}))return null
        ensureWriteSpace()
        // A checkpoint is required; never copy a bare DB while committed data remains only in WAL.
        database.openHelper.readableDatabase.query("PRAGMA quick_check(1)").use{c->require(c.moveToFirst()&&c.getString(0)=="ok"){"数据库完整性异常，已保留旧安全副本"}}
        checkpoint();database.close()
        return try{
            require(File(databaseFile.path+"-wal").length()==0L){"数据库日志尚未合并，未创建不完整副本"}
            val task=currentCoroutineContext()
            val result=recovery.create{task.ensureActive()}
            hasKnownSnapshot=true
            preferences.edit { putInt("safety-boot",boot).putLong("safety-elapsed",now).remove("safety-warning") }
            result
        }finally{database=openDatabase(databaseFile.name)}
    }
    private suspend fun archiveBeforeCleanup(now:Long,zone:ZoneId){
        val state=dualHistory.state()
        // Never rebuild pre-floor summaries under another timezone after compaction deleted raw detail.
        if(state!=null&&state.zone!=zone.id)return
        val progress=DualCodec.decodeProgress(dual.replayMetadata())
        if(!progress.initialized||progress.dirtyFrom!=null)return
        val latest=dual.latest()?:return
        val cutoff=minOf(HistoryRetention.cutoff(now,zone),Dates.midnight(Dates.date(latest.time,zone),zone))
        val first=listOfNotNull<Long>(dualHistory.first(),dual.first(),dual.firstEvent()).minOrNull()?.let{Dates.midnight(Dates.date(it,zone),zone)}?:return
        var day=maxOf(first,state?.floor?:first)
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
    private suspend fun maintainAndSnapshot(force:Boolean=false,allowSnapshot:Boolean=true){
        HistoryMaintenanceCoordinator.run(
            prepare={
            val elapsed=SystemClock.elapsedRealtime()
            val boot=Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)
            // Sync remains immediate. Only housekeeping scans use a six-hour cadence.
            if(force||ElapsedMaintenance.due(elapsed,boot,preferences.getLong("maintenance-elapsed",-1),preferences.getInt("maintenance-boot",-2))){
                val now=System.currentTimeMillis();val zone=ZoneId.systemDefault()
                HistoryMaintenanceCoordinator.State(elapsed,boot,now,zone)
            }else null
            },
            archive={s->database.withTransaction{archiveBeforeCleanup(s.now,s.zone)}},
            compact={s->database.withTransaction{HistoryRetention.compact(database,s.now,s.zone)}},
            deferred={s,e->log(s.now,"CLEANUP_DEFERRED",e.message?:"保留明细")},
            finishMaintenance={s->
                database.withTransaction{
                    dual.trimCache(ZoneId.systemDefault().id)
                    dual.trimHourCache(ZoneId.systemDefault().id)
                    dual.trimMonthCache(ZoneId.systemDefault().id)
                    // Keep civil-midnight replay anchors, including a previously committed retention floor.
                    database.openHelper.writableDatabase.execSQL("DELETE FROM sync_checkpoints WHERE algorithm=2 AND time<? AND time NOT IN (SELECT MAX(time) FROM sync_checkpoints WHERE algorithm=2 GROUP BY time/86400000) AND time NOT IN (SELECT MIN(time) FROM sync_checkpoints WHERE algorithm=2 GROUP BY time/86400000) AND time NOT IN (SELECT floor FROM retention_state)",arrayOf(s.now-100*86400000L))
                    dual.trimDiagnostics(listOf("STATE_RESET","RETENTION_GAP","BOOT_CHANGED","WALL_CLOCK_BACKWARD","WALL_CLOCK_DISCONTINUITY","PERMISSION_INTERRUPTION","PERMISSION_DENIED","SYNC_DELAY","EMPTY_SYSTEM_QUERY","HISTORY_REBUILT","BACKUP_IMPORTED","SYNC_COMMITTED","RECOVERY_SNAPSHOT"))
                }
            },
            recordMaintenance={s->preferences.edit { putLong("maintenance-elapsed",s.elapsed).putInt("maintenance-boot",s.boot) }},
            // A snapshot checkpoints, closes and gzips the whole database under the caller's mutex.
            // Interactive collection skips it so opening the app never waits for a 50 MiB copy;
            // the six-hourly background worker still creates it on the usual 12-hour cadence.
            snapshot={if(allowSnapshot)snapshotInsideLock(force);Unit},
            warning={e->preferences.edit { putString("safety-warning","安全副本尚未更新：${e.message}") }}
        )
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
        clearReportCache()
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
            persistedApps.clear()
            namesLoaded=false
            hasKnownSnapshot=false
            knownNoLegacy=null
        }finally{database=openDatabase(databaseFile.name)}
        database.withTransaction{dual.latest()?.let{saved->dual.events(listOf(DualCodec.event(saved.time,Signal.GAP,source=EvidenceSource.OWN_WINDOW,zone=ZoneId.systemDefault().id)));markReplayDirty(saved.time)}}
        watermark.recordFull()
        watermark.bumpLabels()
        persistWatermark()
        publishReportRevision()
        "已恢复安全副本；恢复前数据库已保留，校验正常时可以撤销"
    }}
    suspend fun close()=withContext(Dispatchers.IO){mutex.withLock{database.close()}}
    var recording:Boolean
        get()=preferences.getBoolean("recording",true)
        set(value){preferences.edit { putBoolean("recording",value) }}
    fun permitted():Boolean {
        val ops=context.getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),context.packageName)==AppOpsManager.MODE_ALLOWED
    }
    suspend fun first():Long?=withContext(Dispatchers.IO){mutex.withLock{listOfNotNull<Long>(dao.first(),dual.first(),dualHistory.first()).minOrNull()}}
    suspend fun last():Long?=withContext(Dispatchers.IO){mutex.withLock{dual.latestTime()?:dao.cursor()?.time}}
    private val attributedEvidence=java.util.EnumSet.of(Evidence.CONFIRMED,Evidence.INFERRED)
    suspend fun hasLegacy(window:Window):Boolean=withContext(Dispatchers.IO){mutex.withLock{
        if(knownNoLegacy==true)false
        else if(!dao.hasAnyIntervals()){knownNoLegacy=true;false}
        else{knownNoLegacy=false;dao.hasIntervals(window.start,window.end)}
    }}
    suspend fun readLegacy(window:Window):Records=withContext(Dispatchers.IO){mutex.withLock{database.withTransaction{
        Records(dao.intervals(window.start,window.end).map{Interval(it.app,it.start,it.end)},dao.coverage(window.start,window.end).map{Coverage(it.start,it.end,it.reliable)})
    }}}
    /** One reader per transaction, with the index boundary loaded only if raw detail is needed. */
    private inner class ObservationReader(private val metric:UsageMetric){
        private var midpoint:Long?=null
        private val validatedSources=hashSetOf("")

        private suspend fun useRecentIndex(window:Window):Boolean{
            val recentFrom=midpoint?:run{
                val first=dual.first();val last=dual.lastIntervalEnd()
                // Imported detail can have no local checkpoint. Use the actual interval bounds.
                (if(first!=null&&last!=null)first+(last-first)/2 else Long.MAX_VALUE).also{midpoint=it}
            }
            return window.start>=recentFrom
        }

        suspend fun read(window:Window):List<UsageSlice>{
            val values=if(useRecentIndex(window))dual.recentSlices(window.start,window.end,metric.name)
                else dual.slices(window.start,window.end,metric.name)
            return DualCodec.effective(values)
        }

        private fun validateSources(sources:String){
            if(sources in validatedSources)return
            sources.split(',').filter{it.isNotEmpty()}.forEach{EvidenceSource.valueOf(it)}
            validatedSources+=sources
        }

        suspend fun dayCells(window:Window):List<MetricDayCell>{
            // Android 10 can ship SQLite without window functions.
            if(metric!=UsageMetric.PRIMARY||Build.VERSION.SDK_INT<Build.VERSION_CODES.R)return cells(window)
            val groups=if(useRecentIndex(window))dual.recentPrimarySliceTotals(window.start,window.end)
                else dual.primarySliceTotals(window.start,window.end)
            if(groups.any{it.unsafe})return DailyMetrics.cells(read(window),metric,window)

            // Exclusive intervals need only sums. Merge source variants in first-occurrence order.
            val totals=linkedMapOf<Pair<String?,Evidence>,LongArray>()
            var observed=0L
            var provisional=0L
            for(group in groups){
                validateSources(group.sources)
                val evidence=Evidence.valueOf(group.evidence)
                val durations=totals.getOrPut(group.app to evidence){LongArray(2)}
                durations[0]+=group.millis
                durations[1]+=group.provisionalMillis
                provisional+=group.provisionalMillis
                if(evidence==Evidence.CONFIRMED)observed+=group.millis-group.provisionalMillis
            }
            return buildList(totals.size+2){
                totals.forEach{(key,durations)->
                    add(MetricDayCell(window.start,window.end,key.first,key.second,durations[0],durations[1]))
                }
                add(MetricDayCell(window.start,window.end,DailyMetrics.SUMMARY,Evidence.UNKNOWN,window.duration-observed,provisional))
                add(MetricDayCell(window.start,window.end,DailyMetrics.OBSERVED,Evidence.CONFIRMED,observed,0))
            }
        }

        suspend fun cells(window:Window):List<MetricDayCell>{
            val measures=if(useRecentIndex(window))dual.recentSliceMeasures(window.start,window.end,metric.name)
                else dual.sliceMeasures(window.start,window.end,metric.name)
            var lastEnd=Long.MIN_VALUE
            for(row in measures){
                // Resolve archived data and conflicting primary intervals against their original proof references.
                if(row.algorithm!=2||metric==UsageMetric.PRIMARY&&row.start<lastEnd)
                    return DailyMetrics.cells(read(window),metric,window)
                lastEnd=row.end
            }
            val durations=measures.map{row->
                validateSources(row.sources)
                // The report uses the evidence grade, duration and pending state. Full detail uses read().
                UsageSlice(metric,row.app,row.start,row.end,Evidence.valueOf(row.evidence),"",emptySet(),emptySet(),row.provisional)
            }
            return DailyMetrics.cells(durations,metric,window)
        }
    }
    private suspend fun readInsideTransaction(window:Window,metric:UsageMetric):Records{
        val observations=ObservationReader(metric).read(window)
        return Records(observations.filter{it.app!=null&&it.evidence in attributedEvidence}.map{Interval(it.app!!,it.start,it.end)},observations.filter{it.evidence==Evidence.CONFIRMED&&!it.provisional}.map{Coverage(it.start,it.end,true)},observations)
    }
    suspend fun read(window:Window,metric:UsageMetric=UsageMetric.PRIMARY):Records=withContext(Dispatchers.IO){mutex.withLock{database.withTransaction{readInsideTransaction(window,metric)}}}
    private suspend fun calculateDay(window:Window,metric:UsageMetric)=ObservationReader(metric).cells(window)
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
    suspend fun discardRecoveryRollback():String=withContext(Dispatchers.IO){mutex.withLock{
        checkRecovery()
        recovery.discardRollback()
        "恢复前副本已删除，当前记录保留"
    }}
    private fun dayRows(cells:List<MetricDayCell>,metric:UsageMetric,zone:ZoneId)=cells.map{c->DailyUsage(DualCodec.hash("${c.start}|${zone.id}|$metric|${c.app}|${c.evidence}"),c.start,c.end,zone.id,metric.name,c.app,c.evidence.name,c.millis,c.provisional,3)}
    /** Bulk insert within the report transaction. Bound parameter counts for older SQLite builds. */
    private suspend fun cacheDailyRows(rows:List<DailyUsage>){
        database.useWriterConnection{connection->
            for(batch in rows.chunked(64)){
                val values=batch.joinToString(","){"(?,?,?,?,?,?,?,?,?,?)"}
                connection.usePrepared("""
                    INSERT OR REPLACE INTO daily_usage
                        (id,dayStart,dayEnd,zone,metric,app,evidence,millis,provisionalMillis,algorithm)
                    VALUES $values
                """.trimIndent()){statement->
                    var index=1
                    for(row in batch){
                        statement.bindText(index++,row.id)
                        statement.bindLong(index++,row.dayStart)
                        statement.bindLong(index++,row.dayEnd)
                        statement.bindText(index++,row.zone)
                        statement.bindText(index++,row.metric)
                        val app=row.app
                        if(app==null)statement.bindNull(index++) else statement.bindText(index++,app)
                        statement.bindText(index++,row.evidence)
                        statement.bindLong(index++,row.millis)
                        statement.bindLong(index++,row.provisionalMillis)
                        statement.bindLong(index++,row.algorithm.toLong())
                    }
                    statement.step()
                }
            }
        }
    }
    private fun hourRows(cells:List<MetricDayCell>,metric:UsageMetric,zone:ZoneId)=cells.map{c->HourUsage(DualCodec.hash("hour|${c.start}|${zone.id}|$metric|${c.app}|${c.evidence}"),c.start,c.end,zone.id,metric.name,c.app,c.evidence.name,c.millis,c.provisional,3)}
    private fun monthRows(cells:List<MetricDayCell>,metric:UsageMetric,zone:ZoneId,recordedDays:Long)=cells.map{c->MonthUsage(DualCodec.hash("month|${c.start}|${zone.id}|$metric|${c.app}|${c.evidence}"),c.start,c.end,zone.id,metric.name,c.app,c.evidence.name,c.millis,c.provisional,recordedDays,3)}
    /** Per-report cache capacity; counts and floor stay in memory until a trim forces a refresh. */
    private class CacheBudget(val cap:Int){
        var loaded=false
        var rows:Long=0L;var floor:Long?=null
        fun allow(slotStart:Long)=rows<cap||floor?.let{slotStart>=it}==true
        fun record(inserted:Int,slotStart:Long){loaded=true;rows+=inserted;if(floor==null||slotStart<floor!!)floor=slotStart}
    }
    private suspend fun prepareBudget(budget:CacheBudget,zone:ZoneId,grain:Grain){
        if(budget.loaded)return
        budget.rows=when(grain){Grain.HOUR->dual.hourCount();Grain.MONTH->dual.monthCount();else->dual.dailyCount()}
        budget.floor=when(grain){Grain.HOUR->dual.hourFirst(zone.id);Grain.MONTH->dual.monthFirst(zone.id);else->dual.cacheFirst(zone.id)}
        budget.loaded=true
    }
    private fun storageWritable()=StatFs(context.filesDir.absolutePath).availableBytes>=LocalRecovery.RESERVE
    /** Disk rows are keyed by start. Keep clipped reports in the exact-window memory cache. */
    private suspend fun dailyCells(slot:Window,metric:UsageMetric,zone:ZoneId,budget:CacheBudget,stored:List<DayMeasure>,archived:Pair<List<HistoryDay>,HistorySeal?>,floor:Long,reader:ObservationReader):List<MetricDayCell>{
        if(stored.any{it.app==DailyMetrics.SUMMARY})return stored.map{MetricDayCell(it.dayStart,it.dayEnd,it.app,Evidence.valueOf(it.evidence),it.millis,it.provisionalMillis)}
        // Durable rows outlive both cache trimming and the 90-day detail window.
        val seal=archived.second
        if(seal!=null&&seal.dayEnd==slot.end){
            val rows=archived.first.sortedWith(compareBy({it.appKey},{it.evidence}))
            HistoryRetention.validate(rows,seal)
            return rows.map{
                val grade=if(seal.origin=="IMPORTED"&&it.app!=DailyMetrics.OBSERVED&&it.evidence==Evidence.CONFIRMED.name)Evidence.INFERRED else Evidence.valueOf(it.evidence)
                MetricDayCell(it.dayStart,it.dayEnd,it.app,grade,if(seal.origin=="IMPORTED"&&it.app==DailyMetrics.OBSERVED)0 else it.millis,it.provisionalMillis)
            }
        }
        require(slot.start>=floor){"此历史范围仅保留原记录时区的完整每日汇总，无法还原部分日期"}
        val calculated=reader.dayCells(slot)
        val date=Dates.date(slot.start,zone)
        if(slot.start==Dates.midnight(date,zone)&&slot.end==Dates.midnight(date.plusDays(1),zone)&&storageWritable()){
            prepareBudget(budget,zone,Grain.DAY)
            if(budget.allow(slot.start)){
                cacheDailyRows(dayRows(calculated,metric,zone))
                budget.record(calculated.size,slot.start)
                if(budget.rows>500000){dual.trimCache(zone.id);budget.rows=dual.dailyCount();budget.floor=dual.cacheFirst(zone.id)}
            }
        }
        return calculated
    }
    /** Bounded prefetch keeps Room round trips proportional to pages, not the history's day count. */
    private inner class DayReader(val metric:UsageMetric,val zone:ZoneId,val budget:CacheBudget,val end:Long,val floor:Long){
        private val observations=ObservationReader(metric)
        private var page=Window(0,0)
        private var days=emptyMap<Long,List<DayMeasure>>()
        private var history=emptyMap<Long,List<HistoryDay>>()
        private var seals=emptyMap<Long,HistorySeal>()
        suspend fun read(slot:Window):List<MetricDayCell>{
            if(slot.start<page.start||slot.start>=page.end){
                currentCoroutineContext().ensureActive()
                page=Window(slot.start,minOf(end,Dates.midnight(Dates.date(slot.start,zone).plusDays(32),zone)))
                days=dual.dailyRange(page.start,page.end,zone.id,metric.name).groupBy{it.dayStart}
                // Avoid reopening the durable archive when every day in this page already has its cached summary.
                val missing=Reports.slots(page,zone,Grain.DAY).filter{day->
                    days[day.start].orEmpty().none{row->row.app==DailyMetrics.SUMMARY&&row.dayEnd==day.end}
                }
                if(missing.isEmpty()){
                    seals=emptyMap();history=emptyMap()
                }else{
                    val from=missing.first().start;val until=missing.last().end
                    seals=dualHistory.sealsRange(zone.id,from,until,metric.name).associateBy{it.dayStart}
                    history=if(seals.isEmpty())emptyMap()else dualHistory.rowsRange(zone.id,from,until,metric.name)
                        .filter{seals[it.dayStart]?.dayEnd==it.dayEnd}.groupBy{it.dayStart}
                }
            }
            return dailyCells(slot,metric,zone,budget,days[slot.start].orEmpty().filter{it.dayEnd==slot.end},
                history[slot.start].orEmpty() to seals[slot.start],floor,observations)
        }
    }
    /** Hour cells from the cache, with only complete elapsed-hour slots written to disk. */
    private suspend fun hourlyCells(slot:Window,metric:UsageMetric,zone:ZoneId,budget:CacheBudget,stored:List<HourMeasure>,reader:ObservationReader):List<MetricDayCell>{
        if(stored.any{it.app==DailyMetrics.SUMMARY})return stored.map{MetricDayCell(it.hourStart,it.hourEnd,it.app,Evidence.valueOf(it.evidence),it.millis,it.provisionalMillis)}
        require(slot.start>=(dualHistory.state()?.floor?:0)){"90天以前请查看每日或每月汇总"}
        val calculated=reader.cells(slot)
        if(slot.duration==3_600_000L&&storageWritable()){
            prepareBudget(budget,zone,Grain.HOUR)
            if(budget.allow(slot.start)){
                dual.hourly(hourRows(calculated,metric,zone))
                budget.record(calculated.size,slot.start)
                if(budget.rows>200000){dual.trimHourCache(zone.id);budget.rows=dual.hourCount();budget.floor=dual.hourFirst(zone.id)}
            }
        }
        return calculated
    }
    /** Month cells merged from day cells; per-month recorded days survive day-cache trimming. */
    private suspend fun monthlyCells(slot:Window,metric:UsageMetric,zone:ZoneId,reader:DayReader,monthBudget:CacheBudget,stored:List<MonthMeasure>):Pair<List<MetricDayCell>,Long>{
        if(stored.any{it.app==DailyMetrics.SUMMARY})return stored.map{MetricDayCell(it.monthStart,it.monthEnd,it.app,Evidence.valueOf(it.evidence),it.millis,it.provisionalMillis)} to stored.first().recordedDays
        val dayGroups=Reports.slots(slot,zone,Grain.DAY).map{day->day to reader.read(day)}
        val cells=DailyMetrics.mergeCells(dayGroups.map{it.second},slot)
        val days=dayGroups.sumOf{(w,c)->DailyMetrics.recordedDays(w,c,zone).toLong()}
        if(slot==Dates.fullMonth(Dates.date(slot.start,zone),zone)&&storageWritable()){
            prepareBudget(monthBudget,zone,Grain.MONTH)
            if(monthBudget.allow(slot.start)){
                dual.monthly(monthRows(cells,metric,zone,days))
                monthBudget.record(cells.size,slot.start)
                if(monthBudget.rows>500000){dual.trimMonthCache(zone.id);monthBudget.rows=dual.monthCount();monthBudget.floor=dual.monthFirst(zone.id)}
            }
        }
        return cells to days
    }
    suspend fun aggregate(window:Window,zone:ZoneId,grain:Grain,metric:UsageMetric,app:String?,includeConfirmed:Boolean=true):UsageReport=cachedReport(ReportKey(window,zone.id,grain,metric,app,includeConfirmed)){database.withTransaction{
        if(grain==Grain.HOUR){
            val budget=CacheBudget(200000)
            val reader=ObservationReader(metric)
            val slotCells=Reports.slots(window,zone,Grain.HOUR).chunked(32).flatMap{page->
                currentCoroutineContext().ensureActive()
                val stored=dual.hourlyRange(page.first().start,page.last().end,zone.id,metric.name).groupBy{it.hourStart}
                page.map{slot->slot to hourlyCells(slot,metric,zone,budget,stored[slot.start].orEmpty().filter{it.hourEnd==slot.end},reader)}
            }
            return@withTransaction DailyMetrics.hourReport(slotCells,window,zone,app,includeConfirmed)
        }
        if(grain==Grain.MONTH){
            val dayBudget=CacheBudget(500000)
            val monthBudget=CacheBudget(500000)
            val reader=DayReader(metric,zone,dayBudget,window.end,dualHistory.state()?.floor?:0)
            val monthCells=Reports.slots(window,zone,Grain.MONTH).chunked(12).flatMap{page->
                currentCoroutineContext().ensureActive()
                val stored=dual.monthlyRange(page.first().start,page.last().end,zone.id,metric.name).groupBy{it.monthStart}
                page.map{slot->slot to monthlyCells(slot,metric,zone,reader,monthBudget,stored[slot.start].orEmpty().filter{it.monthEnd==slot.end})}
            }
            return@withTransaction DailyMetrics.monthReport(monthCells.map{(slot,cells)->slot to cells.first},window,zone,app,monthCells.sumOf{it.second.second},includeConfirmed)
        }
        val budget=CacheBudget(500000)
        val reader=DayReader(metric,zone,budget,window.end,dualHistory.state()?.floor?:0)
        val accumulator=DailyMetrics.Accumulator(window,zone,grain,app,includeConfirmed=includeConfirmed)
        for(page in Reports.slots(window,zone,Grain.DAY).chunked(128)){
            currentCoroutineContext().ensureActive()
            val compact=dual.dailyTotals(page.first().start,page.last().end,zone.id,metric.name,app,includeConfirmed)
            var rowIndex=0
            val cachedDays=page.map{slot->
                val firstRow=rowIndex
                while(rowIndex<compact.size&&compact[rowIndex].dayStart<slot.end)rowIndex++
                // App totals cover contiguous ranges. A surplus row (including a partial day) would pollute that sum.
                compact.getOrNull(firstRow)?.takeIf{row->
                    rowIndex==firstRow+1&&row.dayStart==slot.start&&row.dayEnd==slot.end&&
                        row.summaries==1&&row.invalidEvidence==0
                }
            }
            var dayIndex=0
            while(dayIndex<page.size){
                currentCoroutineContext().ensureActive()
                val firstDay=dayIndex
                val isCached=cachedDays[firstDay]!=null
                do{dayIndex++}while(dayIndex<page.size&&(cachedDays[dayIndex]!=null)==isCached)
                // A fragmented cache can use the existing batched reader without an SQL query per short run.
                if(!isCached||dayIndex-firstDay<minOf(3,page.size)){
                    for(index in firstDay until dayIndex){
                        currentCoroutineContext().ensureActive()
                        accumulator.addDay(reader.read(page[index]))
                    }
                    continue
                }
                for(index in firstDay until dayIndex){
                    val day=checkNotNull(cachedDays[index])
                    accumulator.addDayTotals(page[index],day.value,day.unknown,day.provisional,day.observed,day.positive!=0)
                }
                dual.dailyAppTotals(page[firstDay].start,page[dayIndex-1].end,zone.id,metric.name,app).forEach{row->
                    accumulator.addAppTotals(row.app,row.confirmed,row.inferred,row.provisional)
                }
            }
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
        if((dual.latestTime()?:0)>System.currentTimeMillis()){log(now,"OWN_EVENT_CLOCK_QUARANTINED","Wall clock is behind saved boundary");return@withLock}
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
    private suspend fun ensureNamesLoadedInsideLock(){
        if(!namesLoaded){
            dao.apps().forEach{names.putIfAbsent(it.app,it.label);persistedApps[it.app]=it.label}
            namesLoaded=true
        }
    }
    suspend fun loadNames()=withContext(Dispatchers.IO){mutex.withLock{dao.apps().forEach{names[it.app]=it.label;persistedApps[it.app]=it.label};namesLoaded=true}}
    suspend fun ensureNamesLoaded()=withContext(Dispatchers.IO){mutex.withLock{ensureNamesLoadedInsideLock()}}
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
    /**
     * Phase A persists raw events and the pending replay boundary; phase B recomputes derived rows and can be retried from local evidence alone.
     *
     * @param foreground the caller is the visible UI and someone is waiting for the result. Housekeeping
     *   that can take seconds (the gzipped safety copy) is left to the background worker instead.
     */
    suspend fun collect(foreground:Boolean=false):String=withContext(Dispatchers.IO){
        val requestedAt=SystemClock.elapsedRealtime()
        mutex.withLock{
        val acquiredAt=SystemClock.elapsedRealtime()
        if(recording&&dual.evidenceRevision()!="strict-evidence-0311"){
            if(dual.latestTime()!=null&&dual.firstEvent()!=null)rebuildHistoryInsideLock()
            else database.withTransaction{saveEvidenceRevision()}
        }
        if(!permitted()){
            preferences.edit { putBoolean("interrupted",true) }
            setContinuityHealthy(false)
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
            setContinuityHealthy(false)
            log(now,"WALL_CLOCK_BACKWARD","Sync quarantined, saved history unchanged")
            return@withLock "设备时间早于上次记录；本次未写入使用时长"
        }
        val interrupted=preferences.getBoolean("interrupted",false)
        // Adaptive overlap: healthy continuity uses a minutes-scale window; anomalies or a due deep
        // sweep fall back to the conservative 2-day budget. First collection still looks back 7 days.
        val deepSweepDue=now-lastDeepSweep()>=QueryOverlap.DEEP_SWEEP_INTERVAL_MS
        val healthyContinuity=continuityHealthy()
        val usedDeepQuery=latest==null||!healthyContinuity||deepSweepDue
        val queryStart=QueryOverlap.queryStart(now,latest?.time,healthyContinuity,deepSweepDue)
        val homes=launcherPackages()
        var queryMs=0L
        val timings=SyncTimings()
        var rawCount=0L;var hasLifecycle=false;var earliestNew:Long?=null
        val observedApps=linkedSetOf<String>()
        try{
        // Phase A: raw evidence, continuity markers and the durable replay intent commit first.
        database.withTransaction {
            val queryStarted=SystemClock.elapsedRealtime()
            // Query the platform in bounded windows; never retain a week-sized parcel/list.
            RawEventBatchCollector.collect(
                start=queryStart,end=now,
                read={from,until->platformEvents(from,until,zone.id)},
                write={batch->dual.events(batch)},
                onRow={row->
                    if(row.app.isNotBlank())observedApps+=row.app
                    rawCount++;if(row.signal!=Signal.OTHER.name)hasLifecycle=true
                },
                onInserted={row->if(row.signal!=Signal.OTHER.name)earliestNew=minOf(earliestNew?:row.time,row.time)},
                check={currentCoroutineContext().ensureActive();ensureWriteSpace()}
            )
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
            setContinuityHealthy(false)
            try{processPendingInsideLock()}catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
            catch(local:Exception){throw SyncUnavailable("系统查询失败，本地待算仍保留：${local.message}")}
            throw SyncUnavailable("系统事件查询失败：${e.message?:e.javaClass.simpleName}")
        }
        collectPhaseListener?.invoke("raw-saved")
        var toPersist=emptyList<SavedApp>()
        if(observedApps.isNotEmpty()){
            ensureNamesLoadedInsideLock()
            toPersist=observedApps.mapNotNull{pkg->
                val resolved=label(pkg)
                if(persistedApps[pkg]!=resolved)SavedApp(pkg,resolved)else null
            }
        }
        val modifiedFrom=try{
            // Phase B: replay saved evidence and rebuild layered summaries; failure leaves raw events saved for local retry.
            database.withTransaction {
            val progress=DualCodec.decodeProgress(dual.replayMetadata())
            val requestedStart=progress.start(latest?.time,queryStart,earliestNew)
            if(toPersist.isNotEmpty())dao.apps(toPersist)
            val earliestTouched=recomputeInsideTransaction(now,elapsed,boot,zone,requestedStart,queryStart,homes,timings,sample.uncertainty)
            log(now,"SYNC_COMMITTED","query=$queryStart..$now; raw=$rawCount; replay=${timings.replayStart}; incremental=${progress.initialized}; algorithm=${DualUsageEngine.VERSION}; zone=${zone.id}")
            earliestTouched
        }
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){
            setContinuityHealthy(false)
            log(now,"REPLAY_PENDING","Raw events saved; statistics retry pending: ${e.message?.take(200)?:e.javaClass.simpleName}")
            throw SyncUnavailable("原始事件已保存，统计将在下次补记时继续：${e.message?:e.javaClass.simpleName}")
        }
        if(toPersist.isNotEmpty()){
            toPersist.forEach{persistedApps[it.app]=it.label}
            watermark.bumpLabels()
        }
        // A replay that wrote no slice leaves every derived table exactly as the last commit left it,
        // so neither the watermark nor the report cache needs to move and the UI need not reload.
        if(modifiedFrom!=null)watermark.recordIncremental(modifiedFrom)
        if(modifiedFrom!=null||toPersist.isNotEmpty())persistWatermark()
        setContinuityHealthy(QueryOverlap.continuityIntact(interrupted,clock!=null,hasLifecycle,latest?.time,now))
        if(usedDeepQuery)markDeepSweep(now)
        if(modifiedFrom!=null)invalidateReportsFrom(modifiedFrom)
        collectPhaseListener?.invoke("committed")
        if(interrupted)preferences.edit { putBoolean("interrupted",false) }
        val safetyStarted=SystemClock.elapsedRealtime()
        maintainAndSnapshot(allowSnapshot=!foreground)
        if(dev.usagetime.app.BuildConfig.DEBUG)android.util.Log.i("UsageTimeSync","complete foreground=$foreground total_ms=${SystemClock.elapsedRealtime()-requestedAt} wait_ms=${acquiredAt-requestedAt} query_ms=$queryMs replay_ms=${timings.replayMs} summary_ms=${timings.summaryMs} safety_ms=${SystemClock.elapsedRealtime()-safetyStarted} replay_span_ms=${timings.replaySpan}")
        "已保存最新记录"
    }}
    private class SyncTimings{var replayMs=0L;var summaryMs=0L;var replaySpan=0L;var replayStart=0L}
    /** Replay saved evidence, invalidate report caches and seal completed days in the caller's transaction. */
    private suspend fun recomputeInsideTransaction(now:Long,elapsed:Long,boot:String,zone:ZoneId,requestedStart:Long,cutCandidate:Long,homes:Set<String>,timings:SyncTimings,clockUncertainty:Long=0):Long?{
        val previousEnd=dual.latestTime()
        require(previousEnd==null||now>=previousEnd){"重放终点不能早于已处理边界"}
        val anchor=dual.checkpoint(requestedStart)
        val start=anchor?.time?:requestedStart
        timings.replayStart=start;timings.replaySpan=now-start
        val replayStarted=SystemClock.elapsedRealtime()
        val initial=anchor?.let{DualCodec.decode(it.state)}?:EngineState()
        val cut=cutCandidate.coerceIn(start,now)
        // Forward-only collection appends to the existing checkpoint; only a rewind replaces history.
        val rewound=previousEnd!=null&&start<previousEnd
        if(rewound){
            dual.deleteRecent(start);dual.deleteCheckpointsAfter(start)
        }
        collectPhaseListener?.invoke("stats")
        var at=start
        var state=initial
        var earliestClosure:Long?=null
        var wroteSlices=false
        // Bound live event/interval objects to one civil day; keep the commit atomic.
        while(at<now){
            currentCoroutineContext().ensureActive();ensureWriteSpace()
            val midnight=Dates.midnight(Dates.date(at,zone).plusDays(1),zone)
            val next=minOf(now,midnight,at+6*3_600_000L,if(at<cut)cut else now)
            val result=DualUsageEngine.parse(dual.events(at,next).map{it.event()},Window(at,next),state,homes)
            if(result.slices.isNotEmpty())wroteSlices=true
            result.slices.chunked(1000).forEach{batch->dual.slices(batch.map{DualCodec.stored(it)})}
            applyClosures(result.closures)?.let{earliestClosure=minOf(earliestClosure?:it,it)}
            state=result.checkpoint
            dual.checkpoint(SyncCheckpoint(next,DualCodec.encode(state),next,if(next==now)elapsed else -1,boot,zone.id))
            collectPhaseListener?.invoke("stats-written")
            dual.diagnostics(result.diagnostics.map{DiagnosticLog(DualCodec.hash("${it.time}|${it.code}|${it.detail}"),it.time,it.code,it.detail.take(512))})
            at=next
        }
        timings.replayMs=SystemClock.elapsedRealtime()-replayStarted
        // Nothing entered usage_intervals: the summaries committed last round are still exact.
        if(!SummaryRebuild.needed(wroteSlices,earliestClosure,rewound)){
            timings.summaryMs=0
            finishReplay(now)
            dual.checkpoint(SyncCheckpoint(-4,DualCodec.encodeClock(ClockSample(now,elapsed,boot,clockUncertainty)),0,-1,"","",0))
            return null
        }
        val summariesStarted=SystemClock.elapsedRealtime()
        // A changed replay domain invalidates both caches and every durable day it can touch.
        val dayStart=Dates.midnight(Dates.date(start,zone),zone)
        HistoryRetention.invalidate(database,dayStart)
        dual.deleteHoursFrom(start);dual.deleteMonthsFrom(start);dual.deleteDaysFrom(start)
        // Report readers refill missing buckets on demand. Completed days still need durable
        // summaries in the same transaction as replay, so they can outlive the raw detail.
        val completeThrough=Dates.midnight(Dates.date(now,zone),zone)
        for(daySlot in Reports.slots(Window(dayStart,completeThrough),zone,Grain.DAY)){
            for(metric in UsageMetric.entries){
                currentCoroutineContext().ensureActive()
                val cells=calculateDay(daySlot,metric)
                dual.daily(dayRows(cells,metric,zone))
                HistoryRetention.save(database,HistoryRetention.rows(cells,metric,zone))
            }
        }
        timings.summaryMs=SystemClock.elapsedRealtime()-summariesStarted
        finishReplay(now)
        dual.checkpoint(SyncCheckpoint(-4,DualCodec.encodeClock(ClockSample(now,elapsed,boot,clockUncertainty)),0,-1,"","",0))
        return minOf(start,earliestClosure?:start)
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
        val homes=launcherPackages()
        val timings=SyncTimings()
        try{
            val modifiedFrom=database.withTransaction{recomputeInsideTransaction(boundary.end,boundary.elapsed,boundary.boot,zone,plan.start,boundary.start,homes,timings,boundary.uncertainty)}
            log(sample.wall,"LOCAL_REPLAY_COMMITTED","local-only replay=${timings.replayStart}..${boundary.end}; changed=${modifiedFrom!=null}")
            // The pending boundary is cleared either way; only a real change may touch reports.
            modifiedFrom?.let{
                watermark.recordIncremental(it)
                persistWatermark()
                invalidateReportsFrom(it)
            }
            return "已根据本地事件更新统计"
        }catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){
            log(sample.wall,"REPLAY_PENDING","Raw events saved; statistics retry pending: ${e.message?.take(200)?:e.javaClass.simpleName}")
            throw SyncUnavailable("原始事件已保存，统计将在下次补记时继续：${e.message?:e.javaClass.simpleName}")
        }
    }
    private suspend fun applyClosures(values:List<ClosedEvidence>):Long?{
        if(values.isEmpty())return null
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
        return invalidate
    }
    /** Bounded-memory replay of saved evidence; never asks the platform to recover years-old events. */
    private suspend fun saveEvidenceRevision(){
        dual.checkpoint(SyncCheckpoint(-2,"strict-evidence-0311",0,-1,"","",0))
    }
    suspend fun rebuildHistory():String=withContext(Dispatchers.IO){mutex.withLock{rebuildHistoryInsideLock()}}
    private suspend fun rebuildHistoryInsideLock():String{
        ensureWriteSpace();clearReportCache();snapshotInsideLock(true)
        var rebuilt=false
        val message=database.withTransaction{
        val floor=dualHistory.state()?.floor
        val first=floor?:dual.firstEvent()?:return@withTransaction "尚无已保存原始事件"
        val last=dual.latest()?:return@withTransaction "尚无同步检查点"
        val seed=if(floor!=null)dual.checkpoint(floor)?.takeIf{it.time==floor}?.let{DualCodec.decode(it.state)}?:error("缺少压缩边界检查点")else EngineState()
        val end=last.time;var at=first;var state=seed;val step=6*3_600_000L
        val homes=launcherPackages()
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
        rebuilt=true
        "已根据保存的原始事件重算历史"
    }
        if(rebuilt){
            watermark.recordFull()
            persistWatermark()
            publishReportRevision()
        }
        return message
    }
    suspend fun writeCompressedBackup(output:java.io.OutputStream)=withContext(Dispatchers.IO){
        java.util.zip.GZIPOutputStream(output,65536).use{writeBackup(it)}
    }
    suspend fun exportJson():String {val out=java.io.ByteArrayOutputStream();writeBackup(out);return out.toString("UTF-8")}
    /** Streaming SQLite snapshot. Database/OS credentials and the active checkpoint are not restored. */
    suspend fun writeBackup(output:java.io.OutputStream)=withContext(Dispatchers.IO){mutex.withLock{database.withTransaction{
        BackupStreamWriter.write(output,categoryPreferences.read()){database.openHelper.readableDatabase.query(it)}
    }}}
    suspend fun importBackup(input:java.io.InputStream):Long=withContext(Dispatchers.IO){mutex.withLock{
        ensureWriteSpace();clearReportCache();snapshotInsideLock(true)
        val importedCategories=linkedMapOf<String,AppCategory>()
        val importedTotal=database.withTransaction{
        var imported=0L;var importedDetailStart:Long?=null
        val raw=database.openHelper.writableDatabase
        raw.execSQL("CREATE TEMP TABLE IF NOT EXISTS incoming_days AS SELECT * FROM history_days WHERE 0")
        raw.execSQL("CREATE TEMP TABLE IF NOT EXISTS incoming_seals AS SELECT * FROM history_seals WHERE 0")
        raw.execSQL("DELETE FROM incoming_days");raw.execSQL("DELETE FROM incoming_seals")
        raw.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS temp.incoming_day_key ON incoming_days(zone,dayStart,metric,appKey,evidence)")
        raw.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS temp.incoming_seal_key ON incoming_seals(zone,dayStart,metric)")
        val batches=BackupImportBatchScheduler<StoredEvent,StoredSlice,SavedInterval>(
            writeEvents={dual.events(it)},
            writeSlices=dual::slices,
            writeIntervals=dao::intervals,
            ensureWriteSpace=::ensureWriteSpace)
        val latestAllowed=System.currentTimeMillis()+86_400_000L
        val decoder=BackupRecordDecoder(latestAllowed)
        BackupStreamReader.read(input,object:BackupStreamReader.Sink{
            override suspend fun checkpoint(){ensureWriteSpace()}
            override suspend fun endArray(table:String){batches.flushBatches()}
            override suspend fun row(table:String,row:JSONObject){
                when(table){
                    "app_categories"->{
                        val app=row.getString("app")
                        require(app.isNotBlank()&&app.length<=255&&importedCategories.size<10000)
                        val fallback = AppCategory.valueOf(row.getString("category"))
                        val extended = row.optString("extended_category")
                        importedCategories[app] = AppCategory.entries.firstOrNull { it.name == extended } ?: fallback
                    }
                    "intervals"->{val value=decoder.interval(row);batches.addInterval(value,value.app.length*2L+64);imported++}
                    "coverage"->{val value=decoder.coverage(row);dao.coverage(listOf(value));if(value.reliable)dao.upgradeCoverage(value.start,value.end)}
                    "apps"->{dao.apps(listOf(decoder.app(row)))}
                    "usage_events"->{
                        batches.addEvent(decoder.event(row),row.toString().length*2L)
                    }
                    "usage_intervals"->{
                        val value=decoder.slice(row)
                        importedDetailStart=minOf(importedDetailStart?:value.start,value.start)
                        batches.addSlice(value,row.toString().length*2L);imported++
                    }
                    "history_days"->{
                        val a=row.getLong("dayStart");val b=row.getLong("dayEnd");decoder.validateRange(a,b)
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
                batches.finishRow()
            }
        })
        importedDetailStart?.let{
            // Keep immutable compacted days; reject an ambiguous merge rather than replace a total with a fragment.
            require(it>=(dualHistory.state()?.floor?:0)){"备份明细涉及已压缩历史，请在独立安装中恢复"}
            HistoryRetention.invalidate(database,it)
        }
        HistoryRetention.mergeImport(database)
        raw.execSQL("DELETE FROM incoming_days");raw.execSQL("DELETE FROM incoming_seals")
        dual.deleteDaysFrom(0);dual.deleteHoursFrom(0);dual.deleteMonthsFrom(0)
        names.clear()
        persistedApps.clear()
        namesLoaded=false
        knownNoLegacy=null
        log(System.currentTimeMillis(),"BACKUP_IMPORTED","$imported records; imported evidence cannot become locally confirmed; live checkpoints retained")
        imported
        }
        watermark.recordFull()
        watermark.bumpLabels()
        persistWatermark()
        publishReportRevision()
        maintainAndSnapshot(true)
        categoryPreferences.merge(importedCategories)
        importedTotal
    }}
    suspend fun importJson(text:String):Long=importBackup(text.byteInputStream())
}

class SyncUnavailable(message:String):Exception(message)
