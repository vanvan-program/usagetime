package dev.usagetime.app

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.usagetime.app.data.Records
import dev.usagetime.app.data.CategoryPreferences
import dev.usagetime.app.sync.DesktopDataSettings
import dev.usagetime.app.sync.DesktopSync
import dev.usagetime.core.*
import dev.usagetime.core.Period
import kotlinx.coroutines.*
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.drop
import java.time.*
import kotlin.random.Random

class UsageModel(application:Application,val store:dev.usagetime.app.data.UsageStore,private val wallClock:()->Long=System::currentTimeMillis):AndroidViewModel(application){
    constructor(application:Application):this(application,(application as UsageTimeApplication).store)
    private val prefs=application.getSharedPreferences("usage-settings",0)
    private val categoryPreferences=CategoryPreferences(application)
    var categories by mutableStateOf(categoryPreferences.read());private set
    private var demoCategories by mutableStateOf<Map<String,AppCategory>>(emptyMap())
    val categoryAssignments:Map<String,AppCategory> get()=if(demo)demoCategories else categories
    var safetyInfo by mutableStateOf<dev.usagetime.app.data.UsageStore.StorageInfo?>(null);private set
    var safetyInfoLoadFailed by mutableStateOf(false);private set
    private var safetyInfoJob:Job?=null
    private val initialDate=Dates.date(wallClock(),ZoneId.systemDefault())
    var selection by mutableStateOf(Selection(
        anchor=initialDate,compareA=initialDate,compareB=initialDate.minusWeeks(1),
        metric=UsageMetric.entries.firstOrNull{it.name==prefs.getString("widget-metric","")}?:UsageMetric.PRIMARY,
        legacy=prefs.getBoolean("widget-legacy",false)));private set
    var display by mutableStateOf(DisplayData());private set
    var demo by mutableStateOf(false);private set
    var permitted by mutableStateOf(store.permitted());private set
    var busy by mutableStateOf(false);private set
    var computing by mutableStateOf(false);private set
    var loadError by mutableStateOf<String?>(null);private set
    var syncError by mutableStateOf<String?>(null);private set
    var message by mutableStateOf<String?>(null)
    var appearance by mutableStateOf(prefs.getString("theme","system")?:"system");private set
    var showConfirmed by mutableStateOf(prefs.getBoolean("show-confirmed",false));private set
    var showDesktopData by mutableStateOf(DesktopDataSettings.enabled(application));private set
    var desktopRevision by mutableIntStateOf(0);private set
    private var desktopPullJob:Job?=null
    var recording by mutableStateOf(store.recording);private set
    var now by mutableLongStateOf(wallClock());private set
    private data class CachedAppIcon(val bitmap:androidx.compose.ui.graphics.ImageBitmap,val bytes:Int)
    private val iconCache=object:android.util.LruCache<String,CachedAppIcon>(2*1024*1024){
        override fun sizeOf(key:String,value:CachedAppIcon)=value.bytes
    }
    private val missingIcons=android.util.LruCache<String,Boolean>(256)
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
    private var reportSelection:Selection?=null
    fun reportDrawn(data:DisplayData){
        if(selection.isReportPage&&data===pendingDraw){
            lastCpuDrawMillis=(android.os.SystemClock.elapsedRealtimeNanos()-drawRequestedAt)/1_000_000.0
            pendingDraw=null
            if(!demo)warmLabels{
                buildSet {
                    for(report in listOfNotNull(data.report,data.all,data.other,data.allOther)){
                        report.apps.forEach{add(it.app)}
                    }
                }
            }
        }
    }
    fun refreshTimeContext(){
        val currentZone=ZoneId.systemDefault()
        if(currentZone!=zone||Dates.date(wallClock(),currentZone)!=observedToday)reload()
    }
    init{
        viewModelScope.launch{store.reportRevision.drop(1).collect{if(!demo)reload()}}
    }
    fun change(update:(Selection)->Selection){
        val previous=selection
        selection=update(previous)
        if(!demo&&(selection.metric!=previous.metric||selection.legacy!=previous.legacy)){
            prefs.edit {
                putString("widget-metric", selection.metric.name)
                putBoolean("widget-legacy", selection.legacy)
            }
            UsageWidgetProvider.requestUpdate(getApplication())
        }
        // These controls only render/filter the already loaded report, even while a query is running.
        if(selection.requiresReport(previous)){
            if(selection.page==4)display=display.invalidateReport()
            reload()
        }
    }
    fun navigate(page:Int){change{it.copy(page=if(page==5&&!showDesktopData)0 else page,detail=false,app=null,query="")}}
    fun openApp(pkg:String){change{it.copy(page=1,detail=true,app=pkg,line=true,period=if(it.page==2){if(it.monthly)Period.MONTH else Period.WEEK}else it.period,anchor=if(it.page==2)it.compareA else it.anchor)}}
    fun useDemo(value:Boolean){
        if(selection.page==4)display=display.invalidateReport()
        demo=value
        now=wallClock()
        reload()
    }
    fun assignCategory(pkg:String, category:AppCategory?) {
        if (demo) {
            demoCategories = if (category == null) demoCategories - pkg else demoCategories + (pkg to category)
        } else {
            try {
                categories = categoryPreferences.save(pkg, category)
                UsageWidgetProvider.requestUpdate(getApplication())
            } catch (error: Exception) {
                message = error.message ?: "无法保存分类设置"
            }
        }
    }
    fun theme(value:String){appearance=value;prefs.edit { putString("theme", value) };UsageWidgetProvider.requestUpdate(getApplication())}
    fun showDesktopData(value:Boolean){
        if(showDesktopData==value)return
        DesktopDataSettings.save(getApplication(),value)
        showDesktopData=value
        desktopPullJob?.cancel()
        if(!value){
            if(selection.page==5)navigate(0)
            return
        }
        desktopPullJob=viewModelScope.launch{
            DesktopSync.pullIfStale(getApplication(),0)
            desktopRevision++
        }
    }
    fun showConfirmed(value:Boolean){
        if(showConfirmed==value)return
        showConfirmed=value
        prefs.edit { putBoolean("show-confirmed", value) }
        UsageWidgetProvider.requestUpdate(getApplication())
        display=DisplayData(first=display.first,last=display.last)
        reload()
    }
    fun enableRecording(value:Boolean){recording=value;store.recording=value;if(value)refresh()}
    fun label(pkg:String):String{labelTick;return demoNames[pkg]?:store.cachedLabel(pkg)?:pkg.substringAfterLast('.')}
    fun cachedIcon(pkg:String)=iconCache[pkg]?.bitmap
    suspend fun loadIcon(pkg:String)=withContext(Dispatchers.IO){
        iconCache[pkg]?.bitmap?.let{return@withContext it}
        if(missingIcons[pkg]==true)return@withContext null
        runCatching{
            val drawable=getApplication<Application>().packageManager.getApplicationIcon(pkg)
            val bitmap=createBitmap(64,64)
            drawable.setBounds(0,0,64,64);drawable.draw(android.graphics.Canvas(bitmap))
            val image=bitmap.asImageBitmap()
            iconCache.put(pkg,CachedAppIcon(image,bitmap.allocationByteCount))
            image
        }.onFailure{missingIcons.put(pkg,true)}.getOrNull()
    }
    fun refresh()=refresh(waitForReportFrame=false)
    internal fun refreshOnResume()=refresh(waitForReportFrame=display.report==null)

    private fun refresh(waitForReportFrame:Boolean){
        if(refreshJob?.isActive==true)return
        val beforePermitted=store.permitted()
        permitted=beforePermitted
        refreshJob=viewModelScope.launch {
            busy=true
            syncError=null
            val beforeRevision=store.reportRevision.value
            // Reading saved history must not depend on a successful system query.
            reload()
            reportRequests.join()
            try{
                if(waitForReportFrame&&display.report!=null&&permitted&&recording&&!demo){
                    // The first callback precedes drawing. Yield through that frame before starting collection.
                    // Keep a fallback for windows that are detached or no longer producing frames.
                    withTimeoutOrNull(200L){awaitFrame();awaitFrame()}
                }
                if(!demo){val text=store.collect(foreground=true);if(permitted&&recording&&text!="已保存最新记录")message=text}
            }
            catch(e:CancellationException){throw e}
            catch(e:Exception){syncError="补记失败：${e.message?:e.javaClass.simpleName}";message=syncError}
            finally{
                val afterPermitted=store.permitted()
                val revisionChanged=store.reportRevision.value!=beforeRevision
                val permissionChanged=afterPermitted!=beforePermitted
                now=wallClock();busy=false;permitted=afterPermitted
                if(permissionChanged&&!revisionChanged)reload()
                if(!demo&&(revisionChanged||permissionChanged))UsageWidgetProvider.requestUpdate(getApplication())
            }
        }
    }
    fun rebuildHistory(){
        if(refreshJob?.isActive==true)return
        refreshJob=viewModelScope.launch{busy=true;try{message=store.rebuildHistory();reload()}catch(e:CancellationException){throw e}catch(e:Exception){message="重算失败，原数据已回滚：${e.message}"}finally{busy=false;updateSafetyInfo()}}
    }
    fun updateSafetyInfo(){
        safetyInfoJob?.cancel()
        safetyInfo=null
        safetyInfoLoadFailed=false
        safetyInfoJob=viewModelScope.launch{
            try{safetyInfo=store.storageInfo()}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){safetyInfoLoadFailed=true}
        }
    }
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
            finally{busy=false;updateSafetyInfo()}
        }
    }
    fun period(value:Period){change{it.copy(period=value,anchor=Dates.date(wallClock(),ZoneId.systemDefault()),grain=Grain.MONTH)}}
    fun move(direction:Long){change{it.stepAnchor(direction)}}
    fun reload(){
        if(!demo)categories=categoryPreferences.read()
        val isDemo=demo;val clock=if(isDemo)now else wallClock();val z=ZoneId.systemDefault();val includeConfirmed=showConfirmed
        val today=Dates.date(clock,z)
        if(today!=observedToday){selection=selection.followCurrentDate(observedToday,today);observedToday=today}
        now=clock
        if(!selection.isReportPage){zone=z;reportRequests.stop();display=display.invalidateReport();loadError=null;return}
        reportRequests.cancel()
        val s=selection
        reportSelection=s
        val requestedAt=android.os.SystemClock.elapsedRealtimeNanos()
        reportRequests.submit(load={loadDisplay(s,isDemo,clock,z,includeConfirmed)}){result->
            display=result;zone=z;loadError=null
            pendingDraw=result;drawRequestedAt=requestedAt;lastCpuDrawMillis=null
            lastLoadMillis=(android.os.SystemClock.elapsedRealtimeNanos()-requestedAt)/1_000_000.0
        }
    }
    private suspend fun loadDisplay(s:Selection,isDemo:Boolean,clock:Long,z:ZoneId,includeConfirmed:Boolean)=withContext(Dispatchers.Default){
        ReportLoader(
            first={store.first()},last={store.last()},hasLegacy={store.hasLegacy(it)},
            aggregate={w,g,pkg->if(s.legacy)store.aggregateLegacy(w,z,g,pkg)else store.aggregate(w,z,g,s.metric,pkg,includeConfirmed)}
        ).load(s,clock,z,if(isDemo)sample(clock,z)else null)
    }
    internal suspend fun exportReport():ReportExportSnapshot{
        val current=ReportExportRequest.selection(selection,reportSelection)
        val includeConfirmed=showConfirmed
        val clock=wallClock()
        val z=ZoneId.systemDefault()
        val snapshot=ReportExportRequest.load(demo,current,includeConfirmed,clock,z){snapshot,confirmed,at,zone->
            loadDisplay(snapshot,false,at,zone,confirmed)
        }
        resolveLabels(buildSet {
            snapshot.report.apps.forEach{add(it.app)}
            snapshot.other?.apps?.forEach{add(it.app)}
        })
        return snapshot
    }
    private fun warmLabels(packages: () -> Set<String>){
        // Dispatch out of the draw callback so loading names never delays the report draw.
        viewModelScope.launch(Dispatchers.Main){
            val apps=packages()
            if(apps.isEmpty())return@launch
            resolveLabels(apps)
            labelTick++
        }
    }
    private suspend fun resolveLabels(apps:Set<String>){
        val missing=apps.filter{store.cachedLabel(it)==null}
        if(missing.isEmpty())return
        try{store.ensureNamesLoaded()}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){} // Unavailable stored names still have a package-manager fallback.
        for(pkg in missing)if(store.cachedLabel(pkg)==null)store.warmLabel(pkg)
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
