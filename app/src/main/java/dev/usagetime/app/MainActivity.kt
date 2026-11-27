package dev.usagetime.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        (application as UsageTimeApplication).startupWorkGate.activityStarted()
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
    var backupDocumentsOpened by rememberSaveable { mutableStateOf(false) }
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

    val backupDocuments = if (model.selection.page == 3 || backupDocumentsOpened) {
        // Keep pending picker results registered across tab changes and activity recreation.
        if (!backupDocumentsOpened) SideEffect { backupDocumentsOpened = true }
        rememberBackupDocuments(model)
    } else null

    DisposableEffect(lifecycle, model) {
        // Adding the observer to an already resumed Activity delivers the initial ON_RESUME too.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refreshOnResume()
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
        val startupWorkGate = (context.applicationContext as? UsageTimeApplication)?.startupWorkGate
        val initialReportReady = rendered.report != null || model.loadError != null || model.selection.page >= 3
        val loadingOverview = s.page == 0 && model.permitted &&
            rendered.report == null && model.loadError == null
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
        val showDesktopData = model.showDesktopData
        val (navPages, navItems, navIcons) = remember(showDesktopData) {
            val entries = buildList {
                add(Triple(0, "总览", AppIcons.QueryStats))
                add(Triple(1, "应用", AppIcons.Apps))
                add(Triple(2, "对比", AppIcons.BarChart))
                add(Triple(4, "分析", AppIcons.CalendarMonth))
                if (showDesktopData) add(Triple(5, "电脑", AppIcons.Computer))
                add(Triple(3, "设置", Icons.Outlined.Settings))
            }
            Triple(entries.map { it.first }, entries.map { it.second }, entries.map { it.third })
        }

        HindsightBackdrop(decorated = !loadingOverview) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        model.reportDrawn(rendered)
                        if (initialReportReady) startupWorkGate?.firstReportDrawn()
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
                                decorated = !loadingOverview,
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
                                decorated = !loadingOverview,
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
                                decorated = !loadingOverview,
                                onCalendar = { showCustomDateRangePicker(context, model) },
                                onRefresh = model::refresh
                            )
                            if (model.computing && !loadingOverview) {
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
                                    onExport = checkNotNull(backupDocuments).export,
                                    onImport = backupDocuments.importBackup,
                                    onCsv = backupDocuments.csv,
                                    onDiagnostics = showDiagnostics,
                                    onRebuild = model::rebuildHistory,
                                    onSafety = {
                                        model.updateSafetyInfo()
                                        safetyDialog = true
                                    },
                                    onCompressedExport = backupDocuments.compressedExport
                                )
                                s.page == 5 && model.showDesktopData -> DesktopUsageScreen()
                                !model.permitted && !model.demo && model.display.first == null ->
                                    PermissionScreen(model, onGrant = openUsageAccess)
                                loadingOverview -> Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp)) {
                                    HindsightPageTitle(
                                        title = UsagePresentation.periodTitle(s.period),
                                        subtitle = "正在读取记录",
                                        eyebrow = "使用时间"
                                    )
                                }
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
