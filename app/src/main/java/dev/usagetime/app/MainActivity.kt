package dev.usagetime.app

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.usagetime.core.*
import dev.usagetime.core.Period
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.time.format.DateTimeFormatter

class MainActivity:ComponentActivity(){
    private val observationId=java.util.UUID.randomUUID().toString()
    private fun observe(signal:Signal){
        val app=application as UsageTimeApplication
        val clock=ClockSampling.capture(System::currentTimeMillis,android.os.SystemClock::elapsedRealtime,Settings.Global.getInt(contentResolver,Settings.Global.BOOT_COUNT,-1).toString())
        val at=clock.wall
        val interactive=getSystemService(android.os.PowerManager::class.java).isInteractive
        val locked=getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        val activityName=this@MainActivity.javaClass.name
        app.observationScope.launch{runCatching{app.store.ownEvent(signal,activityName,"${app.processSession}/$observationId",at,interactive,locked,app.processSession,app.processStartedAt,clock)}}
    }
    override fun onResume(){super.onResume();observe(Signal.RESUME)}
    override fun onPause(){observe(Signal.PAUSE);super.onPause()}
    override fun onStop(){observe(Signal.STOP);super.onStop()}
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);observe(if(hasFocus)Signal.FOCUS_GAIN else Signal.FOCUS_LOSS)}
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();setContent{UsageApp()}}
}
private val Blue=Color(0xFF3F56B5)
private val Brown=Color(0xFF8A5F16)
@Composable fun UsageApp(model:UsageModel=viewModel()){
    var safetyDialog by remember{mutableStateOf(false)}
    var diagnosticText by remember{mutableStateOf<String?>(null)}
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    val scope=rememberCoroutineScope();val snack=remember{SnackbarHostState()}
    val systemDark=isSystemInDarkTheme()
    val dark=model.appearance=="dark"||(model.appearance=="system"&&systemDark)
    SideEffect{(context as? ComponentActivity)?.enableEdgeToEdge(statusBarStyle=if(dark)SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),navigationBarStyle=if(dark)SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT))}
    val colors=if(dark)darkColorScheme(primary=Color(0xFF9BAAF0),secondary=Color(0xFFD5A05A),background=Color(0xFF0A0A0A),surface=Color(0xFF111111),surfaceContainer=Color(0xFF1C1C1C),onSurface=Color(0xFFEDEDED),onSurfaceVariant=Color(0xFFB1B1B1),outlineVariant=Color(0xFF343434))
        else lightColorScheme(primary=Blue,secondary=Brown,background=Color.White,surface=Color.White,surfaceContainer=Color(0xFFF3F3F3),onSurface=Color(0xFF171717),onSurfaceVariant=Color(0xFF5C5C5C),outlineVariant=Color(0xFFE6E6E6))
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)scope.launch{
        BackupDocumentCoordinator.exportBackup(
            write={withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{model.store.writeBackup(it)}?:error("无法写入文件")}},
            markExported=model::fullBackupExported,message={model.message=it},
            success="备份已导出",failurePrefix="导出失败：")
    }}
    val compressedExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")){uri->if(uri!=null)scope.launch{
        BackupDocumentCoordinator.exportBackup(
            write={withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{model.store.writeCompressedBackup(it)}?:error("无法写入文件")}},
            markExported=model::fullBackupExported,message={model.message=it},
            success="压缩完整备份已导出",failurePrefix="导出未完成，请保留此前副本：")
    }}
    val csv=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->if(uri!=null)scope.launch{
        try{val snapshot=model.exportReport();withContext(Dispatchers.IO){
            val body=CsvReportFormat.build(snapshot,model::label)
            context.contentResolver.openOutputStream(uri)?.use{it.write(body.toByteArray())}?:error("无法写入文件")
        };model.message="报表已导出"}catch(e:Exception){model.message="导出失败：${e.message}"}
    }}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{
        BackupDocumentCoordinator.importBackup(read={withContext(Dispatchers.IO){
            context.contentResolver.openInputStream(uri)?.use{model.store.importBackup(it)}?:error("无法读取文件")
        }},loadNames={model.store.loadNames()},updateSafety=model::updateSafetyInfo,
            useDemo=model::useDemo,reload=model::reload,message={model.message=it})
    }}
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)model.refresh()};lifecycle.lifecycle.addObserver(observer);onDispose{lifecycle.lifecycle.removeObserver(observer)}}
    LaunchedEffect(lifecycle,model){
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
            while(true){kotlinx.coroutines.delay(60_000);model.refreshTimeContext()}
        }
    }
    LaunchedEffect(model.message){model.message?.let{snack.showSnackbar(it);model.message=null}}
    BackHandler(model.selection.detail){model.navigate(1)}
    MaterialTheme(colorScheme=colors,shapes=Shapes(small=RoundedCornerShape(6.dp),medium=RoundedCornerShape(12.dp),large=RoundedCornerShape(16.dp))){
        if(safetyDialog)StorageSafetyDialog(model){safetyDialog=false}
        diagnosticText?.let{text->AlertDialog(onDismissRequest={diagnosticText=null},title={Text("采集诊断（仅本机）")},text={Text(text,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)},confirmButton={TextButton(onClick={diagnosticText=null}){Text("关闭")}})}
        val s=model.selection
        val rendered=model.display
        val navItems=listOf("总览","应用","对比","设置")
        val navIcons=listOf(Icons.Outlined.Home,Icons.Outlined.Apps,Icons.Outlined.BarChart,Icons.Outlined.Settings)
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background).drawWithContent{
            drawContent();model.reportDrawn(rendered)
        }){
            val wide=maxWidth>=600.dp
            Scaffold(containerColor=colors.background,snackbarHost={SnackbarHost(snack)},bottomBar={if(!wide)NavigationBar(containerColor=colors.surface,tonalElevation=0.dp){navItems.forEachIndexed{i,label->NavigationBarItem(selected=s.page==i,onClick={model.navigate(i)},icon={Icon(navIcons[i],null)},label={Text(label)})}}}){padding->
                Row(Modifier.fillMaxSize().padding(padding)){
                    if(wide)NavigationRail(containerColor=colors.surface){Spacer(Modifier.height(12.dp));navItems.forEachIndexed{i,label->NavigationRailItem(selected=s.page==i,onClick={model.navigate(i)},icon={Icon(navIcons[i],null)},label={Text(label)})}}
                    Column(Modifier.weight(1f).fillMaxHeight().widthIn(max=840.dp)){
                        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
                            if(s.detail)IconButton(onClick={model.navigate(1)}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回应用列表")}
                            Text(if(s.detail)model.label(s.app?:"")else listOf("用时记","应用","周期对比","设置")[s.page],modifier=Modifier.weight(1f).padding(start=8.dp),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                            if(model.demo)Text("示例数据",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                            if(s.page<2)IconButton(onClick={customDates(context,model)}){Icon(Icons.Outlined.CalendarMonth,"选择自定义日期")}
                            IconButton(onClick={model.refresh()},enabled=!model.busy){if(model.busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)else Icon(Icons.Outlined.Refresh,"补记与刷新")}
                        }
                        HorizontalDivider(color=colors.outlineVariant)
                        if(model.computing)LinearProgressIndicator(Modifier.fillMaxWidth())
                        (model.loadError?:model.syncError)?.let{error->
                            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp)){
                                Text(error,color=colors.error,style=MaterialTheme.typography.bodySmall)
                                Text("已保存的记录会继续尝试读取。",style=MaterialTheme.typography.bodySmall)
                                TextButton(onClick={model.refresh()}){Text("重新读取并补记")}
                            }
                        }
                        when {
                            s.page==3->SettingsScreen(model,onPermission={context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))},onExport={export.launch("用时记备份_${LocalDate.now()}.json")},onImport={import.launch(arrayOf("application/json","text/plain","application/gzip","application/x-gzip","application/octet-stream"))},onCsv={csv.launch("用时记报表_${LocalDate.now()}.csv")},onDiagnostics={scope.launch{
                                try{
                                    val cache=model.store.reportCacheStats()
                                    val timing="查询 ${"%.1f".format(model.lastLoadMillis)} ms · CPU 首绘 ${model.lastCpuDrawMillis?.let{"%.1f".format(it)}?:"待测量"} ms"
                                    val hits=cache.hits+cache.misses
                                    val summary="$timing\n缓存命中 ${cache.hits}/${hits}（${if(hits>0)"%.1f".format(cache.hits*100.0/hits)else"0.0"}%）· 条目 ${cache.entries} · 约 ${cache.estimatedBytes/1024} KiB"
                                    diagnosticText=(listOf(summary)+model.store.diagnostics().map{"${it.time} ${it.code}\n${it.detail}"}).joinToString("\n\n")
                                }
                                catch(e:kotlinx.coroutines.CancellationException){throw e}
                                catch(e:Exception){model.message="诊断暂时无法读取，请查看数据安全与占用：${e.message}"}
                            }},onRebuild={model.rebuildHistory()},onSafety={model.updateSafetyInfo();safetyDialog=true},onCompressedExport={compressedExport.launch("用时记备份_${LocalDate.now()}.json.gz")})
                            !model.permitted&&!model.demo&&model.display.first==null->PermissionScreen(model){context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))}
                            s.page==2->CompareScreen(model)
                            else->StatisticsScreen(model)
                        }
                    }
                }
            }
        }
    }
}
private fun datePicker(context:android.content.Context,initial:LocalDate,min:LocalDate?=null,onDate:(LocalDate)->Unit){
    val mode=context.getSharedPreferences("usage-settings",0).getString("theme","system")
    val isDark=mode=="dark"||(mode=="system"&&(context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES)
    val dialog=DatePickerDialog(context,if(isDark)R.style.DateDialogDark else R.style.DateDialogLight,{_,y,m,d->onDate(LocalDate.of(y,m+1,d))},initial.year,initial.monthValue-1,initial.dayOfMonth)
    dialog.datePicker.maxDate=System.currentTimeMillis()
    min?.let{dialog.datePicker.minDate=it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()}
    dialog.show()
}
private fun customDates(context:android.content.Context,model:UsageModel){
    datePicker(context,model.selection.anchor){first->datePicker(context,LocalDate.now(),first){last->model.change{it.copy(period=Period.CUSTOM,custom=first to last)}}}
}
@Composable private fun PermissionScreen(model:UsageModel,onGrant:()->Unit){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Spacer(Modifier.height(28.dp));Icon(Icons.Outlined.QueryStats,null,Modifier.size(44.dp),tint=MaterialTheme.colorScheme.primary)
        Text("开始记录你的用时",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
        Text("请在系统页面找到“用时记”，开启使用情况访问权限。",style=MaterialTheme.typography.bodyLarge)
        Text("授权后导入系统仍保留的近期记录，并持续保存到本机。更早的记录无法凭空恢复，图表会明确留空。",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick=onGrant,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("前往授权")}
        OutlinedButton(onClick={model.useDemo(true)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("先体验示例数据")}
        Text("无需账号 · 无联网权限 · 数据仅保存在本机",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun PeriodControls(model:UsageModel){
    val s=model.selection;val context=LocalContext.current
    val periods=if(s.detail)listOf(Period.TODAY,Period.WEEK,Period.MONTH,Period.ALL)else listOf(Period.TODAY,Period.WEEK,Period.DAYS7,Period.MONTH,Period.DAYS30,Period.ALL)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.SpaceBetween){periods.forEach{p->
        val short=when(p){Period.DAYS7->"7天";Period.DAYS30->"30天";Period.ALL->"全部";else->p.label}
        Column(horizontalAlignment=Alignment.CenterHorizontally){TextButton(onClick={model.period(p)},contentPadding=PaddingValues(horizontal=9.dp),modifier=Modifier.heightIn(min=48.dp)){Text(short,color=if(s.period==p)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,fontWeight=if(s.period==p)FontWeight.SemiBold else FontWeight.Normal)}
            HorizontalDivider(Modifier.width(36.dp),thickness=if(s.period==p)2.dp else 0.dp,color=if(s.period==p)MaterialTheme.colorScheme.primary else Color.Transparent)
        }
    }}
    val w=model.display.report?.window
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        IconButton(onClick={model.move(-1)},enabled=s.period!=Period.ALL&&s.period!=Period.CUSTOM){Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft,"上一周期")}
        TextButton(onClick={if(s.detail)datePicker(context,s.anchor){d->model.change{it.copy(anchor=d)}}else customDates(context,model)},modifier=Modifier.weight(1f)){Text(w?.let{windowLabel(it,model.zone)}?:"正在读取",color=MaterialTheme.colorScheme.onSurface,maxLines=2,style=MaterialTheme.typography.labelLarge)}
        val today=LocalDate.now();val end=when(s.period){Period.WEEK->Dates.week(s.anchor).plusWeeks(1);Period.MONTH->s.anchor.withDayOfMonth(1).plusMonths(1);else->s.anchor.plusDays(1)}
        IconButton(onClick={model.move(1)},enabled=s.period!=Period.ALL&&s.period!=Period.CUSTOM&&end<=today){Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,"下一周期")}
    }
    if(s.anchor<LocalDate.now()&&s.period!=Period.ALL&&s.period!=Period.CUSTOM)TextButton(onClick={model.change{it.copy(anchor=LocalDate.now())}}){Text("回到当前周期")}
    val report=model.display.report
    Text(if(report?.fullyCovered==false)"含推断或未知区间 · 不确定不计为零"else "截至 ${Instant.ofEpochMilli(model.now).atZone(model.zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=8.dp))
}
@Composable private fun StatisticsScreen(model:UsageModel){
    val s=model.selection;val r=model.display.report
    val apps=remember(r?.apps,s.query,s.nameSort,s.page,model.labelRevision){
        val filtered=r?.apps.orEmpty().filter{model.label(it.app).contains(s.query,true)||it.app.contains(s.query,true)}
        if(s.nameSort&&s.page==1)filtered.sortedBy{model.label(it.app)}else filtered
    }
    val reversedBuckets=remember(r?.buckets){r?.buckets.orEmpty().asReversed()}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=20.dp)){
        item{PeriodControls(model)}
        if(model.display.legacyAvailable)item{
            Text("当前范围有旧版记录，新指标不能直接替代旧算法结果。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={model.change{it.copy(legacy=true)}}){Text("显示此范围的旧版记录")}
        }
        if(r!=null){
            item{
                Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
                    Text(if(s.legacy)"旧版累计（未分级）"else if(!model.showConfirmed)"${s.metric.label} · 候选估计累计"else "${s.metric.label} · 已确认与候选估计",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(r.buckets.any{it.available})TimeValue(r.total)else Text("未记录",style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(bottom=14.dp),horizontalArrangement=Arrangement.SpaceBetween){
                    Text(if(s.period==Period.TODAY)"当天按小时统计"else "已记录日均 ${durationText(r.total / model.display.recordedDays.coerceAtLeast(1))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(s.detail)"占总用时 ${percent(r.total,model.display.all?.total?:0)}"else "${r.apps.size} 个应用",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                QualityStatus(r)
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(top=4.dp),verticalAlignment=Alignment.CenterVertically){Text("使用趋势",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);ChartSwitch(s.line){model.change{state->state.copy(line=it)}}}
                if(s.period==Period.ALL)Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf(Grain.DAY,Grain.WEEK,Grain.MONTH).forEach{g->FilterChip(selected=s.grain==g,onClick={model.change{it.copy(grain=g)}},label={Text(g.label)})}}
                UsageChart(r.buckets,line=s.line)
                if(r.buckets.none{it.available})Text(if((r.quality?.unknown?:0)>0)"此范围尚无法确定应用归属，请查看上方未知时长。"else"这个范围没有已闭合的使用记录",Modifier.padding(vertical=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.outlineVariant)
            }
            if(s.detail){
                item{SectionTitle("数据概览")
                    Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween){Metric("最多的时段",durationText(r.buckets.maxOfOrNull{it.millis}?:0));Metric("完整记录",if(r.fullyCovered)"已覆盖"else "部分记录")}
                    TextButton(onClick={model.change{it.copy(page=2,compareApp=s.app,monthly=s.period==Period.MONTH,compareA=s.anchor,compareB=if(s.period==Period.MONTH)s.anchor.minusMonths(1)else s.anchor.minusWeeks(1),detail=false)}}){Text("对比此应用的历史使用")}
                    SectionTitle("时长明细")
                }
                items(reversedBuckets,key={it.start}){b->Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text(b.label);Text(if(b.available)durationText(b.millis)else"未记录",color=MaterialTheme.colorScheme.onSurfaceVariant)}}
            }else{
                item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(s.page==0)"应用排行"else"${r.apps.size} 个应用",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold);Text("占总用时",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(s.page==0)TextButton(onClick={model.navigate(1)}){Text("全部")}}}
                if(s.page==1)item{OutlinedTextField(value=s.query,onValueChange={q->model.change{it.copy(query=q)}},label={Text("搜索应用")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(bottom=8.dp),leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(s.query.isNotEmpty())IconButton(onClick={model.change{it.copy(query="")}}){Icon(Icons.Outlined.Close,"清除搜索")}})
                    Row(verticalAlignment=Alignment.CenterVertically){Text("排序",style=MaterialTheme.typography.bodySmall);TextButton(onClick={model.change{it.copy(nameSort=!it.nameSort)}}){Text(if(s.nameSort)"按名称"else"按时长")}}
                }
                if(apps.isEmpty())item{Text(if(s.query.isNotEmpty())"没有找到对应应用，请换个名称。"else"暂无应用记录；授权后打开其他应用，再返回补记。",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(if(s.page==0)apps.take(8)else apps,key={it.app}){a->Column{AppRow(model,a.app,a.millis,r.total){model.openApp(a.app)};if(model.showConfirmed)r.appQuality[a.app]?.let{q->Text("确定 ${durationText(q.confirmed)} · 推断 ${durationText(q.inferred)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
            }
        }
    }
}
private fun percent(value:Long,total:Long)=if(total>0)"%.1f%%".format(java.util.Locale.CHINA,value.toDouble()/total*100)else"0%"
private fun windowLabel(w:Window,z:ZoneId):String {val a=Dates.date(w.start,z);val b=Dates.date((w.end-1).coerceAtLeast(w.start),z);val f=DateTimeFormatter.ofPattern(if(a.year!=LocalDate.now().year||a.year!=b.year)"yyyy.M.d"else"M月d日");return if(a==b)a.format(f)else "${a.format(f)} — ${b.format(f)}"}
@Composable private fun SectionTitle(text:String){Text(text,Modifier.padding(top=16.dp,bottom=8.dp),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
@Composable private fun Metric(label:String,value:String){Column{Text(label,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(value,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)}}
@Composable private fun TimeValue(ms:Long,small:Boolean=false){
    if(ms in 1..59999){Text("不足1分钟",style=MaterialTheme.typography.titleLarge);return}
    val mins=ms/60000;val h=mins/60;val m=mins%60
    Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(3.dp)){
        if(h>0){Text("$h",style=if(small)MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold);Text("小时",Modifier.padding(bottom=4.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Text("$m",style=if(small)MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold);Text("分",Modifier.padding(bottom=4.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun ChartSwitch(line:Boolean,onChange:(Boolean)->Unit){
    Row{TextButton(onClick={onChange(true)}){Text("折线",fontWeight=if(line)FontWeight.SemiBold else FontWeight.Normal,color=if(line)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)};TextButton(onClick={onChange(false)}){Text("柱状",fontWeight=if(!line)FontWeight.SemiBold else FontWeight.Normal,color=if(!line)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}
}
@Composable private fun AppRow(model:UsageModel,pkg:String,value:Long,total:Long,onClick:()->Unit){
    val label=model.label(pkg)
    var image by remember(pkg){mutableStateOf(model.cachedIcon(pkg))}
    LaunchedEffect(pkg){if(image==null)image=model.loadIcon(pkg)}
    val bitmap=image
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).heightIn(min=64.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
        if(bitmap!=null)Image(bitmap,null,Modifier.size(34.dp))else Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=RoundedCornerShape(8.dp)){Text(label.take(1),Modifier.padding(10.dp),color=MaterialTheme.colorScheme.primary)}
        Column(Modifier.weight(1f)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium);Text(durationText(value),style=MaterialTheme.typography.labelLarge)}
            Row(Modifier.fillMaxWidth().padding(top=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                LinearProgressIndicator(progress={if(total>0)(value.toFloat()/total).coerceIn(0f,1f)else 0f},modifier=Modifier.weight(1f).height(3.dp),gapSize=0.dp,drawStopIndicator={})
                Text(percent(value,total),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CompareScreen(model:UsageModel){
    val s=model.selection;val context=LocalContext.current;val r=model.display.report;val other=model.display.other
    val changes=remember(r?.apps,other?.apps){
        val a=r?.apps.orEmpty().associate{it.app to it.millis};val b=other?.apps.orEmpty().associate{it.app to it.millis}
        (a.keys+b.keys).map{it to ((a[it]?:0)-(b[it]?:0))}.sortedByDescending{kotlin.math.abs(it.second)}
    }
    var menu by remember{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=20.dp,vertical=8.dp)){
        item{
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                FilterChip(selected=!s.monthly,onClick={model.change{it.copy(monthly=false,compareA=LocalDate.now(),compareB=LocalDate.now().minusWeeks(1))}},label={Text("周对比")})
                Spacer(Modifier.width(8.dp));FilterChip(selected=s.monthly,onClick={model.change{it.copy(monthly=true,compareA=LocalDate.now(),compareB=LocalDate.now().minusMonths(1))}},label={Text("月对比")})
                Spacer(Modifier.weight(1f))
                Box{TextButton(onClick={menu=true}){Text(s.compareApp?.let{model.label(it)}?:"全部应用",maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=120.dp));Icon(Icons.Outlined.ArrowDropDown,null)}
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                        DropdownMenuItem(text={Text("全部应用")},onClick={model.change{it.copy(compareApp=null)};menu=false})
                        val packages=((model.display.all?:r)?.apps.orEmpty()+(model.display.allOther?:other)?.apps.orEmpty()).map{it.app}.distinct()
                        packages.forEach{pkg->DropdownMenuItem(text={Text(model.label(pkg))},onClick={model.change{it.copy(compareApp=pkg)};menu=false})}
                    }
                }
            }
            val unit=if(s.monthly)"月"else"周"
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                CompareDate("统计$unit",s.compareA,s.monthly,Modifier.weight(1f),false){datePicker(context,s.compareA){d->model.change{it.copy(compareA=d)}}}
                CompareDate("对照$unit",s.compareB,s.monthly,Modifier.weight(1f),true){datePicker(context,s.compareB){d->model.change{it.copy(compareB=d)}}}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                FilterChip(selected=s.same,onClick={model.change{it.copy(same=true)}},label={Text("同期进度")})
                Spacer(Modifier.width(8.dp));FilterChip(selected=!s.same,onClick={model.change{it.copy(same=false)}},label={Text("完整周期")})
            }
            Text(if(s.same)"按已过去的天数与时刻对齐"else"当前周期未结束时，完整周期比较存在进度差异",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=12.dp))
            if(r!=null&&other!=null){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Column(Modifier.weight(1f)){Text("统计$unit",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium);Text(if(r.buckets.any{it.available})durationText(r.total)else"未记录",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)}
                    Column(Modifier.weight(1f)){Text("对照$unit",color=MaterialTheme.colorScheme.secondary,style=MaterialTheme.typography.labelMedium);Text(if(other.buckets.any{it.available})durationText(other.total)else"未记录",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)}
                }
                QualityStatus(r,"统计周期");QualityStatus(other,"对照周期")
                val diff=r.total-other.total
                val canCompare=r.buckets.any{it.available}&&other.buckets.any{it.available}
                val partial=!r.fullyCovered||!other.fullyCovered
                Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().padding(top=10.dp,bottom=4.dp)){
                    FlowRow(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text(if(!canCompare)"记录不足，暂时无法比较"else if(diff==0L)"${if(partial)"可归属用时（含推断）"else"使用时长"}相同"else"${if(partial)"可归属用时（含推断）"else""}${if(diff<0)"少"else"多"} ${durationText(kotlin.math.abs(diff))}",fontWeight=FontWeight.SemiBold)
                        Text(if(!canCompare)"缺失不是零"else if(other.total>0)"%+.1f%%".format(java.util.Locale.CHINA,diff.toDouble()/other.total*100)else if(r.total>0)"新增使用"else"均为零",style=MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("统计$unit",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall);Spacer(Modifier.width(8.dp));Text("对照$unit · 虚线",color=MaterialTheme.colorScheme.secondary,style=MaterialTheme.typography.labelSmall);Spacer(Modifier.weight(1f));ChartSwitch(s.line){v->model.change{it.copy(line=v)}}}
                UsageChart(r.buckets,other.buckets,s.line)
                if(!r.fullyCovered||!other.fullyCovered)Text("含推断或未知区间；差异不代表确定的全部实际用时。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(top=12.dp),color=MaterialTheme.colorScheme.outlineVariant)
                SectionTitle("哪些应用变了")
            }
        }
        if(r!=null&&other!=null&&r.buckets.any{it.available}&&other.buckets.any{it.available}){
            if(changes.isEmpty())item{Text("这两个周期还没有已保存的使用记录。",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(changes,key={it.first}){(pkg,diff)->
                Row(Modifier.fillMaxWidth().clickable{model.openApp(pkg)}.heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){Text(model.label(pkg),Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);Text("${if(diff>0)"+"else if(diff<0)"−"else""}${durationText(kotlin.math.abs(diff))}",style=MaterialTheme.typography.bodyMedium);Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(18.dp))}
            }
        }
    }
}
@Composable private fun CompareDate(label:String,date:LocalDate,month:Boolean,modifier:Modifier,other:Boolean,onClick:()->Unit){
    val full=if(month)Dates.fullMonth(date,ZoneId.systemDefault())else Dates.fullWeek(date,ZoneId.systemDefault())
    val scheme=MaterialTheme.colorScheme;val tint=if(other)scheme.secondary else scheme.primary
    Surface(onClick=onClick,modifier=modifier.heightIn(min=64.dp),shape=MaterialTheme.shapes.small,color=tint.copy(alpha=.08f),border=BorderStroke(1.dp,tint.copy(alpha=.18f))){
        Column(Modifier.padding(10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),color=tint,style=MaterialTheme.typography.labelMedium);Icon(Icons.Outlined.CalendarMonth,null,Modifier.size(16.dp),tint=tint)}
            Text(if(month)date.format(DateTimeFormatter.ofPattern("yyyy年M月"))else windowLabel(full,ZoneId.systemDefault()),Modifier.padding(top=3.dp),style=MaterialTheme.typography.bodySmall,color=scheme.onSurfaceVariant)
        }
    }
}
@Composable private fun SettingsScreen(model:UsageModel,onPermission:()->Unit,onExport:()->Unit,onImport:()->Unit,onCsv:()->Unit,onDiagnostics:()->Unit,onRebuild:()->Unit,onSafety:()->Unit,onCompressedExport:()->Unit){
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        if(model.demo)Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.padding(16.dp)){Text("正在体验示例数据",fontWeight=FontWeight.SemiBold);Text("示例不会写入真实记录数据库。",style=MaterialTheme.typography.bodySmall);TextButton(onClick={model.useDemo(false);model.refresh()}){Text("切换真实记录")}}}
        SectionTitle("记录与统计")
        MetricControls(model)
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text("显示确定时间",style=MaterialTheme.typography.bodyLarge)
                Text("控制累计是否计入已确认时间；未知与暂定区间始终展示",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=model.showConfirmed,onCheckedChange={model.showConfirmed(it)})
        }
        SettingItem("采集诊断","查看事件缺口、时钟和重试记录",onDiagnostics)
        SettingItem("重算已保存历史","只重算已有事件；保留旧版与导入历史",onRebuild,enabled=!model.demo)
        SettingItem("使用情况访问",if(model.permitted)"已授权"else"尚未授权",onPermission)
        Row(Modifier.fillMaxWidth().heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("自动补记",style=MaterialTheme.typography.bodyLarge);Text("约每6小时保存，打开时补记",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(checked=model.recording,onCheckedChange={model.enableRecording(it)})}
        model.display.last?.let{Text("最近保存：${Instant.ofEpochMilli(it).atZone(model.zone).format(DateTimeFormatter.ofPattern("yyyy.M.d HH:mm"))}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Text("${model.zone.id} · 每周从周一开始",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("后台任务受系统省电调度影响；长时间未补记或权限中断时，部分记录可能无法恢复。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(top=8.dp));SectionTitle("数据管理")
        SettingItem("数据安全与占用","查看占用、本机安全副本和恢复",onSafety,enabled=!model.demo)
        model.safetyInfo.warning?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
        SettingItem("导出完整备份","JSON · 保存已记录历史",onExport,enabled=!model.demo)
        SettingItem("导出压缩完整备份","JSON.GZ · 适合长期归档",onCompressedExport,enabled=!model.demo)
        SettingItem("导入备份","JSON / JSON.GZ · 合并历史，保留当前记录",onImport)
        SettingItem("导出当前报表","CSV · 当前范围的应用时长",onCsv,enabled=!model.demo)
        HorizontalDivider();SectionTitle("外观")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("system" to "跟随系统","light" to "浅色","dark" to "深色").forEach{(key,label)->FilterChip(selected=model.appearance==key,onClick={model.theme(key)},label={Text(label)})}}
        HorizontalDivider(Modifier.padding(top=8.dp));SectionTitle("关于用时记")
        Text("主要使用与屏幕可见是两个独立指标。其他应用的焦点及真实窗口遮挡无法由公开UsageEvents完全确认；单resumed仅作推断，多resumed无焦点证据时主要归属未知。桌面与系统界面排除。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("无账号，无联网权限。记录保存在本机；卸载会删除本地数据，请先导出备份。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!model.demo)TextButton(onClick={model.useDemo(true)}){Text("体验示例数据")}
        Text("用时记 ${BuildConfig.VERSION_NAME}",Modifier.padding(vertical=16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun SettingItem(title:String,subtitle:String,onClick:()->Unit,enabled:Boolean=true){
    Row(Modifier.fillMaxWidth().clickable(enabled=enabled,onClick=onClick).heightIn(min=64.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,color=if(enabled)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(20.dp))}
}

@Composable private fun MetricControls(model:UsageModel){
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        UsageMetric.entries.forEach{m->FilterChip(selected=model.selection.metric==m&&!model.selection.legacy,onClick={model.change{it.copy(metric=m,legacy=false)}},label={Text(m.label)})}
        FilterChip(selected=model.selection.legacy,onClick={model.change{it.copy(legacy=true)}},label={Text("旧版历史（未分级）")})
    }
    if(model.selection.legacy)Text("保留的旧算法记录；不能恢复为确定的双指标。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    else Text(if(model.selection.metric==UsageMetric.PRIMARY)"多应用同时resumed且无焦点证据时，归属为未知。"else"不同应用允许重叠；生命周期候选不等于已证明的窗口可见。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun QualityStatus(report:UsageReport,label:String="数据可信状态"){
    var showHelp by remember(label){mutableStateOf(false)}
    report.quality?.let{q->Column(Modifier.fillMaxWidth().padding(bottom=10.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Text(label,style=MaterialTheme.typography.labelMedium)
            TextButton(onClick={showHelp=true},contentPadding=PaddingValues(8.dp,0.dp),modifier=Modifier.heightIn(min=32.dp).semantics{contentDescription="查看${label}说明"}){
                Text("?",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold)
            }
        }
        Text("已确认 ${durationText(q.confirmed)} · 候选估计 ${durationText(q.inferred)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("无法确定 ${durationText(q.unknown)} · 未闭合 ${durationText(q.provisional)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
    if(showHelp)AlertDialog(onDismissRequest={showHelp=false},title={Text("${label}说明")},
        text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("已确认：有足够证据，可直接计入累计的时长。")
            Text("候选估计：根据应用生命周期推断，但尚未证明为实际使用的时长。")
            Text("无法确定：证据不足，暂时无法归属到具体应用的时长。")
            Text("未闭合：当前周期尚未结算的区间，不计入累计。")
            Text("候选估计可能与无法确定区间重叠，不能相加；候选也不代表已证明的实际用时。",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }},
        confirmButton={TextButton(onClick={showHelp=false}){Text("知道了")}})
}

@Composable private fun StorageSafetyDialog(model:UsageModel,onDismiss:()->Unit){
    var action by remember{mutableStateOf<Int?>(null)}
    var selectedTime by remember{mutableStateOf<Long?>(null)}
    val info=model.safetyInfo
    fun size(bytes:Long)="%.1f MB".format(bytes/1048576.0)
    val date=info.latest?.let{Instant.ofEpochMilli(it).atZone(model.zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}?:"尚无"
    if(action!=null){
        val restoreDate=selectedTime?.let{Instant.ofEpochMilli(it).atZone(model.zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm:ss"))}?:date
        val selected=action!!
        AlertDialog(onDismissRequest={action=null},title={Text(when(selected){1->"恢复本机安全副本";2->"撤销上次恢复";else->"删除恢复前副本"})},
            text={Text(when(selected){1->"将恢复到 $restoreDate 的副本。当前数据库会保留以供撤销，较新的使用事件需要重新补记。";2->"将切回上次恢复前的数据，当前数据库也会保留。只有通过校验才会切换。";else->"将永久删除上次恢复前的副本，之后无法撤销该次恢复。其中可能有当前库没有的较新记录，请先确认已另行保存。当前使用记录和安全副本不会删除。"})},
            confirmButton={TextButton(onClick={model.safetyAction(selected,selectedTime);onDismiss()}){Text("确认")}},dismissButton={TextButton(onClick={action=null}){Text("取消")}})
    }else AlertDialog(onDismissRequest=onDismiss,title={Text("数据安全与占用")},text={Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState())){
        Text("数据库及恢复文件：${size(info.databaseBytes)}")
        Text("安全副本：${info.snapshots} 份 · ${size(info.snapshotBytes)}")
        Text("设备可用空间：${size(info.freeBytes)}")
        Text("最近安全副本：$date")
        val exported=info.lastExport?.let{Instant.ofEpochMilli(it).atZone(model.zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}?:"尚未记录"
        Text("上次导出完整备份：$exported")
        Text("约每12小时成功补记后创建校验副本，保留两份，优先保留不同版本；实际执行受系统调度影响。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=8.dp))
        Text("本机副本也会随卸载或清除数据而删除。请定期将完整备份导出到应用外。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=8.dp))
        info.warning?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        TextButton(enabled=!model.busy,onClick={model.safetyAction(0);onDismiss()}){Text("立即创建安全副本")}
        info.copies.forEachIndexed{index,time->
            val label=Instant.ofEpochMilli(time).atZone(model.zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm:ss"))
            TextButton(enabled=!model.busy&&!info.canUndo,onClick={selectedTime=time;action=1}){Text("恢复${if(index==0)"最新"else"上一份"}副本 · $label")}
        }
        if(info.canUndo){
            TextButton(enabled=!model.busy,onClick={action=2}){Text("撤销上次恢复")}
            TextButton(enabled=!model.busy,onClick={action=3}){Text("删除恢复前副本")}
        }
    }},confirmButton={TextButton(onClick=onDismiss){Text("关闭")}})
}
