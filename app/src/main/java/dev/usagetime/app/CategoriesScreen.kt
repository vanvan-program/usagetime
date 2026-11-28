package dev.usagetime.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.usagetime.core.AppCategory
import dev.usagetime.core.AppTotal
import dev.usagetime.core.CategoryTotal
import dev.usagetime.core.Period
import dev.usagetime.core.UsageInsights
import dev.usagetime.core.UsageMetric
import dev.usagetime.core.UsageReport
import dev.usagetime.core.durationText
import kotlin.math.asin

private val categoryPeriods = listOf(
    Period.TODAY, Period.WEEK, Period.DAYS7, Period.MONTH, Period.DAYS30, Period.ALL
)

@Composable
internal fun CategoriesScreen(model: UsageModel, onPickPeriod: () -> Unit) {
    val selection = model.selection
    val report = model.display.report
    val assignments = model.categoryAssignments
    val colors = hindsightColors()
    var managing by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<AppCategory?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var editingApp by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = managing) { managing = false }
    val activeFilter = filter
    val apps = remember(report?.apps, assignments, activeFilter, query, model.labelRevision, managing) {
        if (!managing) emptyList() else UsagePresentation.filterAndSortApps(
            apps = report?.apps.orEmpty().filter {
                activeFilter == null || UsageInsights.categoryFor(it.app, assignments) == activeFilter
            },
            query = query,
            sortByName = false,
            label = model::label
        )
    }
    editingApp?.let { pkg -> CategoryDialog(model, pkg, onDismiss = { editingApp = null }) }

    key(managing) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { HindsightPageTitle("分类") }
            item {
                val custom = selection.period == Period.CUSTOM
                val periodScroll = key(custom) { rememberScrollState() }
                val periods = if (custom) listOf(Period.CUSTOM) + categoryPeriods else categoryPeriods
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(periodScroll),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    periods.forEach { period ->
                        HindsightChip(
                            label = UsagePresentation.periodShortLabel(period),
                            selected = selection.period == period,
                            onClick = {
                                if (period == Period.CUSTOM) onPickPeriod()
                                else model.period(period)
                            }
                        )
                    }
                }
            }
            item { DateNavigator(model, onPick = onPickPeriod) }
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !managing, onClick = { managing = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = {}
                    ) { Text("用时分布") }
                    SegmentedButton(
                        selected = managing,
                        onClick = {
                            managing = true
                            filter = null
                            query = ""
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = {}
                    ) { Text("应用归类") }
                }
            }
            if (report == null) {
                if (model.loadError == null) item { EmptyHint("正在读取分类用时") }
            } else {
                if (model.display.legacyAvailable) item {
                    HindsightCard {
                        Text("此范围有旧版记录。", color = colors.textMuted)
                        TextButton(onClick = { model.change { it.copy(legacy = true) } }) { Text("查看旧版记录") }
                    }
                }
                if (!managing) {
                    item {
                        CategoryDistribution(model, report, assignments) {
                            filter = it
                            query = ""
                            managing = true
                        }
                    }
                } else {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (activeFilter != null) {
                                Row(
                                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        activeFilter.label, Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium, color = colors.textStrong
                                    )
                                    TextButton(onClick = { filter = null }) { Text("显示全部分类") }
                                }
                            }
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("搜索应用") }
                            )
                            Text(
                                "显示所选日期内有用时的应用。点击应用可修改分类。",
                                style = MaterialTheme.typography.bodySmall, color = colors.textMuted
                            )
                        }
                    }
                    if (apps.isEmpty()) item {
                        EmptyHint(
                            if (query.isNotBlank()) "没有找到对应的应用"
                            else if (activeFilter != null) "此范围内该分类暂无应用用时"
                            else "此范围内暂无应用用时"
                        )
                    }
                    items(apps, key = { it.app }) { app ->
                        CategoryAppRow(model, app, assignments, onEdit = { editingApp = app.app })
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryDistribution(
    model: UsageModel,
    report: UsageReport,
    assignments: Map<String, AppCategory>,
    onCategory: (AppCategory) -> Unit
) {
    val colors = hindsightColors()
    val available = report.buckets.any { it.available } || report.total > 0
    val totals = remember(report.apps, assignments) {
        val recorded = UsageInsights.categories(report.apps, assignments)
        recorded + AppCategory.entries.filter { category -> recorded.none { it.category == category } }
            .map { CategoryTotal(it, 0, 0) }
    }
    val metric = if (model.selection.legacy) "旧版历史" else model.selection.metric.label
    HindsightCard(contentPadding = 16.dp) {
        HindsightCardTitle("应用累计用时", metric)
        Spacer(Modifier.height(14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 560.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    CategoryDonut(totals, report.total, available, Modifier.width(200.dp))
                    CategoryLegend(totals, report.total, available, onCategory, Modifier.weight(1f))
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CategoryDonut(totals, report.total, available, Modifier.widthIn(max = 184.dp).fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    CategoryLegend(totals, report.total, available, onCategory)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val basis = if (!model.selection.legacy && model.selection.metric == UsageMetric.VISIBLE) {
            "占比按应用可见时间相加计算，分屏用时可能重叠。"
        } else {
            "占比按所选日期内已归属应用的累计用时计算。"
        }
        Text(basis, style = MaterialTheme.typography.bodySmall, color = colors.textMuted)
    }
}

@Composable
private fun CategoryDonut(totals: List<CategoryTotal>, totalMillis: Long, available: Boolean, modifier: Modifier) {
    val colors = hindsightColors()
    val segments = remember(totals) { totals.filter { it.millis > 0 } }
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val diameter = size.minDimension
            val strokeWidth = diameter * 0.094f
            val radius = (diameter - strokeWidth) / 2f
            if (segments.size <= 1 || totalMillis <= 0) {
                val color = segments.firstOrNull()?.let { colors.categoryColor(it.category) } ?: colors.fillStrong
                drawCircle(color, radius = radius, style = Stroke(strokeWidth))
            } else {
                val capDegrees = Math.toDegrees(asin(strokeWidth / (2.0 * radius))).toFloat()
                val inset = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2, radius * 2)
                var angle = -90f
                for (segment in segments) {
                    val sweep = (360.0 * segment.millis / totalMillis).toFloat()
                    val gap = minOf(1.6f, sweep * 0.15f)
                    // Rounded caps extend past the arc. Short segments use flat ends to stay visible in proportion.
                    val rounded = sweep > 2 * (gap + capDegrees) + 1f
                    val trim = if (rounded) gap + capDegrees else gap
                    drawArc(
                        color = colors.categoryColor(segment.category),
                        startAngle = angle + trim,
                        sweepAngle = sweep - 2 * trim,
                        useCenter = false,
                        topLeft = inset,
                        size = arcSize,
                        style = Stroke(strokeWidth, cap = if (rounded) StrokeCap.Round else StrokeCap.Butt)
                    )
                    angle += sweep
                }
            }
        }
        Text(
            if (available) durationText(totalMillis) else "未记录",
            modifier = Modifier.fillMaxWidth(0.69f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall,
            color = if (available) colors.textStrong else colors.textMuted,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CategoryLegend(
    totals: List<CategoryTotal>,
    totalMillis: Long,
    available: Boolean,
    onCategory: (AppCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = hindsightColors()
    Column(modifier.fillMaxWidth()) {
        totals.forEachIndexed { index, total ->
            if (index > 0) HindsightDivider()
            val color = colors.categoryColor(total.category)
            val fraction = if (totalMillis > 0) (total.millis.toDouble() / totalMillis).toFloat() else 0f
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(onClickLabel = "查看${total.category.label}应用") { onCategory(total.category) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.size(9.dp).background(color, CircleShape))
                        Text(total.category.label, color = colors.textStrong, fontWeight = FontWeight.Medium)
                        if (available) Text(
                            "${total.apps} 个应用",
                            style = MaterialTheme.typography.labelSmall, color = colors.textMuted
                        )
                    }
                    if (available) {
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(colors.fillStrong)) {
                            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color, CircleShape))
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(104.dp)) {
                    Text(
                        if (available) durationText(total.millis) else "—",
                        textAlign = TextAlign.End,
                        style = MaterialTheme.typography.labelLarge, color = colors.textStrong
                    )
                    if (totalMillis > 0) Text(
                        UsagePresentation.percentOf(total.millis, totalMillis),
                        style = MaterialTheme.typography.labelMedium, color = colors.textMuted
                    )
                }
            }
        }
    }
}

private fun HindsightColors.categoryColor(category: AppCategory): Color = when (category) {
    AppCategory.STUDY -> mint
    AppCategory.WORK -> sky
    AppCategory.SOCIAL -> accent
    AppCategory.VIDEO -> rose
    AppCategory.GAME -> gold
    AppCategory.BROWSE -> if (dark) Color(0xFF59D4ED) else Color(0xFF167F99)
    AppCategory.LIFE -> if (dark) Color(0xFFB3D878) else Color(0xFF658621)
    AppCategory.TOOLS -> peach
    AppCategory.OTHER -> textFaint
}

@Composable
private fun CategoryAppRow(
    model: UsageModel,
    app: AppTotal,
    assignments: Map<String, AppCategory>,
    onEdit: () -> Unit
) {
    val colors = hindsightColors()
    val category = UsageInsights.categoryFor(app.app, assignments)
    val source = if (app.app in assignments) "手动" else "默认"
    HindsightCard(contentPadding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .clickable(onClickLabel = "修改分类", role = Role.Button, onClick = onEdit)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppIconTile(model, app.app, 36.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    model.label(app.app), style = MaterialTheme.typography.titleSmall, color = colors.textStrong,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${category.label} · $source",
                    style = MaterialTheme.typography.bodySmall, color = colors.textMuted
                )
            }
            Text(
                durationText(app.millis), Modifier.widthIn(max = 110.dp),
                style = MaterialTheme.typography.labelLarge, color = colors.textStrong
            )
        }
    }
}

@Composable
internal fun CategoryPicker(model: UsageModel, pkg: String) {
    var editing by rememberSaveable(pkg) { mutableStateOf(false) }
    val category = UsageInsights.categoryFor(pkg, model.categoryAssignments)
    TextButton(onClick = { editing = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Text("分类：${category.label} · 修改")
    }
    if (editing) CategoryDialog(model, pkg, onDismiss = { editing = false })
}

@Composable
private fun CategoryDialog(model: UsageModel, pkg: String, onDismiss: () -> Unit) {
    val colors = hindsightColors()
    val manual = model.categoryAssignments[pkg]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为${model.label(pkg)}选择分类", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                CategoryOption("默认 · ${AppCategory.defaultFor(pkg).label}", manual == null) {
                    model.assignCategory(pkg, null)
                    onDismiss()
                }
                HindsightDivider()
                AppCategory.entries.forEach { category ->
                    CategoryOption(category.label, selected = manual == category) {
                        model.assignCategory(pkg, category)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        containerColor = colors.cardElevated
    )
}

@Composable
private fun CategoryOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, color = hindsightColors().textStrong)
    }
}
