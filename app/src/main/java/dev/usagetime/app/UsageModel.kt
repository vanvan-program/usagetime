package dev.usagetime.app

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.usagetime.app.data.Records
import dev.usagetime.core.*
import dev.usagetime.core.Period
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import java.time.*
import kotlin.random.Random

data class Selection(
    val page:Int=0,val period:Period=Period.WEEK,val anchor:LocalDate=LocalDate.now(),
    val custom:Pair<LocalDate,LocalDate>?=null,val app:String?=null,val detail:Boolean=false,
    val grain:Grain=Grain.MONTH,val line:Boolean=false,val monthly:Boolean=false,
    val compareA:LocalDate=LocalDate.now(),val compareB:LocalDate=LocalDate.now().minusWeeks(1),
    val same:Boolean=true,val compareApp:String?=null,val query:String="",val nameSort:Boolean=false,val metric:UsageMetric=UsageMetric.PRIMARY,val legacy:Boolean=false
){
    fun requiresReport(previous:Selection)=copy(line=previous.line,query=previous.query,nameSort=previous.nameSort)!=previous
}
data class DisplayData(val report:UsageReport?=null,val other:UsageReport?=null,val all:UsageReport?=null,val first:Long?=null,val last:Long?=null,val recordedDays:Int=0,val legacyAvailable:Boolean=false)
class UsageModel(application:Application):AndroidViewModel(application){
    val store=(application as UsageTimeApplication).store
    private val prefs=application.getSharedPreferences("usage-settings",0)
    var safetyInfo by mutableStateOf(store.storageInfo());private set
    var selection by mutableStateOf(Selection());private set
    var display by mutableStateOf(DisplayData());private set
    var demo by mutableStateOf(false);private set
    var permitted by mutableStateOf(false);private set
    var busy by mutableStateOf(false);private set
    var computing by mutableStateOf(false);private set
    var loadError by mutableStateOf<String?>(null);private set
    var syncError by mutableStateOf<String?>(null);private set
    var message by mutableStateOf<String?>(null)
    var appearance by mutableStateOf(prefs.getString("theme","system")?:"system");private set
    var showConfirmed by mutableStateOf(prefs.getBoolean("show-confirmed",false));private set
    var recording by mutableStateOf(store.recording);private set
    var now by mutableLongStateOf(System.currentTimeMillis());private set
    private val iconCache=java.util.concurrent.ConcurrentHashMap<String,android.graphics.Bitmap>()
    private var labelTick by mutableIntStateOf(0)
    val zone:ZoneId get()=ZoneId.systemDefault()
    private var reportJob:Job?=null
    private var refreshJob:Job?=null
    init{
        viewModelScope.launch{store.reportRevision.drop(1).collect{if(!demo)reload()}}
        refresh()
    }
    fun change(update:(Selection)->Selection){
        val previous=selection
        selection=update(previous)
        // These controls only render/filter the already loaded report, even while a query is running.
        if(selection.requiresReport(previous))reload()
    }
    fun navigate(page:Int){change{it.copy(page=page,detail=false,app=null,query="")}}
    fun openApp(pkg:String){change{it.copy(page=1,detail=true,app=pkg,line=true,period=if(it.page==2){if(it.monthly)Period.MONTH else Period.WEEK}else it.period,anchor=if(it.page==2)it.compareA else it.anchor)}}
    fun useDemo(value:Boolean){demo=value;now=System.currentTimeMillis();reload()}
    fun theme(value:String){appearance=value;prefs.edit().putString("theme",value).apply()}
    fun showConfirmed(value:Boolean){
        if(showConfirmed==value)return
        showConfirmed=value
        prefs.edit().putBoolean("show-confirmed",value).apply()
        display=DisplayData(first=display.first,last=display.last)
        reload()
    }
    fun enableRecording(value:Boolean){recording=value;store.recording=value;if(value)refresh()}
    fun label(pkg:String):String{labelTick;return demoNames[pkg]?:store.cachedLabel(pkg)?:pkg.substringAfterLast('.')}
    fun cachedIcon(pkg:String)=iconCache[pkg]?.asImageBitmap()
    suspend fun loadIcon(pkg:String)=withContext(Dispatchers.IO){
        runCatching{iconCache.getOrPut(pkg){
            val drawable=getApplication<Application>().packageManager.getApplicationIcon(pkg)
            val bitmap=android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888)
            drawable.setBounds(0,0,64,64);drawable.draw(android.graphics.Canvas(bitmap));bitmap
        }}.getOrNull()?.asImageBitmap()
    }
    fun refresh(){
        if(refreshJob?.isActive==true)return
        permitted=store.permitted()
        refreshJob=viewModelScope.launch {
            busy=true
            syncError=null
            // Reading saved history must not depend on a successful system query.
            runCatching{store.loadNames()}
            reload()
            reportJob?.join()
            try{if(!demo){val text=store.collect();if(permitted&&recording&&text!="已保存最新记录")message=text}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){syncError="补记失败：${e.message?:e.javaClass.simpleName}";message=syncError}
            finally{now=System.currentTimeMillis();busy=false;permitted=store.permitted();safetyInfo=store.storageInfo();reload()}
        }
    }
    fun rebuildHistory(){
        if(refreshJob?.isActive==true)return
        refreshJob=viewModelScope.launch{busy=true;try{message=store.rebuildHistory();reload()}catch(e:CancellationException){throw e}catch(e:Exception){message="重算失败，原数据已回滚：${e.message}"}finally{busy=false;safetyInfo=store.storageInfo()}}
    }
    fun updateSafetyInfo(){safetyInfo=store.storageInfo()}
    fun fullBackupExported(){store.noteFullExport();updateSafetyInfo()}
    fun safetyAction(action:Int,snapshotTime:Long?=null){
        if(refreshJob?.isActive==true)return
        refreshJob=viewModelScope.launch{
            busy=true
            try{
                message=when(action){0->store.createSafetyCopy();1->store.restoreSafetyCopy(snapshotTime=snapshotTime);2->store.restoreSafetyCopy(true);else->store.discardRecoveryRollback()}
                store.loadNames();now=System.currentTimeMillis();reload()
            }catch(e:CancellationException){throw e}
            catch(e:Exception){message="数据安全操作未完成：${e.message}"}
            finally{busy=false;safetyInfo=store.storageInfo()}
        }
    }
    fun period(value:Period){change{it.copy(period=value,anchor=LocalDate.now(),grain=Grain.MONTH)}}
    fun move(direction:Long){change{it.copy(anchor=when(it.period){Period.WEEK,Period.DAYS7->it.anchor.plusWeeks(direction);Period.MONTH->it.anchor.plusMonths(direction);Period.DAYS30->it.anchor.plusDays(30*direction);else->it.anchor.plusDays(direction)})}}
    private fun range(s:Selection,first:Long?):Window {
        val today=Dates.date(now,zone)
        val d=s.anchor.coerceAtMost(today)
        fun day(date:LocalDate)=Dates.midnight(date,zone)
        val w=when(s.period){
            Period.TODAY->Window(day(d),day(d.plusDays(1)))
            Period.WEEK->Dates.fullWeek(d,zone)
            Period.MONTH->Dates.fullMonth(d,zone)
            Period.DAYS7->Window(day(d.minusDays(6)),day(d.plusDays(1)))
            Period.DAYS30->Window(day(d.minusDays(29)),day(d.plusDays(1)))
            Period.ALL->Window(minOf(first?:now,now),now)
            Period.CUSTOM->s.custom?.let{Window(day(it.first),day(it.second.plusDays(1)))}?:Window(now,now)
        }
        return Window(minOf(w.start,now),minOf(w.end,now))
    }
    fun reload(){
        reportJob?.cancel()
        val s=selection;val isDemo=demo;val clock=now;val z=zone;val includeConfirmed=showConfirmed
        reportJob=viewModelScope.launch {
            computing=true
            try{
                val result=withContext(Dispatchers.Default){
                    val demoData=if(isDemo)sample(clock,z)else null
                    val first=if(isDemo)demoData!!.coverage.first().start else store.first()
                    val cache=mutableMapOf<Window,Records>()
                    val reports=mutableMapOf<Triple<Window,Grain,String?>,UsageReport>()
                    suspend fun buildRaw(w:Window,g:Grain,pkg:String?):UsageReport{
                        if(!isDemo)return if(s.legacy)store.aggregateLegacy(w,z,g,pkg)else store.aggregate(w,z,g,s.metric,pkg,includeConfirmed)
                        val records=demoData?:cache[w]?: (if(s.legacy)store.readLegacy(w)else store.read(w,s.metric)).also{cache[w]=it}
                        if(s.legacy)return Reports.build(records.intervals,records.coverage,w,z,g,pkg)
                        val observations=if(isDemo)records.intervals.map{UsageSlice(s.metric,it.app,it.start,it.end,Evidence.INFERRED,"SYNTHETIC_DEMO",setOf(EvidenceSource.TEST),emptySet())}else records.slices
                        val filtered=observations.filter{it.metric==s.metric&&it.app!=null&&it.evidence in setOf(Evidence.CONFIRMED,Evidence.INFERRED)}
                        val report=Reports.build(filtered.map{Interval(it.app!!,it.start,it.end)},records.coverage,w,z,g,pkg)
                        return report.copy(fullyCovered=false,coveredMillis=0,quality=DualReports.summary(observations,s.metric,w,pkg),appQuality=report.apps.associate{it.app to DualReports.summary(observations,s.metric,w,it.app)})
                    }
                    suspend fun build(w:Window,g:Grain,pkg:String?):UsageReport{
                        val key=Triple(w,g,pkg)
                        return reports[key]?:buildRaw(w,g,pkg).also{reports[key]=it}
                    }
                    if(s.page==2){
                        fun full(d:LocalDate)=if(s.monthly)Dates.fullMonth(d,z)else Dates.fullWeek(d,z)
                        val (a,b)=Dates.alignedComparison(full(s.compareA),full(s.compareB),clock,z,s.same)
                        DisplayData(build(a,Grain.DAY,s.compareApp),build(b,Grain.DAY,s.compareApp),all=if(s.compareApp!=null)build(a,Grain.DAY,null)else null,first=first,last=if(isDemo)clock else store.last())
                    }else{
                        val w=range(s,first)
                        val g=when(s.period){Period.TODAY->Grain.HOUR;Period.ALL->s.grain;Period.CUSTOM->if(w.duration>90*86400000L)Grain.MONTH else Grain.DAY;else->Grain.DAY}
                        val pkg=if(s.detail)s.app else null
                        val report=build(w,g,pkg)
                        val legacyAvailable=!isDemo&&!s.legacy&&report.apps.isEmpty()&&store.hasLegacy(w)
                        DisplayData(report,all=if(pkg!=null)build(w,g,null)else null,first=first,last=if(isDemo)clock else store.last(),recordedDays=report.recordedDays?:build(w,Grain.DAY,null).buckets.count{it.available},legacyAvailable=legacyAvailable)
                    }
                }
                ensureActive();display=result;loadError=null
                if(!isDemo)warmLabels(result)
            }catch(_:CancellationException){}catch(e:Exception){loadError="读取记录失败：${e.message?:e.javaClass.simpleName}";message=loadError}
            finally{if(isActive)computing=false}
        }
    }
    private fun warmLabels(data:DisplayData){
        val pkgs=buildSet{data.report?.apps?.forEach{add(it.app)};data.all?.apps?.forEach{add(it.app)};data.other?.apps?.forEach{add(it.app)}}
        if(pkgs.isEmpty())return
        viewModelScope.launch{
            var changed=false
            for(pkg in pkgs)if(store.cachedLabel(pkg)==null){store.warmLabel(pkg);changed=true}
            if(changed)labelTick++
        }
    }
    companion object {
        val demoNames=linkedMapOf("com.tencent.mm" to "微信","tv.danmaku.bili" to "哔哩哔哩","com.ss.android.ugc.aweme" to "抖音","com.android.chrome" to "浏览器","com.netease.cloudmusic" to "网易云音乐","com.autonavi.minimap" to "高德地图","com.tencent.mobileqq" to "QQ")
        fun sample(now:Long,zone:ZoneId):Records {
            val today=Dates.date(now,zone);val start=today.minusDays(89);val items=mutableListOf<Interval>()
            var day=start
            while(day<=today){
                var at=day.atTime(8,0).atZone(zone).toInstant().toEpochMilli()
                val random=Random(day.toEpochDay().toInt())
                demoNames.keys.forEachIndexed{i,pkg->
                    val end=at+(20+random.nextInt(80)-i*2).coerceAtLeast(5)*60000L
                    if(at<now)items+=Interval(pkg,at,minOf(end,now))
                    at=end+15*60000L
                }
                day=day.plusDays(1)
            }
            return Records(items,listOf(Coverage(Dates.midnight(start,zone),now,true)))
        }
    }
}
