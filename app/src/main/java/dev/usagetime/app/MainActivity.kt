package dev.usagetime.app

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.usagetime.core.Dates
import dev.usagetime.core.Grain
import dev.usagetime.core.Period
import dev.usagetime.core.ClockSampling
import dev.usagetime.core.Signal
import dev.usagetime.core.UsageMetric
import dev.usagetime.core.UsageReport
import dev.usagetime.core.Window
import dev.usagetime.core.durationText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val observationId = java.util.UUID.randomUUID().toString()

    private fun observe(signal: Signal) {
        val app = application as UsageTimeApplication
        val clock = ClockSampling.capture(
            System::currentTimeMillis,
            android.os.SystemClock::elapsedRealtime,
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1).toString()
        )
        val at = clock.wall
        val interactive = getSystemService(android.os.PowerManager::class.java).isInteractive
        val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        val activityName = this@MainActivity.javaClass.name
        app.observationScope.launch {
            runCatching {
                app.store.ownEvent(
                    signal,
                    activityName,
                    "${app.processSession}/$observationId",
                    at,
                    interactive,
                    locked,
                    app.processSession,
                    app.processStartedAt,
                    clock
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        observe(Signal.RESUME)
    }

    override fun onPause() {
        observe(Signal.PAUSE)
        super.onPause()
    }

    override fun onStop() {
        observe(Signal.STOP)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        observe(if (hasFocus) Signal.FOCUS_GAIN else Signal.FOCUS_LOSS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        widgetNavigation = widgetNavigation(intent)
        setContent { UsageApp(widgetNavigation = widgetNavigation) }
    }

    private var widgetNavigation by mutableStateOf<WidgetNavigation?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        widgetNavigation = widgetNavigation(intent)
    }

    private fun widgetNavigation(intent: Intent): WidgetNavigation? {
        if (!intent.hasExtra(UsageWidgetProvider.EXTRA_PAGE)) return null
        return WidgetNavigation(
            page = intent.getIntExtra(UsageWidgetProvider.EXTRA_PAGE, 0).let { if (it == 1) 1 else 0 },
            app = intent.getStringExtra(UsageWidgetProvider.EXTRA_APP),
            week = intent.getBooleanExtra(UsageWidgetProvider.EXTRA_WEEK, false),
            request = System.nanoTime()
        )
    }
}

data class WidgetNavigation(val page: Int, val app: String?, val week: Boolean, val request: Long)

@Composable
fun UsageApp(model: UsageModel = viewModel(), widgetNavigation: WidgetNavigation? = null) {
    var safetyDialog by remember { mutableStateOf(false) }
    var diagnosticText by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = model.appearance == "dark" || (model.appearance == "system" && systemDark)

    LaunchedEffect(widgetNavigation) {
        widgetNavigation?.let { destination ->
            if (model.demo) model.useDemo(false)
            model.change { it.copy(
                page = destination.page,
                anchor = Dates.date(System.currentTimeMillis(), model.zone),
                period = if (destination.week) dev.usagetime.core.Period.DAYS7 else dev.usagetime.core.Period.TODAY,
                detail = destination.app != null, app = destination.app, query = ""
            ) }
        }
    }

    SideEffect {
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (dark) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            },
            navigationBarStyle = if (dark) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            }
        )
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            BackupDocumentCoordinator.exportBackup(
                write = {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { model.store.writeBackup(it) }
                            ?: error("无法写入文件")
                    }
                },
                markExported = model::fullBackupExported,
                message = { model.message = it },
                success = "备份已导出",
                failurePrefix = "导出失败："
            )
        }
    }
    val compressedExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri != null) scope.launch {
            BackupDocumentCoordinator.exportBackup(
                write = {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { model.store.writeCompressedBackup(it) }
                            ?: error("无法写入文件")
                    }
                },
                markExported = model::fullBackupExported,
                message = { model.message = it },
                success = "压缩完整备份已导出",
                failurePrefix = "导出未完成，请保留此前副本："
            )
        }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            try {
                val snapshot = model.exportReport()
                withContext(Dispatchers.IO) {
                    val body = CsvReportFormat.build(snapshot, model::label)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                        ?: error("无法写入文件")
                }
                model.message = "报表已导出"
            } catch (e: Exception) {
                model.message = "导出失败：${e.message}"
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            BackupDocumentCoordinator.importBackup(
                read = {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { model.store.importBackup(it) }
                            ?: error("无法读取文件")
                    }
                },
                loadNames = { model.store.loadNames() },
                updateSafety = model::updateSafetyInfo,
                useDemo = model::useDemo,
                reload = model::reload,
                message = { model.message = it }
            )
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refresh()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(lifecycle, model) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(60_000)
                model.refreshTimeContext()
            }
        }
    }
    LaunchedEffect(model.message) {
        model.message?.let {
            snack.showSnackbar(it)
            model.message = null
        }
    }
    BackHandler(model.selection.detail) { model.navigate(1) }

    HindsightTheme(dark = dark) {
        val c = hindsightColors()
        if (safetyDialog) StorageSafetyDialog(model) { safetyDialog = false }
        diagnosticText?.let { text ->
            AlertDialog(
                onDismissRequest = { diagnosticText = null },
                title = { Text("采集诊断（仅本机）") },
                text = {
                    Text(
                        text,
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = { TextButton(onClick = { diagnosticText = null }) { Text("关闭") } }
            )
        }

        val s = model.selection
        val rendered = model.display
        val navItems = listOf("总览", "应用", "对比", "分析", "设置")
        val navPages = listOf(0, 1, 2, 4, 3)
        val navIcons = listOf(Icons.Outlined.QueryStats, Icons.Outlined.Apps, Icons.Outlined.BarChart, Icons.Outlined.CalendarMonth, Icons.Outlined.Settings)

        HindsightBackdrop {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        model.reportDrawn(rendered)
                    }
            ) {
                val wide = maxWidth >= 720.dp
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = { SnackbarHost(snack) },
                    bottomBar = {
                        if (!wide) {
                            HindsightBottomNavigation(
                                labels = navItems,
                                icons = navIcons,
                                selected = navPages.indexOf(s.page),
                                onNavigate = { model.navigate(navPages[it]) }
                            )
                        }
                    }
                ) { padding ->
                    Row(Modifier.fillMaxSize().padding(padding)) {
                        if (wide) {
                            HindsightNavigationRail(
                                labels = navItems,
                                icons = navIcons,
                                selected = navPages.indexOf(s.page),
                                onNavigate = { model.navigate(navPages[it]) }
                            )
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .statusBarsPadding()
                                .widthIn(max = 900.dp)
                        ) {
                            HindsightToolbar(
                                model = model,
                                showCalendar = s.page < 2,
                                onCalendar = { customDates(context, model) },
                                onRefresh = model::refresh
                            )
                            if (model.computing) {
                                LinearProgressIndicator(
                                    Modifier.fillMaxWidth().height(2.dp),
                                    color = c.accent,
                                    trackColor = Color.Transparent
                                )
                            }
                            (model.loadError ?: model.syncError)?.let { error ->
                                ErrorBanner(
                                    error = error,
                                    onRetry = model::refresh
                                )
                            }
                            when {
                                s.page == 3 -> SettingsScreen(
                                    model = model,
                                    onPermission = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                                    onExport = { export.launch("用时记备份_${LocalDate.now()}.json") },
                                    onImport = {
                                        import.launch(
                                            arrayOf(
                                                "application/json",
                                                "text/plain",
                                                "application/gzip",
                                                "application/x-gzip",
                                                "application/octet-stream"
                                            )
                                        )
                                    },
                                    onCsv = { csv.launch("用时记报表_${LocalDate.now()}.csv") },
                                    onDiagnostics = {
                                        scope.launch {
                                            try {
                                                val cache = model.store.reportCacheStats()
                                                val timing = "查询 ${"%.1f".format(model.lastLoadMillis)} ms · CPU 首绘 ${
                                                    model.lastCpuDrawMillis?.let { "%.1f".format(it) } ?: "待测量"
                                                } ms"
                                                val hits = cache.hits + cache.misses
                                                val summary = "$timing\n缓存命中 ${cache.hits}/${hits}（${
                                                    if (hits > 0) "%.1f".format(cache.hits * 100.0 / hits) else "0.0"
                                                }%）· 条目 ${cache.entries} · 约 ${cache.estimatedBytes / 1024} KiB"
                                                diagnosticText = (listOf(summary) + model.store.diagnostics().map {
                                                    "${it.time} ${it.code}\n${it.detail}"
                                                }).joinToString("\n\n")
                                            } catch (e: kotlinx.coroutines.CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                model.message = "诊断暂时无法读取，请查看数据安全与占用：${e.message}"
                                            }
                                        }
                                    },
                                    onRebuild = model::rebuildHistory,
                                    onSafety = {
                                        model.updateSafetyInfo()
                                        safetyDialog = true
                                    },
                                    onCompressedExport = {
                                        compressedExport.launch("用时记备份_${LocalDate.now()}.json.gz")
                                    }
                                )
                                !model.permitted && !model.demo && model.display.first == null -> PermissionScreen(model) {
                                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                }
                                s.page == 4 -> InsightsScreen(model)
                                s.page == 2 -> CompareScreen(model)
                                else -> StatisticsScreen(model)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HindsightToolbar(
    model: UsageModel,
    showCalendar: Boolean,
    onCalendar: () -> Unit,
    onRefresh: () -> Unit
) {
    val c = hindsightColors()
    val headerTop = c.accentSoft.copy(alpha = if (c.dark) 0.5f else 0.55f).compositeOver(c.canvas)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(headerTop, c.canvas)))
            .heightIn(min = 58.dp)
            .padding(start = 18.dp, end = 14.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark(34.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "用时记",
            style = MaterialTheme.typography.titleMedium,
            color = c.textStrong,
            fontWeight = FontWeight.SemiBold
        )
        if (model.demo) {
            Spacer(Modifier.width(8.dp))
            HindsightBadge("示例数据")
        }
        Spacer(Modifier.weight(1f))
        if (showCalendar) {
            GlassIconButton(Icons.Outlined.CalendarMonth, "选择自定义日期", onCalendar)
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.control)
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .clickable(enabled = !model.busy, onClick = onRefresh),
            contentAlignment = Alignment.Center
        ) {
            if (model.busy) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = c.accent
                )
            } else {
                Icon(
                    Icons.Outlined.Refresh,
                    "补记与刷新",
                    tint = c.textRegular,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun HindsightBottomNavigation(
    labels: List<String>,
    icons: List<ImageVector>,
    selected: Int,
    onNavigate: (Int) -> Unit
) {
    val c = hindsightColors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(26.dp),
        color = c.nav,
        border = BorderStroke(1.dp, c.border),
        shadowElevation = 18.dp
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.height(68.dp)
        ) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                NavigationBarItem(
                    selected = active,
                    onClick = { onNavigate(index) },
                    icon = {
                        Box(
                            Modifier
                                .width(44.dp)
                                .height(29.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) c.accentSoft else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                icons[index],
                                contentDescription = null,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    },
                    label = {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.accent,
                        selectedTextColor = c.accent,
                        unselectedIconColor = c.textFaint,
                        unselectedTextColor = c.textFaint,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    }
}

@Composable
private fun HindsightNavigationRail(
    labels: List<String>,
    icons: List<ImageVector>,
    selected: Int,
    onNavigate: (Int) -> Unit
) {
    val c = hindsightColors()
    Surface(
        modifier = Modifier
            .statusBarsPadding()
            .padding(14.dp)
            .width(92.dp)
            .fillMaxHeight(),
        shape = RoundedCornerShape(26.dp),
        color = c.nav,
        border = BorderStroke(1.dp, c.border),
        shadowElevation = 16.dp
    ) {
        NavigationRail(
            containerColor = Color.Transparent,
            header = {
                Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                    BrandMark(38.dp)
                }
            }
        ) {
            Spacer(Modifier.height(8.dp))
            labels.forEachIndexed { index, label ->
                val active = selected == index
                NavigationRailItem(
                    selected = active,
                    onClick = { onNavigate(index) },
                    icon = {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) c.accentSoft else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icons[index], null, modifier = Modifier.size(20.dp))
                        }
                    },
                    label = {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                        selectedIconColor = c.accent,
                        selectedTextColor = c.accent,
                        unselectedIconColor = c.textFaint,
                        unselectedTextColor = c.textFaint,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: String, onRetry: () -> Unit) {
    val c = hindsightColors()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = c.danger.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, c.danger.copy(alpha = 0.16f))
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = c.danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = c.textStrong)
                Text("已保存的记录会继续尝试读取。", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            }
            TextButton(onClick = onRetry) { Text("重试", color = c.danger) }
        }
    }
}

private fun datePicker(
    context: android.content.Context,
    initial: LocalDate,
    min: LocalDate? = null,
    onDate: (LocalDate) -> Unit
) {
    val mode = context.getSharedPreferences("usage-settings", 0).getString("theme", "system")
    val isDark = mode == "dark" || (mode == "system" &&
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES)
    val dialog = DatePickerDialog(
        context,
        if (isDark) R.style.DateDialogDark else R.style.DateDialogLight,
        { _, y, m, d -> onDate(LocalDate.of(y, m + 1, d)) },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth
    )
    dialog.datePicker.maxDate = System.currentTimeMillis()
    min?.let { dialog.datePicker.minDate = it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    dialog.show()
}

private fun customDates(context: android.content.Context, model: UsageModel) {
    datePicker(context, model.selection.anchor) { first ->
        datePicker(context, LocalDate.now(), first) { last ->
            model.change { it.copy(period = Period.CUSTOM, custom = first to last) }
        }
    }
}

@Composable
private fun PermissionScreen(model: UsageModel, onGrant: () -> Unit) {
    val c = hindsightColors()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HindsightCard(
            background = Brush.linearGradient(
                listOf(c.accentSoft.copy(alpha = 0.9f), c.peachSoft.copy(alpha = 0.8f))
            ),
            contentPadding = 24.dp
        ) {
            BrandMark(52.dp)
            Spacer(Modifier.height(22.dp))
            Text(
                "开始记录你的用时",
                style = MaterialTheme.typography.headlineLarge,
                color = c.textStrong
            )
            Spacer(Modifier.height(9.dp))
            Text(
                "请在系统页面找到“用时记”，开启使用情况访问权限。",
                style = MaterialTheme.typography.bodyLarge,
                color = c.textRegular
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "授权后会导入系统仍保留的近期记录，并持续保存到本机。更早的记录无法凭空恢复，图表会明确留空。",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HindsightBadge("无需账号")
                HindsightBadge("无联网权限")
            }
        }
        Button(
            onClick = onGrant,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)
        ) {
            Text("前往授权", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = { model.useDemo(true) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, c.accent.copy(alpha = 0.28f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent)
        ) {
            Text("先体验示例数据", fontWeight = FontWeight.SemiBold)
        }
        Text(
            "无需账号 · 无联网权限 · 数据仅保存在本机",
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = c.textFaint,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StatisticsScreen(model: UsageModel) {
    val s = model.selection
    val r = model.display.report
    val listState = rememberLazyListState()
    LaunchedEffect(s.page, s.detail, s.app) {
        listState.scrollToItem(0)
    }
    val apps = remember(r?.apps, s.query, s.nameSort, s.page, model.labelRevision) {
        val filtered = r?.apps.orEmpty().filter {
            model.label(it.app).contains(s.query, true) || it.app.contains(s.query, true)
        }
        if (s.nameSort && s.page == 1) filtered.sortedBy { model.label(it.app) } else filtered
    }
    val reversedBuckets = remember(r?.buckets) { r?.buckets.orEmpty().asReversed() }
    val periods = if (s.detail) {
        listOf(Period.TODAY, Period.WEEK, Period.MONTH, Period.ALL)
    } else {
        listOf(Period.TODAY, Period.WEEK, Period.DAYS7, Period.MONTH, Period.DAYS30, Period.ALL)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            if (s.detail) {
                DetailHeader(
                    model = model,
                    pkg = s.app.orEmpty(),
                    subtitle = if (r != null) {
                        "占总用时 ${percentOf(r.total, model.display.all?.total ?: 0)} · ${periodShortLabel(s.period)}"
                    } else {
                        "正在读取应用记录"
                    }
                )
            } else if (s.page == 0) {
                HindsightPageTitle(
                    title = if (s.period == Period.TODAY && s.anchor != LocalDate.now()) {
                        s.anchor.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
                    } else periodTitle(s.period),
                    subtitle = periodSubtitle(model, r),
                    eyebrow = "使用时间"
                )
            } else {
                HindsightPageTitle(
                    title = "应用",
                    subtitle = if (r != null) {
                        "${r.apps.size} 个应用 · ${durationText(r.total)}"
                    } else {
                        "正在读取应用记录"
                    },
                    eyebrow = "全部应用"
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                periods.forEach { period ->
                    HindsightChip(
                        label = periodShortLabel(period),
                        selected = s.period == period,
                        onClick = { model.period(period) }
                    )
                }
            }
        }

        if (model.display.legacyAvailable) {
            item {
                HindsightCard(contentPadding = 16.dp) {
                    Text(
                        "当前范围有旧版记录，新指标不能直接替代旧算法结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = hindsightColors().textMuted
                    )
                    TextButton(
                        onClick = { model.change { it.copy(legacy = true) } },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("显示此范围的旧版记录")
                    }
                }
            }
        }

        if (r != null) {
            if (!s.detail) item { CategorySummaryCard(model, r) }
            if (s.page == 1 && !s.detail) {
                item { SearchAndSortCard(model) }
                if (apps.isEmpty()) {
                    item {
                        EmptyHint(
                            if (s.query.isNotEmpty()) "没有找到对应应用，请换个名称。"
                            else "暂无应用记录；授权后打开其他应用，再返回补记。"
                        )
                    }
                }
                itemsIndexed(apps.chunked(8)) { chunkIndex, chunk ->
                    HindsightCard(contentPadding = 12.dp) {
                        chunk.forEachIndexed { offset, app ->
                            if (offset > 0) HindsightDivider()
                            RankedAppRow(
                                rank = chunkIndex * 8 + offset + 1,
                                model = model,
                                pkg = app.app,
                                value = app.millis,
                                total = r.total,
                                confirmed = if (model.showConfirmed) r.appQuality[app.app]?.confirmed else null,
                                inferred = if (model.showConfirmed) r.appQuality[app.app]?.inferred else null,
                                onClick = { model.openApp(app.app) }
                            )
                        }
                    }
                }
            } else {
                item { SummaryCard(model, r) }
                item { UsageTrendCard(model, r) }
            }
            if (s.detail) {
                item {
                    HindsightCard {
                        HindsightCardTitle("数据概览")
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            MiniStat("峰值时段", durationText(r.buckets.maxOfOrNull { it.millis } ?: 0))
                            MiniStat("记录覆盖", if (r.fullyCovered) "已覆盖" else "部分记录")
                            MiniStat("应用占比", percentOf(r.total, model.display.all?.total ?: 0))
                        }
                        Spacer(Modifier.height(14.dp))
                        TextButton(
                            onClick = {
                                model.change {
                                    it.copy(
                                        page = 2,
                                        compareApp = s.app,
                                        monthly = s.period == Period.MONTH,
                                        compareA = s.anchor,
                                        compareB = if (s.period == Period.MONTH) {
                                            s.anchor.minusMonths(1)
                                        } else {
                                            s.anchor.minusWeeks(1)
                                        },
                                        detail = false
                                    )
                                }
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("对比这个应用的历史使用")
                        }
                    }
                }
                item {
                    SectionLabel("时长明细")
                }
                itemsIndexed(reversedBuckets.chunked(10)) { _, chunk ->
                    HindsightCard(contentPadding = 14.dp) {
                        chunk.forEachIndexed { index, bucket ->
                            if (index > 0) HindsightDivider()
                            Row(
                                Modifier.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    bucket.label,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = hindsightColors().textStrong
                                )
                                Text(
                                    if (bucket.available) durationText(bucket.millis) else "未记录",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (bucket.available) hindsightColors().textStrong else hindsightColors().textFaint,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            } else if (s.page != 1) {
                    item {
                        HindsightCard {
                            HindsightCardTitle(
                                title = "常用应用",
                                subtitle = "${r.apps.size} 个应用 · 按使用时长排序",
                                trailing = {
                                    TextButton(
                                        onClick = { model.navigate(1) },
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) {
                                        Text("查看全部", color = hindsightColors().accent)
                                    }
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                            apps.take(7).forEachIndexed { index, app ->
                                if (index > 0) HindsightDivider()
                                RankedAppRow(
                                    rank = index + 1,
                                    model = model,
                                    pkg = app.app,
                                    value = app.millis,
                                    total = r.total,
                                    confirmed = if (model.showConfirmed) r.appQuality[app.app]?.confirmed else null,
                                    inferred = if (model.showConfirmed) r.appQuality[app.app]?.inferred else null,
                                    onClick = { model.openApp(app.app) }
                                )
                            }
                        }
                    }
            }
        } else {
            item {
                EmptyHint("正在读取已保存的记录…")
            }
        }
    }
}

@Composable
private fun DetailHeader(model: UsageModel, pkg: String, subtitle: String) {
    val c = hindsightColors()
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(
            Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "返回应用列表",
            onClick = { model.navigate(1) }
        )
        Spacer(Modifier.width(10.dp))
        AppIconTile(model, pkg, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                model.label(pkg),
                style = MaterialTheme.typography.headlineMedium,
                color = c.textStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            CategoryPicker(model, pkg)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SummaryCard(model: UsageModel, report: UsageReport) {
    val c = hindsightColors()
    val s = model.selection
    val label = when {
        s.legacy -> "旧版累计（未分级）"
        model.showConfirmed -> "${s.metric.label} · 已确认与候选估计"
        else -> "${s.metric.label} · 候选估计累计"
    }
    HindsightCard(
        background = Brush.linearGradient(
            listOf(
                c.accentSoft.copy(alpha = if (c.dark) 0.76f else 0.9f),
                c.peachSoft.copy(alpha = if (c.dark) 0.6f else 0.75f)
            )
        ),
        contentPadding = 20.dp
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textMuted
                )
                Spacer(Modifier.height(7.dp))
                BigTimeValue(
                    millis = if (report.buckets.any { it.available }) report.total else 0L,
                    large = true,
                    available = report.buckets.any { it.available }
                )
            }
            HindsightBadge(
                text = when {
                    !report.fullyCovered -> "部分记录"
                    report.quality?.unknown?.let { it > 0 } == true -> "含未知区间"
                    else -> "记录完整"
                },
                tint = if (report.fullyCovered) c.success else c.warning,
                background = if (report.fullyCovered) {
                    c.success.copy(alpha = 0.1f)
                } else {
                    c.warning.copy(alpha = 0.12f)
                }
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MiniStat(
                if (s.period == Period.TODAY) "按小时统计" else "已记录日均",
                if (s.period == Period.TODAY) "${report.buckets.count { it.available }} 个时段"
                else durationText(report.total / model.display.recordedDays.coerceAtLeast(1))
            )
            MiniStat(
                if (s.detail) "记录天数" else "应用",
                if (s.detail) "${model.display.recordedDays} 天" else "${report.apps.size}"
            )
            MiniStat(
                if (s.detail) "占总用时" else "记录范围",
                if (s.detail) percentOf(report.total, model.display.all?.total ?: 0)
                else windowLabel(report.window, model.zone)
            )
        }
        report.quality?.let { quality ->
            Spacer(Modifier.height(14.dp))
            HindsightDivider()
            Spacer(Modifier.height(11.dp))
            QualityStatus(report)
        }
    }
}

@Composable
private fun UsageTrendCard(model: UsageModel, report: UsageReport) {
    val c = hindsightColors()
    val s = model.selection
    val context = LocalContext.current
    HindsightCard {
        HindsightCardTitle(
            title = "使用趋势",
            subtitle = windowLabel(report.window, model.zone)
        )
        Spacer(Modifier.height(10.dp))
        UnderlineTabs(
            labels = listOf("柱状", "折线"),
            selected = if (s.line) 1 else 0,
            onSelect = { index -> model.change { it.copy(line = index == 1) } }
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DateNavigator(
                model = model,
                modifier = Modifier.weight(1f),
                onPick = {
                    if (s.detail) {
                        datePicker(context, s.anchor) { date -> model.change { it.copy(anchor = date) } }
                    } else {
                        customDates(context, model)
                    }
                }
            )
        }
        if (s.period == Period.ALL) {
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(Grain.DAY, Grain.WEEK, Grain.MONTH).forEach { grain ->
                    HindsightChip(
                        label = grain.label,
                        selected = s.grain == grain,
                        onClick = { model.change { it.copy(grain = grain) } }
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        UsageChart(report.buckets, line = s.line)
        Spacer(Modifier.height(12.dp))
        HindsightDivider()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot("本期使用", c.accent)
            Spacer(Modifier.width(16.dp))
            Text(
                if (report.fullyCovered) "缺口与未来时段保持空白" else "含推断或未知区间 · 缺口不画成零",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = c.textFaint,
                textAlign = TextAlign.End
            )
        }
        if (s.anchor < LocalDate.now() && s.period != Period.ALL && s.period != Period.CUSTOM) {
            TextButton(
                onClick = { model.change { it.copy(anchor = LocalDate.now()) } },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text("回到当前周期", color = c.accent)
            }
        }
    }
}

@Composable
private fun DateNavigator(
    model: UsageModel,
    modifier: Modifier = Modifier,
    onPick: () -> Unit
) {
    val c = hindsightColors()
    val s = model.selection
    val today = LocalDate.now()
    val end = when (s.period) {
        Period.WEEK -> Dates.week(s.anchor).plusWeeks(1)
        Period.MONTH -> s.anchor.withDayOfMonth(1).plusMonths(1)
        Period.DAYS7 -> s.anchor.plusDays(1)
        Period.DAYS30 -> s.anchor.plusDays(1)
        else -> s.anchor.plusDays(1)
    }
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DateNavButton(
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            description = "上一周期",
            enabled = s.period != Period.ALL && s.period != Period.CUSTOM,
            onClick = { model.move(-1) }
        )
        Surface(
            onClick = onPick,
            modifier = Modifier.weight(1f).height(38.dp),
            shape = RoundedCornerShape(12.dp),
            color = c.control,
            border = BorderStroke(1.dp, c.border)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                Text(
                    model.display.report?.let { windowLabel(it.window, model.zone) } ?: "正在读取",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        DateNavButton(
            icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            description = "下一周期",
            enabled = s.period != Period.ALL && s.period != Period.CUSTOM && end <= today,
            onClick = { model.move(1) }
        )
    }
}

@Composable
private fun DateNavButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val c = hindsightColors()
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.control)
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            description,
            tint = if (enabled) c.textMuted else c.textFaint.copy(alpha = 0.45f),
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun SearchAndSortCard(model: UsageModel) {
    val c = hindsightColors()
    val s = model.selection
    HindsightCard(contentPadding = 14.dp) {
        OutlinedTextField(
            value = s.query,
            onValueChange = { query -> model.change { it.copy(query = query) } },
            label = { Text("搜索应用") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = c.textFaint) },
            trailingIcon = {
                if (s.query.isNotEmpty()) {
                    IconButton(onClick = { model.change { it.copy(query = "") } }) {
                        Icon(Icons.Outlined.Close, "清除搜索", tint = c.textMuted)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent.copy(alpha = 0.55f),
                unfocusedBorderColor = c.border,
                focusedContainerColor = c.card,
                unfocusedContainerColor = c.card,
                cursorColor = c.accent,
                focusedLabelColor = c.accent,
                unfocusedLabelColor = c.textMuted
            )
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.nameSort) "按名称排序" else "按时长排序",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = c.textMuted
            )
            TextButton(
                onClick = { model.change { it.copy(nameSort = !it.nameSort) } },
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Outlined.SwapVert, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("切换排序")
            }
        }
    }
}

@Composable
private fun CompareScreen(model: UsageModel) {
    val s = model.selection
    val context = LocalContext.current
    val r = model.display.report
    val other = model.display.other
    val changes = remember(r?.apps, other?.apps) {
        val current = r?.apps.orEmpty().associate { it.app to it.millis }
        val previous = other?.apps.orEmpty().associate { it.app to it.millis }
        (current.keys + previous.keys)
            .map { it to ((current[it] ?: 0L) - (previous[it] ?: 0L)) }
            .sortedByDescending { kotlin.math.abs(it.second) }
    }
    var menu by remember { mutableStateOf(false) }
    val unit = if (s.monthly) "月" else "周"
    val packages = ((model.display.all ?: r)?.apps.orEmpty() + (model.display.allOther ?: other)?.apps.orEmpty())
        .map { it.app }
        .distinct()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HindsightPageTitle(
                title = "周期对比",
                subtitle = "同进度比较周与月的变化，先看总量，再看应用差异。",
                eyebrow = "时间对照"
            )
        }
        item {
            HindsightCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CompareChip("周对比", !s.monthly) {
                        model.change {
                            it.copy(
                                monthly = false,
                                compareA = LocalDate.now(),
                                compareB = LocalDate.now().minusWeeks(1)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    CompareChip("月对比", s.monthly) {
                        model.change {
                            it.copy(
                                monthly = true,
                                compareA = LocalDate.now(),
                                compareB = LocalDate.now().minusMonths(1)
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        OutlinedButton(
                            onClick = { menu = true },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                            border = BorderStroke(1.dp, cBorder())
                        ) {
                            Text(
                                s.compareApp?.let(model::label) ?: "全部应用",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Icon(Icons.Outlined.ArrowDropDown, null, modifier = Modifier.size(17.dp))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("全部应用") },
                                onClick = {
                                    model.change { it.copy(compareApp = null) }
                                    menu = false
                                }
                            )
                            packages.forEach { pkg ->
                                DropdownMenuItem(
                                    text = { Text(model.label(pkg)) },
                                    onClick = {
                                        model.change { it.copy(compareApp = pkg) }
                                        menu = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    CompareDateCard(
                        label = "统计$unit",
                        date = s.compareA,
                        month = s.monthly,
                        other = false,
                        modifier = Modifier.weight(1f)
                    ) {
                        datePicker(context, s.compareA) { date -> model.change { it.copy(compareA = date) } }
                    }
                    CompareDateCard(
                        label = "对照$unit",
                        date = s.compareB,
                        month = s.monthly,
                        other = true,
                        modifier = Modifier.weight(1f)
                    ) {
                        datePicker(context, s.compareB) { date -> model.change { it.copy(compareB = date) } }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompareChip("同期进度", s.same) { model.change { it.copy(same = true) } }
                    Spacer(Modifier.width(8.dp))
                    CompareChip("完整周期", !s.same) { model.change { it.copy(same = false) } }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (s.same) "按已过去的天数与时刻对齐" else "当前周期未结束时，完整周期比较存在进度差异",
                    style = MaterialTheme.typography.labelSmall,
                    color = hindsightColors().textMuted
                )
            }
        }
        if (r != null && other != null) {
            item {
                val diff = r.total - other.total
                val canCompare = r.buckets.any { it.available } && other.buckets.any { it.available }
                val partial = !r.fullyCovered || !other.fullyCovered
                HindsightCard(
                    background = Brush.linearGradient(
                        listOf(cAccentSoft(), cPeachSoft())
                    )
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "统计$unit",
                                style = MaterialTheme.typography.labelMedium,
                                color = cAccent()
                            )
                            Spacer(Modifier.height(5.dp))
                            BigTimeValue(r.total, available = r.buckets.any { it.available })
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "对照$unit",
                                style = MaterialTheme.typography.labelMedium,
                                color = cPeach()
                            )
                            Spacer(Modifier.height(5.dp))
                            BigTimeValue(other.total, available = other.buckets.any { it.available })
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HindsightDivider()
                    Spacer(Modifier.height(11.dp))
                    Text(
                        when {
                            !canCompare -> "记录不足，暂时无法比较"
                            diff == 0L -> "${if (partial) "可归属用时（含推断）" else "使用时长"}相同"
                            diff < 0 -> "${if (partial) "可归属用时（含推断）" else ""}少用了 ${durationText(kotlin.math.abs(diff))}"
                            else -> "${if (partial) "可归属用时（含推断）" else ""}多用了 ${durationText(diff)}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = hindsightColors().textStrong
                    )
                    Text(
                        if (!canCompare) "缺失不是零"
                        else if (other.total > 0) "%+.1f%%".format(java.util.Locale.CHINA, diff.toDouble() / other.total * 100)
                        else if (r.total > 0) "新增使用"
                        else "均为零",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (diff <= 0) hindsightColors().success else hindsightColors().warning
                    )
                    Spacer(Modifier.height(12.dp))
                    QualityStatus(r, "统计周期")
                    Spacer(Modifier.height(6.dp))
                    QualityStatus(other, "对照周期")
                }
            }
            item {
                HindsightCard {
                    HindsightCardTitle("周期趋势", "$unit 粒度 · 统计与对照")
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UnderlineTabs(
                            labels = listOf("柱状", "折线"),
                            selected = if (s.line) 1 else 0,
                            onSelect = { index -> model.change { it.copy(line = index == 1) } }
                        )
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot("统计$unit", cAccent())
                        Spacer(Modifier.width(16.dp))
                        StatusDot("对照$unit · 虚线", cPeach())
                    }
                    Spacer(Modifier.height(4.dp))
                    UsageChart(r.buckets, other.buckets, s.line)
                    if (!r.fullyCovered || !other.fullyCovered) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "含推断或未知区间；差异不代表确定的全部实际用时。",
                            style = MaterialTheme.typography.labelSmall,
                            color = hindsightColors().textMuted
                        )
                    }
                }
            }
            if (r.buckets.any { it.available } && other.buckets.any { it.available }) {
                item {
                    HindsightCard {
                        HindsightCardTitle("哪些应用变了", "按变化幅度排序")
                        Spacer(Modifier.height(8.dp))
                        if (changes.isEmpty()) {
                            EmptyHint("这两个周期还没有已保存的使用记录。")
                        } else {
                            changes.take(14).forEachIndexed { index, (pkg, diff) ->
                                if (index > 0) HindsightDivider()
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { model.openApp(pkg) }
                                        .heightIn(min = 62.dp)
                                        .padding(vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AppIconTile(model, pkg, 36.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            model.label(pkg),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = hindsightColors().textStrong,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            if (diff > 0) "比对照周期增加" else if (diff < 0) "比对照周期减少" else "与对照周期相同",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = hindsightColors().textMuted
                                        )
                                    }
                                    Text(
                                        "${if (diff > 0) "+" else if (diff < 0) "−" else ""}${durationText(kotlin.math.abs(diff))}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (diff > 0) hindsightColors().warning else if (diff < 0) hindsightColors().success else hindsightColors().textMuted,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            item { EmptyHint("正在读取对比周期的记录…") }
        }
    }
}

@Composable
private fun CompareChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = hindsightColors()
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = c.control,
            labelColor = c.textMuted,
            selectedContainerColor = c.accent,
            selectedLabelColor = Color.White
        )
    )
}

@Composable
private fun CompareDateCard(
    label: String,
    date: LocalDate,
    month: Boolean,
    other: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val c = hindsightColors()
    val tint = if (other) c.peach else c.accent
    val soft = if (other) c.peachSoft else c.accentSoft
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 76.dp),
        shape = RoundedCornerShape(16.dp),
        color = soft.copy(alpha = if (c.dark) 0.7f else 0.72f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(Icons.Outlined.CalendarMonth, null, tint = tint, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(7.dp))
            Text(
                if (month) date.format(DateTimeFormatter.ofPattern("yyyy年M月"))
                else windowLabel(
                    if (month) Dates.fullMonth(date, ZoneId.systemDefault()) else Dates.fullWeek(date, ZoneId.systemDefault()),
                    ZoneId.systemDefault()
                ),
                style = MaterialTheme.typography.labelLarge,
                color = c.textStrong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    model: UsageModel,
    onPermission: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onCsv: () -> Unit,
    onDiagnostics: () -> Unit,
    onRebuild: () -> Unit,
    onSafety: () -> Unit,
    onCompressedExport: () -> Unit
) {
    val c = hindsightColors()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp)
    ) {
        HindsightPageTitle(
            title = "设置",
            subtitle = "记录方式、数据管理与外观。",
            eyebrow = "偏好"
        )
        Spacer(Modifier.height(14.dp))
        if (model.demo) {
            HindsightCard(
                background = Brush.linearGradient(
                    listOf(c.accentSoft.copy(alpha = 0.9f), c.peachSoft.copy(alpha = 0.72f))
                )
            ) {
                Text("正在体验示例数据", style = MaterialTheme.typography.titleMedium, color = c.textStrong)
                Spacer(Modifier.height(4.dp))
                Text("示例不会写入真实记录数据库。", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                TextButton(
                    onClick = {
                        model.useDemo(false)
                        model.refresh()
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("切换真实记录", color = c.accent)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        SectionLabel("记录与统计")
        HindsightCard {
            MetricControls(model)
            Spacer(Modifier.height(8.dp))
            HindsightDivider()
            SettingRow(
                title = "显示确定时间",
                subtitle = "控制累计是否计入已确认时间；未知与暂定区间始终展示",
                trailing = {
                    Switch(
                        checked = model.showConfirmed,
                        onCheckedChange = { model.showConfirmed(it) },
                        colors = hindsightSwitchColors()
                    )
                }
            )
            HindsightDivider()
            SettingRow(
                title = "自动补记",
                subtitle = "约每 6 小时保存，打开时补记",
                trailing = {
                    Switch(
                        checked = model.recording,
                        onCheckedChange = { model.enableRecording(it) },
                        colors = hindsightSwitchColors()
                    )
                }
            )
            HindsightDivider()
            SettingRow(
                title = "使用情况访问",
                subtitle = if (model.permitted) "已授权" else "尚未授权",
                onClick = onPermission
            )
            HindsightDivider()
            SettingRow(
                title = "采集诊断",
                subtitle = "查看事件缺口、时钟和重试记录",
                onClick = onDiagnostics
            )
            HindsightDivider()
            SettingRow(
                title = "重算已保存历史",
                subtitle = "只重算已有事件；保留旧版与导入历史",
                onClick = onRebuild,
                enabled = !model.demo
            )
            Spacer(Modifier.height(10.dp))
            model.display.last?.let { last ->
                Text(
                    "最近保存：${Instant.ofEpochMilli(last).atZone(model.zone).format(DateTimeFormatter.ofPattern("yyyy.M.d HH:mm"))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textFaint
                )
            }
            Text(
                "${model.zone.id} · 每周从周一开始",
                style = MaterialTheme.typography.labelSmall,
                color = c.textFaint
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "后台任务受系统省电调度影响；长时间未补记或权限中断时，部分记录可能无法恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = c.textMuted
            )
        }

        SectionLabel("数据管理")
        HindsightCard {
            SettingRow(
                title = "数据安全与占用",
                subtitle = "查看占用、本机安全副本和恢复",
                onClick = onSafety,
                enabled = !model.demo
            )
            model.safetyInfo.warning?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = c.danger)
            }
            HindsightDivider()
            SettingRow(
                title = "导出完整备份",
                subtitle = "JSON · 保存已记录历史",
                onClick = onExport,
                enabled = !model.demo
            )
            HindsightDivider()
            SettingRow(
                title = "导出压缩完整备份",
                subtitle = "JSON.GZ · 适合长期归档",
                onClick = onCompressedExport,
                enabled = !model.demo
            )
            HindsightDivider()
            SettingRow(
                title = "导入备份",
                subtitle = "JSON / JSON.GZ · 合并历史，保留当前记录",
                onClick = onImport
            )
            HindsightDivider()
            SettingRow(
                title = "导出当前报表",
                subtitle = "CSV · 当前范围的应用时长",
                onClick = onCsv,
                enabled = !model.demo
            )
        }

        SectionLabel("外观")
        HindsightCard {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
                    ThemeChip(label = label, selected = model.appearance == key) { model.theme(key) }
                }
            }
        }

        SectionLabel("桌面小组件")
        WidgetSettingsCard()

        SectionLabel("关于用时记")
        HindsightCard {
            Text(
                "主要使用与屏幕可见是两个独立指标。其他应用的焦点及真实窗口遮挡无法由公开 UsageEvents 完全确认；单 resumed 仅作推断，多 resumed 无焦点证据时主要归属未知。桌面与系统界面排除。",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "无账号，无联网权限。记录保存在本机；卸载会删除本地数据，请先导出备份。",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted
            )
            if (!model.demo) {
                TextButton(onClick = { model.useDemo(true) }, contentPadding = PaddingValues(0.dp)) {
                    Text("体验示例数据", color = c.accent)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "用时记 ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = c.textFaint
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = hindsightColors()
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = c.control,
            labelColor = c.textMuted,
            selectedContainerColor = c.accent,
            selectedLabelColor = Color.White
        )
    )
}

@Composable
private fun hindsightSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = hindsightColors().accent,
    uncheckedThumbColor = hindsightColors().textFaint,
    uncheckedTrackColor = hindsightColors().fillStrong,
    uncheckedBorderColor = hindsightColors().border
)

@Composable
private fun MetricControls(model: UsageModel) {
    val c = hindsightColors()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        UsageMetric.entries.forEach { metric ->
            HindsightChip(
                label = metric.label,
                selected = model.selection.metric == metric && !model.selection.legacy,
                onClick = { model.change { it.copy(metric = metric, legacy = false) } }
            )
        }
        HindsightChip(
            label = "旧版历史",
            selected = model.selection.legacy,
            onClick = { model.change { it.copy(legacy = true) } }
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        if (model.selection.legacy) {
            "保留的旧算法记录；不能恢复为确定的双指标。"
        } else if (model.selection.metric == UsageMetric.PRIMARY) {
            "多应用同时 resumed 且无焦点证据时，归属为未知。"
        } else {
            "不同应用允许重叠；生命周期候选不等于已证明的窗口可见。"
        },
        style = MaterialTheme.typography.labelSmall,
        color = c.textMuted
    )
}

@Composable
private fun QualityStatus(report: UsageReport, label: String = "数据可信状态") {
    var showHelp by remember(label) { mutableStateOf(false) }
    val c = hindsightColors()
    report.quality?.let { quality ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                label,
                if (quality.unknown > 0 || !report.fullyCovered) c.warning else c.success
            )
            Spacer(Modifier.width(5.dp))
            IconButton(
                onClick = { showHelp = true },
                modifier = Modifier.size(26.dp).semantics { contentDescription = "查看${label}说明" }
            ) {
                Icon(Icons.Outlined.Info, null, tint = c.textFaint, modifier = Modifier.size(14.dp))
            }
        }
        Text(
            "已确认 ${durationText(quality.confirmed)} · 候选估计 ${durationText(quality.inferred)}",
            style = MaterialTheme.typography.labelSmall,
            color = c.textMuted
        )
        Text(
            "无法确定 ${durationText(quality.unknown)} · 未闭合 ${durationText(quality.provisional)}",
            style = MaterialTheme.typography.labelSmall,
            color = c.textFaint
        )
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("$label 说明") },
            text = {
                Column(
                    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("已确认：有足够证据，可直接计入累计的时长。")
                    Text("候选估计：根据应用生命周期推断，但尚未证明为实际使用的时长。")
                    Text("无法确定：证据不足，暂时无法归属到具体应用的时长。")
                    Text("未闭合：当前周期尚未结算的区间，不计入累计。")
                    Text("候选估计可能与无法确定区间重叠，不能相加；候选也不代表已证明的实际用时。", color = c.textMuted)
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } }
        )
    }
}

@Composable
private fun StorageSafetyDialog(model: UsageModel, onDismiss: () -> Unit) {
    var action by remember { mutableStateOf<Int?>(null) }
    var selectedTime by remember { mutableStateOf<Long?>(null) }
    val info = model.safetyInfo
    fun size(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)
    val date = info.latest?.let {
        Instant.ofEpochMilli(it).atZone(model.zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
    } ?: "尚无"
    if (action != null) {
        val restoreDate = selectedTime?.let {
            Instant.ofEpochMilli(it).atZone(model.zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm:ss"))
        } ?: date
        val selected = action!!
        AlertDialog(
            onDismissRequest = { action = null },
            title = {
                Text(
                    when (selected) {
                        1 -> "恢复本机安全副本"
                        2 -> "撤销上次恢复"
                        else -> "删除恢复前副本"
                    }
                )
            },
            text = {
                Text(
                    when (selected) {
                        1 -> "将恢复到 $restoreDate 的副本。当前数据库会保留以供撤销，较新的使用事件需要重新补记。"
                        2 -> "将切回上次恢复前的数据，当前数据库也会保留。只有通过校验才会切换。"
                        else -> "将永久删除上次恢复前的副本，之后无法撤销该次恢复。其中可能有当前库没有的较新记录，请先确认已另行保存。当前使用记录和安全副本不会删除。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.safetyAction(selected, selectedTime)
                        onDismiss()
                    }
                ) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("取消") } }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("数据安全与占用") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text("数据库及恢复文件：${size(info.databaseBytes)}")
                    Text("安全副本：${info.snapshots} 份 · ${size(info.snapshotBytes)}")
                    Text("设备可用空间：${size(info.freeBytes)}")
                    Text("最近安全副本：$date")
                    val exported = info.lastExport?.let {
                        Instant.ofEpochMilli(it).atZone(model.zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
                    } ?: "尚未记录"
                    Text("上次导出完整备份：$exported")
                    Text(
                        "约每 12 小时成功补记后创建校验副本，保留两份，优先保留不同版本；实际执行受系统调度影响。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        "本机副本也会随卸载或清除数据而删除。请定期将完整备份导出到应用外。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    info.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(
                        enabled = !model.busy,
                        onClick = {
                            model.safetyAction(0)
                            onDismiss()
                        }
                    ) { Text("立即创建安全副本") }
                    info.copies.forEachIndexed { index, time ->
                        val copyLabel = Instant.ofEpochMilli(time).atZone(model.zone)
                            .format(DateTimeFormatter.ofPattern("M月d日 HH:mm:ss"))
                        TextButton(
                            enabled = !model.busy && !info.canUndo,
                            onClick = {
                                selectedTime = time
                                action = 1
                            }
                        ) {
                            Text("恢复${if (index == 0) "最新" else "上一份"}副本 · $copyLabel")
                        }
                    }
                    if (info.canUndo) {
                        TextButton(enabled = !model.busy, onClick = { action = 2 }) { Text("撤销上次恢复") }
                        TextButton(enabled = !model.busy, onClick = { action = 3 }) { Text("删除恢复前副本") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }
}

private fun periodTitle(period: Period): String = when (period) {
    Period.TODAY -> "今天"
    Period.WEEK -> "本周"
    Period.DAYS7 -> "最近 7 天"
    Period.MONTH -> "本月"
    Period.DAYS30 -> "最近 30 天"
    Period.ALL -> "全部记录"
    Period.CUSTOM -> "自选日期"
}

private fun periodShortLabel(period: Period): String = when (period) {
    Period.DAYS7 -> "7天"
    Period.DAYS30 -> "30天"
    Period.ALL -> "全部"
    else -> period.label
}

@Composable
private fun periodSubtitle(model: UsageModel, report: UsageReport?): String {
    val window = report?.window
    val range = window?.let { windowLabel(it, model.zone) } ?: "正在读取"
    val coverage = when {
        report == null -> "正在读取记录"
        !report.fullyCovered -> "含推断或未知区间 · 缺口不画成零"
        else -> "截至 ${Instant.ofEpochMilli(model.now).atZone(model.zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))}"
    }
    return "$range · $coverage"
}

private fun windowLabel(window: Window, zone: ZoneId): String {
    val start = Dates.date(window.start, zone)
    val end = Dates.date((window.end - 1).coerceAtLeast(window.start), zone)
    val format = DateTimeFormatter.ofPattern(if (start.year != LocalDate.now().year || start.year != end.year) "yyyy.M.d" else "M月d日")
    return if (start == end) start.format(format) else "${start.format(format)} — ${end.format(format)}"
}

private fun percentOf(value: Long, total: Long): String =
    if (total > 0) "%.1f%%".format(java.util.Locale.CHINA, value.toDouble() / total * 100) else "0%"

@Composable
private fun cAccent() = hindsightColors().accent

@Composable
private fun cAccentSoft() = hindsightColors().accentSoft.copy(alpha = if (hindsightColors().dark) 0.7f else 0.88f)

@Composable
private fun cPeach() = hindsightColors().peach

@Composable
private fun cPeachSoft() = hindsightColors().peachSoft.copy(alpha = if (hindsightColors().dark) 0.58f else 0.72f)

@Composable
private fun cBorder() = hindsightColors().border
