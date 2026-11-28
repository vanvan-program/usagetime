package dev.usagetime.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.usagetime.core.AppTotal
import dev.usagetime.core.Dates
import dev.usagetime.core.Grain
import dev.usagetime.core.Period
import dev.usagetime.core.UsageReport
import dev.usagetime.core.durationText
import java.time.Instant
import java.time.format.DateTimeFormatter

private val FULL_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val AS_OF_TIME_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private const val RECORDING_STALE_MS = 12 * 60 * 60 * 1_000L

@Composable
private fun recordingNotice(
    model: UsageModel,
    report: UsageReport?,
    onGrant: () -> Unit,
    onDiagnostics: () -> Unit
): RecordingNotice? {
    val display = model.display
    val last = display.last
    val gap = remember(report, display.first, last, model.computing) {
        if (report == null || model.computing || last == null) null
        else UsagePresentation.lastUnavailableRange(report, display.first, minOf(last, report.window.end))
    }
    return when {
        !model.permitted && display.first != null -> RecordingNotice(
            "使用情况访问已关闭",
            "重新授权后可以继续保存用时。",
            "恢复授权",
            onGrant
        )
        !model.recording -> RecordingNotice(
            "自动补记已暂停",
            "开启后继续记录近期用时。",
            "开启自动补记",
            { model.enableRecording(true) }
        )
        last != null && last <= model.now - RECORDING_STALE_MS -> RecordingNotice(
            "补记已超过 12 小时未更新",
            "上次保存于 ${Instant.ofEpochMilli(last).atZone(model.zone).format(AS_OF_TIME_FORMATTER)}。",
            "立即补记",
            model::refresh,
            enabled = !model.busy
        )
        gap != null -> {
            val dateTime = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(model.zone)
            val range = if (model.selection.period == Period.TODAY) {
                "${dateTime.format(Instant.ofEpochMilli(gap.start))} — ${dateTime.format(Instant.ofEpochMilli(gap.end))}"
            } else {
                UsagePresentation.windowLabel(gap, model.zone, Dates.date(model.now, model.zone).year)
            }
            RecordingNotice("这段时间暂无可用统计", range, "查看采集诊断", onDiagnostics)
        }
        else -> null
    }
}

@Composable
private fun OverviewRecordingNotice(notice: RecordingNotice) {
    val c = hindsightColors()
    HindsightCard(contentPadding = 14.dp) {
        StatusDot(notice.title, c.warning)
        Spacer(Modifier.height(4.dp))
        Text(notice.detail, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        TextButton(onClick = notice.action, enabled = notice.enabled, contentPadding = PaddingValues(0.dp)) {
            Text(notice.actionLabel)
        }
    }
}

private data class RecordingNotice(
    val title: String,
    val detail: String,
    val actionLabel: String,
    val action: () -> Unit,
    val enabled: Boolean = true
)

@Composable
internal fun StatisticsScreen(
    model: UsageModel,
    onGrant: () -> Unit,
    onDiagnostics: () -> Unit,
    onPickPeriod: () -> Unit
) {
    val s = model.selection
    val r = model.display.report
    val notice = if (s.page == 0 && !model.demo) recordingNotice(model, r, onGrant, onDiagnostics) else null
    val listState = rememberLazyListState()
    LaunchedEffect(s.page, s.detail, s.app) {
        listState.scrollToItem(0)
    }
    val apps = remember(r?.apps, s.query, s.nameSort, s.page, model.labelRevision) {
        UsagePresentation.filterAndSortApps(
            apps = r?.apps.orEmpty(),
            query = s.query,
            sortByName = s.nameSort && s.page == 1,
            label = model::label
        )
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
                        "占总用时 ${UsagePresentation.percentOf(r.total, model.display.all?.total ?: 0)} · ${UsagePresentation.periodShortLabel(s.period)}"
                    } else {
                        "正在读取应用记录"
                    }
                )
            } else if (s.page == 0) {
                val today = Dates.date(model.now, model.zone)
                HindsightPageTitle(
                    title = if (s.period == Period.TODAY && s.anchor != today) {
                        s.anchor.format(FULL_DATE_FORMATTER)
                    } else UsagePresentation.periodTitle(s.period),
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
                if (s.period == Period.CUSTOM) {
                    HindsightChip(UsagePresentation.periodShortLabel(Period.CUSTOM), true, onPickPeriod)
                }
                periods.forEach { period ->
                    HindsightChip(
                        label = UsagePresentation.periodShortLabel(period),
                        selected = s.period == period,
                        onClick = { model.period(period) }
                    )
                }
            }
        }

        notice?.let { item(key = "recording-status") { OverviewRecordingNotice(it) } }

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
                        RankedAppRows(model = model, report = r, apps = chunk, startRank = chunkIndex * 8 + 1)
                    }
                }
            } else {
                item { SummaryCard(model, r) }
                item { UsageTrendCard(model, r, onPickPeriod) }
            }
            if (s.detail) {
                item {
                    HindsightCard {
                        HindsightCardTitle("数据概览")
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            MiniStat("峰值时段", durationText(r.buckets.maxOfOrNull { it.millis } ?: 0))
                            MiniStat("记录覆盖", if (r.fullyCovered) "已覆盖" else "部分记录")
                            MiniStat("应用占比", UsagePresentation.percentOf(r.total, model.display.all?.total ?: 0))
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
                item { SectionLabel("时长明细") }
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
                        RankedAppRows(model = model, report = r, apps = apps.take(7), startRank = 1)
                    }
                }
                if (s.page == 0 && model.showDesktopData) {
                    item { CompactDesktopCard(model) }
                }
            }
        } else {
            item { EmptyHint("正在读取已保存的记录…") }
        }
    }
}

@Composable
private fun RankedAppRows(
    model: UsageModel,
    report: UsageReport,
    apps: List<AppTotal>,
    startRank: Int
) {
    apps.forEachIndexed { offset, app ->
        if (offset > 0) HindsightDivider()
        RankedAppRow(
            rank = startRank + offset,
            model = model,
            pkg = app.app,
            value = app.millis,
            total = report.total,
            confirmed = if (model.showConfirmed) report.appQuality[app.app]?.confirmed else null,
            inferred = if (model.showConfirmed) report.appQuality[app.app]?.inferred else null,
            onClick = { model.openApp(app.app) }
        )
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
                Text(label, style = MaterialTheme.typography.labelMedium, color = c.textMuted)
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
                if (s.detail) UsagePresentation.percentOf(report.total, model.display.all?.total ?: 0)
                else UsagePresentation.windowLabel(report.window, model.zone, Dates.date(model.now, model.zone).year)
            )
        }
        report.quality?.let {
            Spacer(Modifier.height(14.dp))
            HindsightDivider()
            Spacer(Modifier.height(11.dp))
            QualityStatus(report)
        }
    }
}

@Composable
private fun UsageTrendCard(model: UsageModel, report: UsageReport, onPickPeriod: () -> Unit) {
    val c = hindsightColors()
    val s = model.selection
    val today = Dates.date(model.now, model.zone)
    HindsightCard {
        HindsightCardTitle(
            title = "使用趋势",
            subtitle = UsagePresentation.windowLabel(report.window, model.zone, today.year)
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
                onPick = onPickPeriod
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
        if (s.anchor < today && s.period != Period.ALL && s.period != Period.CUSTOM) {
            TextButton(
                onClick = { model.change { it.copy(anchor = today) } },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text("回到当前周期", color = c.accent)
            }
        }
    }
}

@Composable
internal fun DateNavigator(
    model: UsageModel,
    modifier: Modifier = Modifier,
    onPick: () -> Unit
) {
    val c = hindsightColors()
    val s = model.selection
    val today = Dates.date(model.now, model.zone)
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
            modifier = Modifier.weight(1f).height(38.dp).semantics { contentDescription = "选择统计周期" },
            shape = RoundedCornerShape(12.dp),
            color = c.control,
            border = BorderStroke(1.dp, c.border)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
                Text(
                    model.display.report?.let { UsagePresentation.windowLabel(it.window, model.zone, today.year) } ?: "正在读取",
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
            enabled = s.canStepForward(today),
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
                Icon(AppIcons.SwapVert, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("切换排序")
            }
        }
    }
}

@Composable
private fun periodSubtitle(model: UsageModel, report: UsageReport?): String {
    val window = report?.window
    val range = window?.let {
        UsagePresentation.windowLabel(it, model.zone, Dates.date(model.now, model.zone).year)
    } ?: "正在读取"
    val coverage = when {
        report == null -> "正在读取记录"
        !report.fullyCovered -> "含推断或未知区间 · 缺口不画成零"
        else -> "截至 ${Instant.ofEpochMilli(model.now).atZone(model.zone).format(AS_OF_TIME_FORMATTER)}"
    }
    return "$range · $coverage"
}
