package dev.usagetime.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.usagetime.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun CategoryPicker(model: UsageModel, pkg: String) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text("分类：${(model.categoryAssignments[pkg] ?: AppCategory.OTHER).label} · 修改")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            AppCategory.entries.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.label) },
                    onClick = { model.assignCategory(pkg, category); expanded = false }
                )
            }
        }
    }
}

@Composable
fun CategorySummaryCard(model: UsageModel, report: UsageReport) {
    val colors = hindsightColors()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val totals = remember(report.apps, model.categoryAssignments) {
        UsageInsights.categories(report.apps, model.categoryAssignments)
    }
    HindsightCard(contentPadding = 16.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("分类统计", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = colors.textStrong)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开")
            }
        }
        if (!expanded) {
            Text(
                totals.take(3).joinToString(" · ") { "${it.category.label} ${durationText(it.millis)}" }
                    .ifEmpty { "当前范围暂无可归属用时" },
                style = MaterialTheme.typography.bodySmall, color = colors.textMuted
            )
        } else {
            Text(
                "在应用详情中设置分类。${if (model.selection.metric == UsageMetric.VISIBLE) "屏幕可见按应用累计，允许重叠。" else "分类沿用当前统计口径。"}",
                style = MaterialTheme.typography.bodySmall, color = colors.textMuted
            )
            totals.forEach { total ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        selected = if (selected == total.category.name) null else total.category.name
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(total.category.label, Modifier.width(52.dp), color = colors.textStrong)
                    LinearProgressIndicator(
                        progress = { if (report.total > 0) (total.millis.toFloat() / report.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.weight(1f).height(4.dp),
                        color = colors.accent, trackColor = colors.fillStrong,
                        gapSize = 0.dp, drawStopIndicator = {}
                    )
                    Text(
                        durationText(total.millis), Modifier.widthIn(min = 90.dp).padding(start = 10.dp),
                        style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, color = colors.textStrong
                    )
                }
                if (selected == total.category.name) {
                    report.apps.filter { (model.categoryAssignments[it.app] ?: AppCategory.OTHER) == total.category }
                        .forEach { app ->
                            TextButton(onClick = { model.openApp(app.app) }) {
                                Text("${model.label(app.app)} · ${durationText(app.millis)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                }
            }
        }
    }
}

@Composable
fun WidgetSettingsCard() {
    val context = LocalContext.current
    val manager = remember(context) { android.appwidget.AppWidgetManager.getInstance(context) }
    val colors = hindsightColors()
    HindsightCard {
        Text("桌面小组件", style = MaterialTheme.typography.titleSmall, color = colors.textStrong)
        Spacer(Modifier.height(6.dp))
        Text(
            "5种款式，按桌面空间选择。沿用当前统计口径，只显示真实已保存记录；右上角可刷新，补记新数据请打开用时记。",
            style = MaterialTheme.typography.bodySmall, color = colors.textMuted
        )
        WidgetStyle.entries.forEachIndexed { index, style ->
            if (index > 0) HindsightDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${style.label}  ${style.dimensions}", style = MaterialTheme.typography.labelLarge, color = colors.textStrong)
                    Spacer(Modifier.height(4.dp))
                    Text(style.description, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
                }
                if (manager.isRequestPinAppWidgetSupported) {
                    TextButton(onClick = {
                        manager.requestPinAppWidget(android.content.ComponentName(context, style.provider), null, null)
                    }) { Text("添加") }
                }
            }
        }
        Text("网格尺寸以桌面实际排列为准；拖动边缘调整后，内容会随空间变化。后台更新受系统调度影响。",
            style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
        if (!manager.isRequestPinAppWidgetSupported) {
            Text("在桌面长按空白处，选择小组件 → 用时记。", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
        }
    }
}

@Composable
fun InsightsScreen(model: UsageModel) {
    val colors = hindsightColors()
    val today = Dates.date(model.now, model.zone)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var monthOffset by rememberSaveable { mutableLongStateOf(0L) }
    var heatmapYear by rememberSaveable { mutableIntStateOf(today.year) }
    val month = YearMonth.from(today).plusMonths(monthOffset)
    val requestedYear = if (tab == 1) month.year else if (tab == 2) heatmapYear else today.year
    LaunchedEffect(requestedYear, model.demo, model.selection.metric, model.selection.legacy, model.showConfirmed, model.now / 60000, model.zone) {
        model.loadInsights(requestedYear)
    }
    val state = model.insights
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HindsightPageTitle(title = "时间分析", subtitle = "从当天使用片段，到每个月的变化。", eyebrow = "记录回顾")
        }
        item {
            HindsightCard(contentPadding = 14.dp) {
                UnderlineTabs(labels = listOf("当天时间线", "月历", "年度热力图"), selected = tab, onSelect = { tab = it })
                Spacer(Modifier.height(8.dp))
                Text(
                    "${if (model.selection.legacy) "旧版历史" else model.selection.metric.label} · ${if (model.showConfirmed) "已确认与候选估计" else "候选估计累计"}",
                    color = colors.textMuted, style = MaterialTheme.typography.labelSmall
                )
                if (model.insightsLoading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
                }
                model.insightsError?.let { error ->
                    Text(error, color = colors.danger, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { model.loadInsights(requestedYear) }) { Text("重新读取") }
                }
            }
        }
        when (tab) {
            0 -> {
                item {
                    HindsightCard(contentPadding = 16.dp) {
                        Text(today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA)), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "展示已保存的当天片段，截止最近补记。没有明细不等于使用了 0 分钟；重叠片段不直接相加。",
                            color = colors.textMuted, style = MaterialTheme.typography.bodySmall
                        )
                        if (!model.showConfirmed) Text(
                            "已确认片段仍可查看，但未计入当前累计。",
                            color = colors.textFaint, style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                if (!model.insightsLoading && state.timeline.isEmpty()) item {
                    EmptyHint("当天暂无保存的明细，返回总览补记后再查看。")
                }
                items(state.timeline.chunked(12)) { group ->
                    HindsightCard(contentPadding = 14.dp) {
                        group.forEachIndexed { index, entry ->
                            if (index > 0) HindsightDivider()
                            TimelineRow(model, entry)
                        }
                    }
                }
            }
            1 -> {
                item {
                    HindsightCard(contentPadding = 14.dp) {
                        PeriodHeading(
                            "${month.year}年${month.monthValue}月", month.year > 1970 || month.monthValue > 1,
                            month < YearMonth.from(today),
                            { monthOffset-- }, { monthOffset++ }
                        )
                        MonthCalendar(model, month, state.days, today)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "数字为当天用时；— 为未记录，0分为已有记录且用时为零。边框标记部分记录。点击日期查看明细。",
                            style = MaterialTheme.typography.labelSmall, color = colors.textMuted
                        )
                    }
                }
            }
            2 -> {
                item {
                    HindsightCard(contentPadding = 14.dp) {
                        PeriodHeading(
                            "${heatmapYear}年", heatmapYear > 1970, heatmapYear < today.year,
                            { heatmapYear-- }, { heatmapYear++ }
                        )
                        YearHeatmap(heatmapYear, state.days, today)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "颜色越深，用时越长。空心格为未记录，浅色格为已有记录的零用时；短线标记部分记录。横向滑动查看全年。",
                            style = MaterialTheme.typography.labelSmall, color = colors.textMuted
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..12).forEach { monthNumber ->
                                if (LocalDate.of(heatmapYear, monthNumber, 1) <= today) {
                                    TextButton(onClick = {
                                        monthOffset = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.of(heatmapYear, monthNumber))
                                        tab = 1
                                    }) { Text("${monthNumber}月") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(model: UsageModel, entry: TimelineEntry) {
    val colors = hindsightColors()
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val start = Instant.ofEpochMilli(entry.start).atZone(model.zone).format(formatter)
    val end = Instant.ofEpochMilli(entry.end).atZone(model.zone).format(formatter)
    val tint = when {
        entry.missing -> colors.textFaint
        entry.provisional -> colors.warning
        entry.evidence == Evidence.CONFIRMED -> colors.success
        entry.evidence == Evidence.UNKNOWN -> colors.warning
        else -> colors.accent
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 74.dp)
            .clickable(enabled = entry.app != null) { entry.app?.let(model::openApp) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(60.dp)) {
            Text(start, style = MaterialTheme.typography.labelMedium, color = colors.textStrong)
            Text(end, style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
        }
        Box(Modifier.width(3.dp).height(42.dp).background(tint, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.app?.let(model::label) ?: if (entry.missing) "没有保存的明细" else "无法确定应用归属",
                color = colors.textStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            val quality = when {
                entry.missing -> "可能无使用，也可能缺少记录"
                entry.provisional -> "未闭合 · 不计入累计"
                entry.evidence == Evidence.CONFIRMED -> "已确认"
                entry.evidence == Evidence.INFERRED -> "候选估计"
                entry.evidence == Evidence.LEGACY -> "旧版未分级"
                else -> "未知区间"
            }
            Text(quality, color = tint, style = MaterialTheme.typography.labelSmall)
        }
        Text(durationText(entry.end - entry.start), style = MaterialTheme.typography.labelMedium, color = colors.textMuted)
    }
}

@Composable
private fun PeriodHeading(title: String, canBack: Boolean, canForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = canBack) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上一周期") }
        Text(title, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onForward, enabled = canForward) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一周期") }
    }
}

@Composable
private fun MonthCalendar(model: UsageModel, month: YearMonth, days: Map<LocalDate, CalendarDay>, today: LocalDate) {
    val colors = hindsightColors()
    val maximum = days.values.filter { YearMonth.from(it.date) == month && it.available }.maxOfOrNull { it.millis } ?: 0
    Row(Modifier.fillMaxWidth()) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach {
            Text(it, Modifier.weight(1f).padding(vertical = 10.dp), textAlign = TextAlign.Center, color = colors.textMuted)
        }
    }
    UsageInsights.monthGrid(month).chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            week.forEach { date ->
                val day = days[date]
                val future = date != null && date > today
                val available = day?.available == true
                val label = when {
                    date == null || future -> ""
                    !available -> "—"
                    else -> shortDuration(day!!.millis)
                }
                val fill = if (date == null || future || !available) Color.Transparent
                    else colors.accent.copy(alpha = 0.06f + UsageInsights.intensity(day!!.millis, maximum) * 0.055f)
                Column(
                    Modifier.weight(1f).padding(2.dp).heightIn(min = 58.dp)
                        .clip(RoundedCornerShape(10.dp)).background(fill)
                        .border(
                            1.dp,
                            if (date == today) colors.accent else if (available && day?.complete == false) colors.warning else Color.Transparent,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable(enabled = date != null && !future) { date?.let(model::openDate) }
                        .semantics {
                            contentDescription = if (date == null) "" else "$date ${if (future) "未来日期" else if (!available) "未记录" else durationText(day!!.millis)}${if (available && day?.complete == false) " 部分记录" else ""}"
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(date?.dayOfMonth?.toString().orEmpty(), style = MaterialTheme.typography.labelMedium,
                        color = if (future) colors.textFaint else colors.textStrong)
                    Spacer(Modifier.height(5.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun YearHeatmap(year: Int, days: Map<LocalDate, CalendarDay>, today: LocalDate) {
    val colors = hindsightColors()
    val first = LocalDate.of(year, 1, 1)
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val end = LocalDate.of(year, 12, 31)
    val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(start, end) + 7) / 7).toInt()
    val maximum = days.values.filter { it.available }.maxOfOrNull { it.millis } ?: 0
    Row(
        Modifier.horizontalScroll(rememberScrollState()).clearAndSetSemantics {
            contentDescription = "${year}年用时热力图，已记录${days.values.count { it.available }}天。可通过下方月份按钮查看每日明细。"
        },
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(weeks) { week ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(7) { weekday ->
                    val date = start.plusDays((week * 7 + weekday).toLong())
                    val day = days[date]
                    val inRange = date.year == year && date <= today
                    val available = inRange && day?.available == true
                    val fill = if (!available) Color.Transparent else colors.accent.copy(
                        alpha = 0.12f + UsageInsights.intensity(day!!.millis, maximum) * 0.2f
                    )
                    Box(
                        Modifier.size(15.dp).background(fill, RoundedCornerShape(3.dp))
                            .border(1.dp, if (inRange && !available) colors.border else Color.Transparent, RoundedCornerShape(3.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (available && day?.complete == false) {
                            Box(Modifier.width(8.dp).height(1.dp).background(colors.textMuted))
                        }
                    }
                }
            }
        }
    }
}

private fun shortDuration(millis: Long): String = when {
    millis < 60000 -> if (millis == 0L) "0分" else "<1分"
    millis < 3600000 -> "${millis / 60000}分"
    else -> "%.1f时".format(java.util.Locale.CHINA, millis / 3600000.0)
}
