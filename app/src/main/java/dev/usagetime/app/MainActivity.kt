package dev.usagetime.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.usagetime.core.ClockSampling
import dev.usagetime.core.Dates
import dev.usagetime.core.Period
import dev.usagetime.core.Signal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val observationId = java.util.UUID.randomUUID().toString()
    private var widgetNavigation by mutableStateOf<WidgetNavigation?>(null)

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
            model.change {
                it.copy(
                    page = destination.page,
                    anchor = Dates.date(System.currentTimeMillis(), model.zone),
                    period = if (destination.week) Period.DAYS7 else Period.TODAY,
                    detail = destination.app != null,
                    app = destination.app,
                    query = ""
                )
            }
        }
    }

    LaunchedEffect(dark) {
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

    DisposableEffect(lifecycle, model) {
        // Adding the observer to an already resumed Activity delivers the initial ON_RESUME too.
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
    BackHandler(model.selection.page == 5) { model.navigate(0) }

    HindsightTheme(dark = dark) {
        val c = hindsightColors()
        if (safetyDialog) StorageSafetyDialog(model) { safetyDialog = false }
        diagnosticText?.let { text -> DiagnosticDialog(text) { diagnosticText = null } }

        val s = model.selection
        val rendered = model.display
        ReportDrawnWhen {
            model.display.report != null || model.loadError != null || model.selection.page >= 3
        }
        val openUsageAccess = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        val showDiagnostics: () -> Unit = {
            scope.launch {
                try {
                    val cache = model.store.reportCacheStats()
                    val summary = UsagePresentation.diagnosticsSummary(
                        lastLoadMillis = model.lastLoadMillis,
                        lastCpuDrawMillis = model.lastCpuDrawMillis,
                        hits = cache.hits,
                        misses = cache.misses,
                        entries = cache.entries,
                        estimatedBytes = cache.estimatedBytes
                    )
                    diagnosticText = (listOf(summary) + model.store.diagnostics().map {
                        "${it.time} ${it.code}\n${it.detail}"
                    }).joinToString("\n\n")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    model.message = "诊断暂时无法读取：${e.message}"
                }
            }
        }
        val navigation = listOf(
            Triple(0, "总览", AppIcons.QueryStats),
            Triple(1, "应用", AppIcons.Apps),
            Triple(2, "对比", AppIcons.BarChart),
            Triple(4, "分析", AppIcons.CalendarMonth),
            Triple(5, "电脑", AppIcons.Computer),
            Triple(3, "设置", Icons.Outlined.Settings)
        ).filter { it.first != 5 || model.showDesktopData }
        val navPages = navigation.map { it.first }
        val navItems = navigation.map { it.second }
        val navIcons = navigation.map { it.third }

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
                                onCalendar = { showCustomDateRangePicker(context, model) },
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
                                    onPermission = openUsageAccess,
                                    onExport = { export.launch("用时记备份_${Dates.date(model.now, model.zone)}.json") },
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
                                    onCsv = { csv.launch("用时记报表_${Dates.date(model.now, model.zone)}.csv") },
                                    onDiagnostics = showDiagnostics,
                                    onRebuild = model::rebuildHistory,
                                    onSafety = {
                                        model.updateSafetyInfo()
                                        safetyDialog = true
                                    },
                                    onCompressedExport = {
                                        compressedExport.launch("用时记备份_${Dates.date(model.now, model.zone)}.json.gz")
                                    }
                                )
                                s.page == 5 && model.showDesktopData -> DesktopUsageScreen()
                                !model.permitted && !model.demo && model.display.first == null ->
                                    PermissionScreen(model, onGrant = openUsageAccess)
                                s.page == 4 -> InsightsScreen(model)
                                s.page == 2 -> CompareScreen(model)
                                else -> StatisticsScreen(model, openUsageAccess, showDiagnostics)
                            }
                        }
                    }
                }
            }
        }
    }
}
