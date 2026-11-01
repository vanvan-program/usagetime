package dev.usagetime.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.usagetime.core.*
import org.json.*
import java.security.MessageDigest

@Entity(tableName="usage_events",indices=[Index(value=["time","id"])])
data class StoredEvent(@PrimaryKey val id:String,val time:Long,val signal:String,val app:String,val activity:String,val instance:String?,val source:String,val zone:String,val origin:String="LOCAL",val rawType:Int?=null){
    fun event()=ObservedEvent(id,time,Signal.valueOf(signal),app,
        if(source=="OWN_WINDOW"&&app=="dev.usagetime.app"&&activity=="kotlinx.coroutines.StandaloneCoroutine")"$app.MainActivity"else activity,
        instance,EvidenceSource.valueOf(source))
}
@Entity(tableName="usage_intervals",indices=[Index("start"),Index("end"),Index(value=["metric","start"]),Index(value=["provisional","metric","app"])])
data class StoredSlice(@PrimaryKey val id:String,val metric:String,val app:String?,val start:Long,val end:Long,val evidence:String,val reason:String,val sources:String,val refs:String,val provisional:Boolean,val algorithm:Int=2){
    fun slice()=UsageSlice(UsageMetric.valueOf(metric),app,start,end,Evidence.valueOf(evidence),reason,sources.split(',').filter{it.isNotEmpty()}.map{EvidenceSource.valueOf(it)}.toSet(),refs.split(',').filter{it.isNotEmpty()}.toSet(),provisional)
}
@Entity(tableName="daily_usage",indices=[Index(value=["metric","dayStart"]),Index(value=["zone","dayStart"])])
data class DailyUsage(@PrimaryKey val id:String,val dayStart:Long,val dayEnd:Long,val zone:String,val metric:String,val app:String?,val evidence:String,val millis:Long,val provisionalMillis:Long,val algorithm:Int=2)
@Entity(tableName="hour_usage",indices=[Index(value=["metric","hourStart"]),Index(value=["zone","hourStart"])])
data class HourUsage(@PrimaryKey val id:String,val hourStart:Long,val hourEnd:Long,val zone:String,val metric:String,val app:String?,val evidence:String,val millis:Long,val provisionalMillis:Long,val algorithm:Int=3)
@Entity(tableName="month_usage",indices=[Index(value=["metric","monthStart"]),Index(value=["zone","monthStart"])])
data class MonthUsage(@PrimaryKey val id:String,val monthStart:Long,val monthEnd:Long,val zone:String,val metric:String,val app:String?,val evidence:String,val millis:Long,val provisionalMillis:Long,val recordedDays:Long,val algorithm:Int=3)
@Entity(tableName="sync_checkpoints")
data class SyncCheckpoint(@PrimaryKey val time:Long,val state:String,val wall:Long,val elapsed:Long,val boot:String,val zone:String,val algorithm:Int=2)
@Entity(tableName="diagnostic_logs",indices=[Index("time")])
data class DiagnosticLog(@PrimaryKey val id:String,val time:Long,val code:String,val detail:String)
@Dao interface DualDao {
    @Query("SELECT MIN(dayStart) FROM daily_usage WHERE zone=:zone") suspend fun cacheFirst(zone:String):Long?
    @Query("SELECT COUNT(*) FROM daily_usage") suspend fun dailyCount():Long
    @Query("DELETE FROM daily_usage WHERE algorithm!=3 OR zone!=:zone OR dayStart<(SELECT MIN(dayStart) FROM (SELECT dayStart FROM daily_usage WHERE zone=:zone ORDER BY dayStart DESC LIMIT :maxRows))") suspend fun trimCache(zone:String,maxRows:Int=500000)
    @Query("DELETE FROM diagnostic_logs WHERE code NOT IN (:critical) AND id NOT IN (SELECT id FROM diagnostic_logs ORDER BY time DESC LIMIT :keep)") suspend fun trimDiagnostics(critical:List<String>,keep:Int=10000)
    @Query("DELETE FROM sync_checkpoints WHERE algorithm=2 AND time<:before AND time NOT IN (SELECT MAX(time) FROM sync_checkpoints WHERE algorithm=2 GROUP BY time/86400000)") suspend fun compactCheckpoints(before:Long)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun events(values:List<StoredEvent>):List<Long>
    @Query("SELECT * FROM usage_events WHERE time>=:start AND time<:end AND source!='LEGACY_IMPORT' ORDER BY time,id") suspend fun events(start:Long,end:Long):List<StoredEvent>
    @Query("UPDATE usage_intervals SET evidence=:evidence, provisional=0, refs=refs||:ending, reason=replace(reason,'_OPEN_TAIL','') WHERE algorithm=2 AND metric=:metric AND app=:app AND start<:at AND provisional=1 AND instr(','||refs||',',','||:origin||',')>0 AND reason LIKE :prefix")
    suspend fun finalizeEvidence(metric:String,app:String,origin:String,at:Long,evidence:String,prefix:String,ending:String)
    @Query("SELECT MIN(time) FROM usage_events WHERE source!='LEGACY_IMPORT'") suspend fun firstEvent():Long?
    @Query("SELECT MIN(time) FROM usage_events WHERE time>=:start AND source!='LEGACY_IMPORT' AND signal!='OTHER'") suspend fun firstRelevantEventFrom(start:Long):Long?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun slices(values:List<StoredSlice>)
    @Query("SELECT * FROM usage_intervals WHERE metric=:metric AND `end`>:start AND start<:end ORDER BY start") suspend fun slices(start:Long,end:Long,metric:String):List<StoredSlice>
    @Query("DELETE FROM usage_intervals WHERE start>=:start AND algorithm=2") suspend fun deleteRecent(start:Long)
    @Query("SELECT * FROM sync_checkpoints WHERE time<=:time AND algorithm=2 ORDER BY time DESC LIMIT 1") suspend fun checkpoint(time:Long):SyncCheckpoint?
    @Query("SELECT * FROM sync_checkpoints WHERE algorithm=2 ORDER BY time DESC LIMIT 1") suspend fun latest():SyncCheckpoint?
    @Query("SELECT state FROM sync_checkpoints WHERE time=-1 AND algorithm=0") suspend fun replayMetadata():String?
    @Query("SELECT state FROM sync_checkpoints WHERE time=-2 AND algorithm=0") suspend fun evidenceRevision():String?
    @Query("DELETE FROM sync_checkpoints WHERE time>:time") suspend fun deleteCheckpointsAfter(time:Long)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun checkpoint(value:SyncCheckpoint)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun diagnostics(values:List<DiagnosticLog>)
    @Query("SELECT * FROM diagnostic_logs ORDER BY time DESC LIMIT 100") suspend fun diagnostics():List<DiagnosticLog>
    @Query("SELECT MIN(start) FROM usage_intervals") suspend fun first():Long?
    @Query("SELECT * FROM daily_usage WHERE dayStart=:start AND dayEnd=:end AND zone=:zone AND metric=:metric AND algorithm=3") suspend fun daily(start:Long,end:Long,zone:String,metric:String):List<DailyUsage>
    @Query("DELETE FROM daily_usage WHERE dayEnd>:start") suspend fun deleteDaysFrom(start:Long)
    @Query("DELETE FROM daily_usage WHERE dayEnd>:start AND zone=:zone") suspend fun deleteDays(start:Long,zone:String)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun daily(values:List<DailyUsage>)
    @Query("SELECT * FROM hour_usage WHERE hourStart=:start AND hourEnd=:end AND zone=:zone AND metric=:metric AND algorithm=3") suspend fun hourly(start:Long,end:Long,zone:String,metric:String):List<HourUsage>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun hourly(values:List<HourUsage>)
    @Query("DELETE FROM hour_usage WHERE hourEnd>:start") suspend fun deleteHoursFrom(start:Long)
    @Query("SELECT COUNT(*) FROM hour_usage") suspend fun hourCount():Long
    @Query("SELECT MIN(hourStart) FROM hour_usage WHERE zone=:zone") suspend fun hourFirst(zone:String):Long?
    @Query("DELETE FROM hour_usage WHERE algorithm!=3 OR zone!=:zone OR hourStart<(SELECT MIN(hourStart) FROM (SELECT hourStart FROM hour_usage WHERE zone=:zone ORDER BY hourStart DESC LIMIT :maxRows))") suspend fun trimHourCache(zone:String,maxRows:Int=200000)
    @Query("SELECT * FROM month_usage WHERE monthStart=:start AND monthEnd=:end AND zone=:zone AND metric=:metric AND algorithm=3") suspend fun monthly(start:Long,end:Long,zone:String,metric:String):List<MonthUsage>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun monthly(values:List<MonthUsage>)
    @Query("DELETE FROM month_usage WHERE monthEnd>:start") suspend fun deleteMonthsFrom(start:Long)
    @Query("SELECT COUNT(*) FROM month_usage") suspend fun monthCount():Long
    @Query("SELECT MIN(monthStart) FROM month_usage WHERE zone=:zone") suspend fun monthFirst(zone:String):Long?
    @Query("DELETE FROM month_usage WHERE algorithm!=3 OR zone!=:zone OR monthStart<(SELECT MIN(monthStart) FROM (SELECT monthStart FROM month_usage WHERE zone=:zone ORDER BY monthStart DESC LIMIT :maxRows))") suspend fun trimMonthCache(zone:String,maxRows:Int=500000)
}
object DualCodec {
    fun decodeProgress(text:String?):ReplayProgress {
        if(text==null)return ReplayProgress()
        return try{
            val j=JSONObject(text)
            if(j.optString("format")!="usage-replay-progress"||j.optInt("version") !in 1..2)return ReplayProgress()
            val q=j.optJSONObject("queried")?.let{QueryBoundary(it.getLong("start"),it.getLong("end"),it.getLong("elapsed"),it.getString("boot"),it.getString("zone"))}
            q?.let{java.time.ZoneId.of(it.zone)}
            ReplayProgress(j.getBoolean("ready"),if(j.isNull("dirtyFrom"))null else j.getLong("dirtyFrom"),q)
        }catch(_:IllegalArgumentException){ReplayProgress()}catch(_:org.json.JSONException){ReplayProgress()}catch(_:java.time.DateTimeException){ReplayProgress()}
    }
    fun encodeProgress(value:ReplayProgress):String=JSONObject().put("format","usage-replay-progress").put("version",2).put("ready",value.initialized).put("dirtyFrom",value.dirtyFrom?:JSONObject.NULL).put("queried",value.queried?.let{JSONObject().put("start",it.start).put("end",it.end).put("elapsed",it.elapsed).put("boot",it.boot).put("zone",it.zone)}?:JSONObject.NULL).toString()
    /** Flatten only our well-formed provenance envelope; keep the oldest source and reason. */
    fun importReason(reason:String,evidence:String,sources:String):String {
        var raw=reason;var grade=evidence;var origin=sources
        var depth=0
        while(depth++<64&&raw.startsWith("IMPORTED_UNVERIFIED:")){
            val nested=runCatching{JSONObject(raw.removePrefix("IMPORTED_UNVERIFIED:"))}.getOrNull()?:break
            val next=nested.opt("originalReason") as? String?:break
            val nextGrade=nested.opt("originalEvidence") as? String?:break
            val nextSource=nested.opt("originalSources") as? String?:break
            raw=next;grade=nextGrade;origin=nextSource
        }
        return "IMPORTED_UNVERIFIED:"+JSONObject().put("originalEvidence",grade).put("originalSources",origin).put("originalReason",raw).toString()
    }
    fun importOrigin(source:String,origin:String):String {
        var raw=origin;var originalSource=source;var depth=0
        while(depth++<64){
            val nested=runCatching{JSONObject(raw)}.getOrNull()?:break
            val next=nested.opt("originalOrigin") as? String?:break
            val nextSource=nested.opt("originalSource") as? String?:break
            raw=next;originalSource=nextSource
        }
        return JSONObject().put("originalSource",originalSource).put("originalOrigin",raw).toString()
    }
    fun backupString(table:String,name:String,value:String):String {
        if(value.length<=100000)return value
        // Recover older self-exported nested metadata within a bounded temporary budget.
        require(value.length<=4000000){"备份字段过长"}
        val normalized=when{
            table=="usage_intervals"&&name=="reason"&&value.startsWith("IMPORTED_UNVERIFIED:")->importReason(value,"UNKNOWN","LEGACY_IMPORT")
            table=="usage_events"&&name=="origin"->importOrigin("LEGACY_IMPORT",value)
            else->error("备份字段过长")
        }
        require(normalized.length<=100000){"备份字段过长"}
        return normalized
    }
    fun hash(text:String):String {
        val digits="0123456789abcdef"
        val digest=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(64){digest.forEach{b->val n=b.toInt() and 255;append(digits[n ushr 4]);append(digits[n and 15])}}
    }
    fun event(time:Long,signal:Signal,app:String="",activity:String="",instance:String?=null,source:EvidenceSource=EvidenceSource.USAGE_EVENTS,zone:String)=StoredEvent(hash(listOf(time,signal,app,activity,instance,source).joinToString("|")),time,signal.name,app,activity,instance,source.name,zone)
    fun stored(s:UsageSlice,algorithm:Int=2)=StoredSlice(hash(listOf(algorithm,s.metric,s.app,s.start,s.end,s.evidence,s.reason,s.provisional,s.references.sorted()).joinToString("|")),s.metric.name,s.app,s.start,s.end,s.evidence.name,s.reason,s.sources.sortedBy{it.name}.joinToString(","){it.name},s.references.sorted().joinToString(","),s.provisional,algorithm)
    fun encode(s:EngineState):String=JSONObject().put("screen",s.screen.name).put("locked",s.locked.name).put("references",JSONArray(s.references.toList())).put("displayOff",s.displayOff).put("screenRef",s.screenRef?:JSONObject.NULL).put("lockRef",s.lockRef?:JSONObject.NULL).put("focusRefs",JSONObject(s.focusRefs)).put("focus",JSONObject(s.focuses)).put("activities",JSONArray().apply{s.activities.forEach{(key,n)->put(JSONObject().put("key",key).put("app",n.app).put("activity",n.activity).put("instance",n.instance?:JSONObject.NULL).put("stage",n.stage.name).put("source",n.source.name).put("since",n.since).put("origin",n.origin))}}).toString()
    fun decode(text:String):EngineState {
        val j=JSONObject(text);val array=j.getJSONArray("activities");val a=(0 until array.length()).map{array.getJSONObject(it)}.associate{it.getString("key") to ActivityNode(it.getString("app"),it.getString("activity"),if(it.isNull("instance"))null else it.getString("instance"),ActivityStage.valueOf(it.getString("stage")),EvidenceSource.valueOf(it.getString("source")),it.getLong("since"),it.getString("origin"))}
        val f=j.getJSONObject("focus");val refs=j.getJSONArray("references")
        return EngineState(a,Knowledge.valueOf(j.getString("screen")),Knowledge.valueOf(j.getString("locked")),f.keys().asSequence().associateWith{f.getString(it)},(0 until refs.length()).map{refs.getString(it)}.toSet(),if(j.isNull("screenRef"))null else j.getString("screenRef"),if(j.isNull("lockRef"))null else j.getString("lockRef"),j.optJSONObject("focusRefs")?.let{obj->obj.keys().asSequence().associateWith{obj.getString(it)}}?:emptyMap(),j.optBoolean("displayOff",false))
    }
    /** Preserve archived slices, but never let imports override the locally recomputed time domain. */
    fun effective(values:List<StoredSlice>):List<UsageSlice> = values.groupBy{it.metric}.values.flatMap{effectiveMetric(it)}
    private fun effectiveMetric(values:List<StoredSlice>):List<UsageSlice> {
        val live=values.filter{it.algorithm==2}
        val decoded=live.map{it.slice()}
        // Engine output is already exclusive. Validate that invariant before bypassing re-resolution.
        if(values.size==live.size&&!DualIntervals.hasPrimaryOverlap(decoded))return decoded
        val bounds=mutableListOf<Window>()
        live.sortedBy{it.start}.forEach{slice->
            val last=bounds.lastOrNull()
            if(last!=null&&slice.start<=last.end)bounds[bounds.lastIndex]=Window(last.start,maxOf(last.end,slice.end))
            else bounds+=Window(slice.start,slice.end)
        }
        val archive=values.filter{it.algorithm!=2}.flatMap{item->
            val slice=item.slice()
            buildList{
                var at=item.start
                var i=bounds.binarySearch{if(it.end<=at)-1 else 1}.let{if(it<0)-it-1 else it}
                while(i<bounds.size&&bounds[i].start<item.end){
                    val b=bounds[i++]
                    if(at<b.start)add(slice.copy(start=at,end=minOf(b.start,item.end)))
                    at=maxOf(at,b.end)
                }
                if(at<item.end)add(slice.copy(start=at,end=item.end))
            }
        }
        return DualIntervals.resolvePrimary(decoded+archive)
    }
}
object DualMigration {
    // Kept as explicit SQL so the identical production statements can be checked with SQLite on a host.
    val statements=listOf(
        "CREATE TABLE IF NOT EXISTS usage_events (id TEXT NOT NULL PRIMARY KEY, time INTEGER NOT NULL, signal TEXT NOT NULL, app TEXT NOT NULL, activity TEXT NOT NULL, instance TEXT, source TEXT NOT NULL, zone TEXT NOT NULL, origin TEXT NOT NULL, rawType INTEGER)",
        "CREATE INDEX IF NOT EXISTS index_usage_events_time ON usage_events(time)",
        "CREATE TABLE IF NOT EXISTS usage_intervals (id TEXT NOT NULL PRIMARY KEY, metric TEXT NOT NULL, app TEXT, start INTEGER NOT NULL, `end` INTEGER NOT NULL, evidence TEXT NOT NULL, reason TEXT NOT NULL, sources TEXT NOT NULL, refs TEXT NOT NULL, provisional INTEGER NOT NULL, algorithm INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_usage_intervals_start ON usage_intervals(start)",
        "CREATE INDEX IF NOT EXISTS index_usage_intervals_end ON usage_intervals(`end`)",
        "CREATE INDEX IF NOT EXISTS index_usage_intervals_metric_start ON usage_intervals(metric,start)",
        "CREATE INDEX IF NOT EXISTS index_usage_intervals_provisional_metric_app ON usage_intervals(provisional,metric,app)",
        "CREATE TABLE IF NOT EXISTS daily_usage (id TEXT NOT NULL PRIMARY KEY, dayStart INTEGER NOT NULL, dayEnd INTEGER NOT NULL, zone TEXT NOT NULL, metric TEXT NOT NULL, app TEXT, evidence TEXT NOT NULL, millis INTEGER NOT NULL, provisionalMillis INTEGER NOT NULL, algorithm INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_daily_usage_metric_dayStart ON daily_usage(metric,dayStart)",
        "CREATE INDEX IF NOT EXISTS index_daily_usage_zone ON daily_usage(zone)",
        "CREATE TABLE IF NOT EXISTS sync_checkpoints (time INTEGER NOT NULL PRIMARY KEY, state TEXT NOT NULL, wall INTEGER NOT NULL, elapsed INTEGER NOT NULL, boot TEXT NOT NULL, zone TEXT NOT NULL, algorithm INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS diagnostic_logs (id TEXT NOT NULL PRIMARY KEY, time INTEGER NOT NULL, code TEXT NOT NULL, detail TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_diagnostic_logs_time ON diagnostic_logs(time)"
    )
    val FROM_1_TO_2=object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){statements.forEach{db.execSQL(it)}}}
    val statementsV3=listOf(
        "CREATE TABLE IF NOT EXISTS hour_usage (id TEXT NOT NULL PRIMARY KEY, hourStart INTEGER NOT NULL, hourEnd INTEGER NOT NULL, zone TEXT NOT NULL, metric TEXT NOT NULL, app TEXT, evidence TEXT NOT NULL, millis INTEGER NOT NULL, provisionalMillis INTEGER NOT NULL, algorithm INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_hour_usage_metric_hourStart ON hour_usage(metric, hourStart)",
        "CREATE INDEX IF NOT EXISTS index_hour_usage_zone ON hour_usage(zone)",
        "CREATE TABLE IF NOT EXISTS month_usage (id TEXT NOT NULL PRIMARY KEY, monthStart INTEGER NOT NULL, monthEnd INTEGER NOT NULL, zone TEXT NOT NULL, metric TEXT NOT NULL, app TEXT, evidence TEXT NOT NULL, millis INTEGER NOT NULL, provisionalMillis INTEGER NOT NULL, recordedDays INTEGER NOT NULL, algorithm INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_month_usage_metric_monthStart ON month_usage(metric, monthStart)",
        "CREATE INDEX IF NOT EXISTS index_month_usage_zone ON month_usage(zone)"
    )
    val FROM_2_TO_3=object:Migration(2,3){override fun migrate(db:SupportSQLiteDatabase){statementsV3.forEach{db.execSQL(it)}}}
    val statementsV4=listOf(
        "DROP INDEX IF EXISTS index_daily_usage_zone",
        "CREATE INDEX IF NOT EXISTS index_daily_usage_zone_dayStart ON daily_usage(zone, dayStart)",
        "DROP INDEX IF EXISTS index_hour_usage_zone",
        "CREATE INDEX IF NOT EXISTS index_hour_usage_zone_hourStart ON hour_usage(zone, hourStart)",
        "DROP INDEX IF EXISTS index_month_usage_zone",
        "CREATE INDEX IF NOT EXISTS index_month_usage_zone_monthStart ON month_usage(zone, monthStart)"
    )
    val FROM_3_TO_4=object:Migration(3,4){override fun migrate(db:SupportSQLiteDatabase){statementsV4.forEach{db.execSQL(it)}}}
    val statementsV5=listOf(
        "DROP INDEX IF EXISTS index_usage_events_time",
        "CREATE INDEX IF NOT EXISTS index_usage_events_time_id ON usage_events(time, id)"
    )
    val FROM_4_TO_5=object:Migration(4,5){override fun migrate(db:SupportSQLiteDatabase){statementsV5.forEach{db.execSQL(it)}}}
}
