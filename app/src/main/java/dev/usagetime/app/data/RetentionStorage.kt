package dev.usagetime.app.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.usagetime.core.*
import java.time.ZoneId

/** Durable history, deliberately separate from the disposable daily/hour/month caches. */
@Entity(tableName="history_days",primaryKeys=["zone","dayStart","metric","appKey","evidence"],
    indices=[Index(value=["metric","dayStart"])])
data class HistoryDay(val zone:String,val dayStart:Long,val metric:String,val appKey:String,
    val evidence:String,val dayEnd:Long,val app:String?,val millis:Long,val provisionalMillis:Long,
    val algorithm:Int=3)

@Entity(tableName="history_seals",primaryKeys=["zone","dayStart","metric"])
data class HistorySeal(val zone:String,val dayStart:Long,val metric:String,val dayEnd:Long,
    val rows:Long,val digest:String,val origin:String="LOCAL")

@Entity(tableName="retention_state")
data class RetentionState(@PrimaryKey val id:Int=0,val floor:Long,val zone:String)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_days WHERE zone=:zone AND dayStart=:start AND metric=:metric ORDER BY appKey,evidence")
    suspend fun rows(zone:String,start:Long,metric:String):List<HistoryDay>
    @Query("SELECT * FROM history_seals WHERE zone=:zone AND dayStart=:start AND metric=:metric")
    suspend fun seal(zone:String,start:Long,metric:String):HistorySeal?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun rows(rows:List<HistoryDay>)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun seal(seal:HistorySeal)
    @Query("DELETE FROM history_days WHERE dayEnd>:start") suspend fun invalidateRows(start:Long)
    @Query("DELETE FROM history_seals WHERE dayEnd>:start") suspend fun invalidateSeals(start:Long)
    @Query("DELETE FROM history_days WHERE zone=:zone AND dayStart=:start AND metric=:metric")
    suspend fun removeDay(zone:String,start:Long,metric:String)
    @Query("SELECT MIN(dayStart) FROM history_seals") suspend fun first():Long?
    @Query("SELECT * FROM retention_state WHERE id=0") suspend fun state():RetentionState?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun state(state:RetentionState)
}

object HistoryMigration {
    val statements=listOf(
        "CREATE INDEX IF NOT EXISTS index_usage_intervals_metric_end_start ON usage_intervals(metric, `end`, start)",
        "CREATE TABLE IF NOT EXISTS history_days (zone TEXT NOT NULL, dayStart INTEGER NOT NULL, metric TEXT NOT NULL, appKey TEXT NOT NULL, evidence TEXT NOT NULL, dayEnd INTEGER NOT NULL, app TEXT, millis INTEGER NOT NULL, provisionalMillis INTEGER NOT NULL, algorithm INTEGER NOT NULL, PRIMARY KEY(zone, dayStart, metric, appKey, evidence))",
        "CREATE INDEX IF NOT EXISTS index_history_days_metric_dayStart ON history_days(metric, dayStart)",
        "CREATE TABLE IF NOT EXISTS history_seals (zone TEXT NOT NULL, dayStart INTEGER NOT NULL, metric TEXT NOT NULL, dayEnd INTEGER NOT NULL, rows INTEGER NOT NULL, digest TEXT NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(zone, dayStart, metric))",
        "CREATE TABLE IF NOT EXISTS retention_state (id INTEGER NOT NULL PRIMARY KEY, floor INTEGER NOT NULL, zone TEXT NOT NULL)"
    )
    val FROM_5_TO_6=object:androidx.room.migration.Migration(5,6){
        override fun migrate(db:SupportSQLiteDatabase){statements.forEach{db.execSQL(it)}}
    }
}

/** All mutating calls require the caller's Room transaction and UsageStore mutex. */
object HistoryRetention {
    fun appKey(app:String?)=if(app==null)"0" else "1$app"
    fun rows(cells:List<MetricDayCell>,metric:UsageMetric,zone:ZoneId)=cells.map{
        HistoryDay(zone.id,it.start,metric.name,appKey(it.app),it.evidence.name,it.end,it.app,it.millis,it.provisional)
    }
    fun digest(rows:List<HistoryDay>):String=DualCodec.hash(
        rows.sortedWith(compareBy({it.appKey},{it.evidence})).joinToString("\n"){
            // Length prefixes avoid delimiter collisions with package/zone strings.
            listOf(it.zone,it.dayStart,it.metric,it.appKey,it.evidence,it.dayEnd,it.millis,it.provisionalMillis,it.algorithm)
                .joinToString(""){v->v.toString().let{s->"${s.length}:$s"}}
        })
    fun validate(rows:List<HistoryDay>,seal:HistorySeal){
        val zone=ZoneId.of(seal.zone);val date=Dates.date(seal.dayStart,zone)
        require(seal.dayStart>=0&&seal.dayStart==Dates.midnight(date,zone)&&seal.dayEnd==Dates.midnight(date.plusDays(1),zone))
        UsageMetric.valueOf(seal.metric)
        require(rows.size.toLong()==seal.rows&&rows.isNotEmpty()&&digest(rows)==seal.digest){"长期汇总校验失败，禁止清理"}
        require(rows.all{it.zone==seal.zone&&it.dayStart==seal.dayStart&&it.dayEnd==seal.dayEnd&&it.metric==seal.metric&&
            it.appKey==appKey(it.app)&&it.algorithm==3&&it.millis in 0..(seal.dayEnd-seal.dayStart)&&
            it.provisionalMillis in 0..(if(it.app==DailyMetrics.SUMMARY)seal.dayEnd-seal.dayStart else it.millis)})
        require(rows.map{it.appKey to it.evidence}.distinct().size==rows.size)
        rows.forEach{Evidence.valueOf(it.evidence)}
        require(rows.count{it.app==DailyMetrics.SUMMARY&&it.evidence==Evidence.UNKNOWN.name}==1)
        require(rows.count{it.app==DailyMetrics.OBSERVED&&it.evidence==Evidence.CONFIRMED.name}==1)
        require(seal.origin in setOf("LOCAL","IMPORTED"))
    }
    suspend fun save(db:UsageDatabase,rows:List<HistoryDay>,origin:String="LOCAL"){
        check(db.inTransaction())
        val first=rows.first()
        val seal=HistorySeal(first.zone,first.dayStart,first.metric,first.dayEnd,rows.size.toLong(),digest(rows),origin)
        validate(rows,seal)
        val dao=db.history()
        require(first.dayStart>=(dao.state()?.floor?:0)){"已压缩历史不能被重新覆盖"}
        dao.removeDay(first.zone,first.dayStart,first.metric)
        dao.rows(rows);dao.seal(seal)
    }
    suspend fun invalidate(db:UsageDatabase,start:Long){
        check(db.inTransaction())
        require(start>=(db.history().state()?.floor?:0)){"修改涉及已压缩历史，已保留现有数据"}
        db.history().invalidateRows(start);db.history().invalidateSeals(start)
    }
    /** Civil-day cutoff retains at least the most recent 90 complete days plus today. */
    fun cutoff(now:Long,zone:ZoneId)=RetentionCalendar.cutoff(now,zone)

    // Used verbatim by host fault-injection tests.
    const val DELETE_EVENTS="DELETE FROM usage_events WHERE time<:floor AND source!='LEGACY_IMPORT'"
    const val DELETE_SLICES="DELETE FROM usage_intervals WHERE `end`<=:floor AND algorithm=2"

    suspend fun compact(db:UsageDatabase,now:Long,zone:ZoneId):Long{
        check(db.inTransaction())
        val raw=db.openHelper.writableDatabase
        val floor=cutoff(now,zone)
        val previous=db.history().state()
        if(previous!=null){require(previous.zone==zone.id){"时区改变，停止清理"};if(floor<=previous.floor)return 0}
        fun count(sql:String,vararg args:Long)=raw.query(sql,args.map{it as Any?}.toTypedArray()).use{it.moveToFirst();it.getLong(0)}
        require(DualCodec.decodeProgress(db.dual().replayMetadata()).let{it.initialized&&it.dirtyFrom==null})
        require(count("SELECT COUNT(*) FROM sync_checkpoints WHERE algorithm=2 AND time=?",floor)==1L){"缺少边界检查点"}
        // A detail span ending after the cutoff could otherwise lose its tail when only its prefix is deleted.
        require(count("SELECT COUNT(*) FROM usage_intervals WHERE start<? AND (provisional=1 OR `end`>?)",floor,floor)==0L){"存在未闭合或跨边界区间"}
        val detailFirst=db.dual().first()?.let{Dates.midnight(Dates.date(it,zone),zone)}
        val first=previous?.floor?:listOfNotNull<Long>(db.history().first(),detailFirst).minOrNull()?:return 0
        var at=first
        while(at<floor){
            for(metric in UsageMetric.entries){
                val seal=db.history().seal(zone.id,at,metric.name)?:error("缺少双指标长期汇总")
                require(seal.origin=="LOCAL"){"导入汇总不能授权本机清理"}
                validate(db.history().rows(zone.id,at,metric.name),seal)
            }
            at=Dates.midnight(Dates.date(at,zone).plusDays(1),zone)
        }
        // All seals, deletes and the floor commit together; rollback preserves every detail row.
        raw.execSQL(DELETE_EVENTS,arrayOf(floor))
        raw.execSQL(DELETE_SLICES,arrayOf(floor))
        raw.execSQL("DELETE FROM daily_usage WHERE dayEnd<=?",arrayOf(floor))
        raw.execSQL("DELETE FROM hour_usage WHERE hourEnd<=?",arrayOf(floor))
        db.history().state(RetentionState(floor=floor,zone=zone.id))
        return floor
    }

    suspend fun mergeImport(db:UsageDatabase){
        val raw=db.openHelper.writableDatabase
        raw.query("SELECT zone,dayStart,metric,dayEnd,rows,digest FROM incoming_seals").use{cursor->
            while(cursor.moveToNext()){
                val seal=HistorySeal(cursor.getString(0),cursor.getLong(1),cursor.getString(2),cursor.getLong(3),cursor.getLong(4),cursor.getString(5),"IMPORTED")
                val rows=mutableListOf<HistoryDay>()
                raw.query("SELECT zone,dayStart,metric,appKey,evidence,dayEnd,app,millis,provisionalMillis,algorithm FROM incoming_days WHERE zone=? AND dayStart=? AND metric=?",arrayOf<Any?>(seal.zone,seal.dayStart,seal.metric)).use{c->
                    while(c.moveToNext())rows+=HistoryDay(c.getString(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5),if(c.isNull(6))null else c.getString(6),c.getLong(7),c.getLong(8),c.getInt(9))
                }
                validate(rows,seal)
                // Whole-bucket precedence avoids both repeated summation and partial overwrites.
                if(db.history().seal(seal.zone,seal.dayStart,seal.metric)==null){
                    val hasLocal=raw.query("SELECT EXISTS(SELECT 1 FROM usage_intervals WHERE algorithm=2 AND metric=? AND start<? AND `end`>?)",arrayOf<Any?>(seal.metric,seal.dayEnd,seal.dayStart)).use{it.moveToFirst();it.getInt(0)!=0}
                    if(!hasLocal)save(db,rows,"IMPORTED")
                }
            }
        }
        raw.query("SELECT COUNT(*) FROM incoming_days d WHERE NOT EXISTS(SELECT 1 FROM incoming_seals s WHERE s.zone=d.zone AND s.dayStart=d.dayStart AND s.metric=d.metric)").use{
            it.moveToFirst();require(it.getLong(0)==0L){"备份长期汇总缺少完整性标记"}
        }
        raw.query("SELECT COUNT(*) FROM (SELECT zone,dayStart FROM incoming_seals GROUP BY zone,dayStart HAVING COUNT(DISTINCT metric)!=2)").use{
            it.moveToFirst();require(it.getLong(0)==0L){"备份缺少每日双指标汇总"}
        }
    }
}
