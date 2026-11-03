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

class UsageModel(application:Application,val store:dev.usagetime.app.data.UsageStore,private val wallClock:()->Long=System::currentTimeMillis):AndroidViewModel(application){
    constructor(application:Application):this(application,(application as UsageTimeApplication).store)
    private val prefs=application.getSharedPreferences("usage-settings",0)
    var safetyInfo by mutableStateOf(store.storageInfo());private set
    private val initialDate=Dates.date(wallClock(),ZoneId.systemDefault())
    var selection by mutableStateOf(Selection(anchor=initialDate,compareA=initialDate,compareB=initialDate.minusWeeks(1)));private set
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
    var now by mutableLongStateOf(wallClock());private set
    private val iconCache=object:android.util.LruCache<String,android.graphics.Bitmap>(2*1024*1024){
        override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.allocationByteCount
    }
    private var labelTick by mutableIntStateOf(0)
    val labelRevision:Int get()=labelTick
    var lastLoadMillis:Double=0.0;private set
    var lastCpuDrawMillis:Double?=null;private set
    private var pendingDraw:DisplayData?=null
    private var drawRequestedAt=0L
    var zone:ZoneId by mutableStateOf(ZoneId.systemDefault());private set
    private var observedToday=Dates.date(wallClock(),zone)
    private val reportRequests=ReportRequestRunner<DisplayData>(
        scope=viewModelScope,
        onComputing={computing=it},
        onError={e->loadError="读取记录失败：${e.message?:e.javaClass.simpleName}";message=loadError},
        beginTrace={android.os.Trace.beginAsyncSection("UsageReport.load",it)},
        endTrace={android.os.Trace.endAsyncSection("UsageReport.load",it)})
    private var refreshJob:Job?=null
    fun reportDrawn(data:DisplayData){
        if(selection.page<3&&data===pendingDraw){
            lastCpuDrawMillis=(android.os.SystemClock.elapsedRealtimeNanos()-drawRequestedAt)/1_000_000.0
            pendingDraw=null
        }
    }
    fun refreshTimeContext(){
        val currentZone=ZoneId.systemDefault()
        if(currentZone!=zone||Dates.date(wallClock(),currentZone)!=observedToday)reload()
    }
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
    fun useDemo(value:Boolean){demo=value;now=wallClock();reload()}
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
        runCatching{(iconCache[pkg]?:run{
            val drawable=getApplication<Application>().packageManager.getApplicationIcon(pkg)
            val bitmap=android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888)
            drawable.setBounds(0,0,64,64);drawable.draw(android.graphics.Canvas(bitmap));iconCache.put(pkg,bitmap);bitmap
        })}.getOrNull()?.asImageBitmap()
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
            reportRequests.join()
            try{if(!demo){val text=store.collect();if(permitted&&recording&&text!="已保存最新记录")message=text}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){syncError="补记失败：${e.message?:e.javaClass.simpleName}";message=syncError}
            finally{now=wallClock();busy=false;permitted=store.permitted();safetyInfo=store.storageInfo();reload()}
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
                store.loadNames();now=wallClock();reload()
            }catch(e:CancellationException){throw e}
            catch(e:Exception){message="数据安全操作未完成：${e.message}"}
            finally{busy=false;safetyInfo=store.storageInfo()}
        }
    }
    fun period(value:Period){change{it.copy(period=value,anchor=Dates.date(wallClock(),ZoneId.systemDefault()),grain=Grain.MONTH)}}
    fun move(direction:Long){change{it.copy(anchor=when(it.period){Period.WEEK,Period.DAYS7->it.anchor.plusWeeks(direction);Period.MONTH->it.anchor.plusMonths(direction);Period.DAYS30->it.anchor.plusDays(30*direction);else->it.anchor.plusDays(direction)})}}
    fun reload(){
        if(selection.page==3){reportRequests.stop();return}
        reportRequests.cancel()
        val isDemo=demo;val clock=if(isDemo)now else wallClock();val z=ZoneId.systemDefault();val includeConfirmed=showConfirmed
        val today=Dates.date(clock,z)
        if(today!=observedToday){selection=selection.followCurrentDate(observedToday,today);observedToday=today}
        val s=selection
        now=clock
        val requestedAt=android.os.SystemClock.elapsedRealtimeNanos()
        reportRequests.submit(load={
            withContext(Dispatchers.Default){
                ReportLoader(
                    first={store.first()},last={store.last()},hasLegacy={store.hasLegacy(it)},
                    aggregate={w,g,pkg->if(s.legacy)store.aggregateLegacy(w,z,g,pkg)else store.aggregate(w,z,g,s.metric,pkg,includeConfirmed)}
                ).load(s,clock,z,if(isDemo)sample(clock,z)else null)
            }
        }){result->
            display=result;zone=z;loadError=null
            pendingDraw=result;drawRequestedAt=requestedAt;lastCpuDrawMillis=null
            lastLoadMillis=(android.os.SystemClock.elapsedRealtimeNanos()-requestedAt)/1_000_000.0
            if(!isDemo)warmLabels(result)
        }
    }
    private fun warmLabels(data:DisplayData){
        val pkgs=buildSet{data.report?.apps?.forEach{add(it.app)};data.all?.apps?.forEach{add(it.app)};data.other?.apps?.forEach{add(it.app)};data.allOther?.apps?.forEach{add(it.app)}}
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
