package dev.usagetime.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.usagetime.core.Dates
import dev.usagetime.core.durationText
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val MONTH_YEAR_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月")

@Composable
internal fun CompareScreen(model: UsageModel) {
    val c = hindsightColors()
    val s = model.selection
    val today = Dates.date(model.now, model.zone)
    var pickingOther by remember(s.monthly) { mutableStateOf<Boolean?>(null) }
    pickingOther?.let { other ->
        ComparisonPeriodPicker(
            initial = if (other) s.compareB else s.compareA,
            monthly = s.monthly,
            label = (if (other) "对照" else "统计") + if (s.monthly) "月" else "周",
            onDismiss = { pickingOther = null },
            onSelect = { date ->
                model.change { if (other) it.copy(compareB = date) else it.copy(compareA = date) }
            },
            today = today
        )
    }
    val r = model.display.report
    val other = model.display.other
    val changes = remember(r?.apps, other?.apps) {
        UsagePresentation.compareAppDeltas(r?.apps.orEmpty(), other?.apps.orEmpty())
    }
    var menu by remember { mutableStateOf(false) }
    val unit = if (s.monthly) "月" else "周"
    val packages = remember(model.display.all?.apps, r?.apps, model.display.allOther?.apps, other?.apps) {
        ((model.display.all ?: r)?.apps.orEmpty() + (model.display.allOther ?: other)?.apps.orEmpty())
            .map { it.app }
            .distinct()
    }

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
                    HindsightPillChip("周对比", !s.monthly) {
                        model.change {
                            it.copy(
                                monthly = false,
                                compareA = today,
                                compareB = today.minusWeeks(1)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    HindsightPillChip("月对比", s.monthly) {
                        model.change {
                            it.copy(
                                monthly = true,
                                compareA = today,
                                compareB = today.minusMonths(1)
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        OutlinedButton(
                            onClick = { menu = true },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                            border = BorderStroke(1.dp, c.border)
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
                        zone = model.zone,
                        referenceYear = today.year,
                        modifier = Modifier.weight(1f)
                    ) {
                        pickingOther = false
                    }
                    CompareDateCard(
                        label = "对照$unit",
                        date = s.compareB,
                        month = s.monthly,
                        other = true,
                        zone = model.zone,
                        referenceYear = today.year,
                        modifier = Modifier.weight(1f)
                    ) {
                        pickingOther = true
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HindsightPillChip("同期进度", s.same) { model.change { it.copy(same = true) } }
                    Spacer(Modifier.width(8.dp))
                    HindsightPillChip("完整周期", !s.same) { model.change { it.copy(same = false) } }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (s.same) "按已过去的天数与时刻对齐" else "当前周期未结束时，完整周期比较存在进度差异",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMuted
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
                        listOf(
                            c.accentSoft.copy(alpha = if (c.dark) 0.7f else 0.88f),
                            c.peachSoft.copy(alpha = if (c.dark) 0.58f else 0.72f)
                        )
                    )
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "统计$unit",
                                style = MaterialTheme.typography.labelMedium,
                                color = c.accent
                            )
                            Spacer(Modifier.height(5.dp))
                            BigTimeValue(r.total, available = r.buckets.any { it.available })
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "对照$unit",
                                style = MaterialTheme.typography.labelMedium,
                                color = c.peach
                            )
                            Spacer(Modifier.height(5.dp))
                            BigTimeValue(other.total, available = other.buckets.any { it.available })
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HindsightDivider()
                    Spacer(Modifier.height(11.dp))
                    Text(
                        UsagePresentation.comparisonHeadline(diff, canCompare, partial),
                        style = MaterialTheme.typography.titleSmall,
                        color = c.textStrong
                    )
                    Text(
                        UsagePresentation.comparisonDeltaPercent(r.total, other.total, canCompare),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (diff <= 0) c.success else c.warning
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
                        StatusDot("统计$unit", c.accent)
                        Spacer(Modifier.width(16.dp))
                        StatusDot("对照$unit · 虚线", c.peach)
                    }
                    Spacer(Modifier.height(4.dp))
                    UsageChart(r.buckets, other.buckets, s.line)
                    if (!r.fullyCovered || !other.fullyCovered) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "含推断或未知区间；差异不代表确定的全部实际用时。",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textMuted
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
                                            color = c.textStrong,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            if (diff > 0) "比对照周期增加" else if (diff < 0) "比对照周期减少" else "与对照周期相同",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = c.textMuted
                                        )
                                    }
                                    Text(
                                        "${if (diff > 0) "+" else if (diff < 0) "−" else ""}${durationText(abs(diff))}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (diff > 0) c.warning else if (diff < 0) c.success else c.textMuted,
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
private fun CompareDateCard(
    label: String,
    date: LocalDate,
    month: Boolean,
    other: Boolean,
    zone: ZoneId,
    referenceYear: Int,
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
                Icon(AppIcons.CalendarMonth, null, tint = tint, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(7.dp))
            Text(
                if (month) date.format(MONTH_YEAR_FORMATTER)
                else UsagePresentation.windowLabel(
                    Dates.fullWeek(date, zone),
                    zone,
                    referenceYear
                ),
                style = MaterialTheme.typography.labelLarge,
                color = c.textStrong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
