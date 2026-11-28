package dev.usagetime.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.usagetime.core.Dates
import dev.usagetime.core.Period
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

internal enum class ReportDateMode { DAY, WEEK, MONTH, RANGE }

internal fun reportDateMode(period: Period) = when (period) {
    Period.WEEK -> ReportDateMode.WEEK
    Period.MONTH -> ReportDateMode.MONTH
    Period.ALL, Period.CUSTOM -> ReportDateMode.RANGE
    else -> ReportDateMode.DAY
}

@Composable
internal fun ReportDateDialog(model: UsageModel, mode: ReportDateMode, onDismiss: () -> Unit) {
    val selection = model.selection
    val today = Dates.date(model.now, model.zone)
    val selectAnchor: (LocalDate) -> Unit = { date -> model.change { it.copy(anchor = date) } }
    if (mode == ReportDateMode.WEEK || mode == ReportDateMode.MONTH) {
        ComparisonPeriodPicker(
            selection.anchor, mode == ReportDateMode.MONTH,
            if (mode == ReportDateMode.MONTH) "统计月" else "统计周",
            onDismiss, selectAnchor, today
        )
        return
    }

    val range = mode == ReportDateMode.RANGE
    val initial = when {
        !range -> selection.anchor to selection.anchor
        selection.period == Period.CUSTOM -> selection.custom ?: (today to today)
        else -> {
            val window = Dates.window(selection.period, model.now, model.zone,
                earliest = model.display.first ?: model.now, anchor = selection.anchor)
            Dates.date(window.start, model.zone) to Dates.date(maxOf(window.start, window.end - 1), model.zone)
        }
    }
    ReportCalendarPicker(
        start = initial.first.coerceAtMost(today),
        end = initial.second.coerceAtMost(today),
        today = today,
        range = range,
        title = when {
            range -> "选择日期范围"
            selection.period == Period.DAYS7 || selection.period == Period.DAYS30 -> "选择截止日期"
            else -> "选择日期"
        },
        onDismiss = onDismiss
    ) { first, last ->
        if (range) model.change { it.copy(period = Period.CUSTOM, custom = first to last) }
        else selectAnchor(first)
        onDismiss()
    }
}

private val shortDate = DateTimeFormatter.ofPattern("yyyy/M/d")
private val spokenDate = DateTimeFormatter.ofPattern("yyyy年M月d日")

@Composable
internal fun ReportCalendarPicker(
    start: LocalDate,
    end: LocalDate,
    today: LocalDate,
    range: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onSelect: (LocalDate, LocalDate) -> Unit
) {
    val c = hindsightColors()
    var visible by rememberSaveable { mutableStateOf(YearMonth.from(end)) }
    var choosingMonth by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val first = pending
    fun pick(date: LocalDate) {
        when {
            !range -> onSelect(date, date)
            first == null -> pending = date
            else -> onSelect(minOf(first, date), maxOf(first, date))
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 420.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp), color = c.card
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Column(Modifier.padding(start = 4.dp, top = 6.dp, end = 4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, color = c.textStrong)
                        if (range) {
                            Text(
                                if (first == null) "${start.format(shortDate)} — ${end.format(shortDate)}"
                                else "${first.format(shortDate)} · 再点一天完成范围",
                                style = MaterialTheme.typography.bodyMedium, color = c.textMuted
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HindsightPillChip("今天", false) { onSelect(today, today) }
                        if (range) {
                            HindsightPillChip("最近7天", false) { onSelect(today.minusDays(6), today) }
                            HindsightPillChip("最近30天", false) { onSelect(today.minusDays(29), today) }
                        } else {
                            HindsightPillChip("昨天", false) { onSelect(today.minusDays(1), today.minusDays(1)) }
                        }
                    }
                    CalendarMonthHeader(visible, YearMonth.from(today), choosingMonth,
                        onNavigate = { visible = it }, onToggle = { choosingMonth = !choosingMonth })
                    if (choosingMonth) {
                        CalendarMonthGrid(visible.year, visible, YearMonth.from(today)) {
                            visible = it
                            choosingMonth = false
                        }
                    } else {
                        ReportMonthDays(visible, first ?: start, first ?: end, today, ::pick)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (range && first == null && !choosingMonth) {
                        Text("点选两天，可跨月", Modifier.padding(start = 4.dp),
                            style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun ReportMonthDays(
    month: YearMonth, start: LocalDate, end: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit
) {
    val c = hindsightColors()
    Row {
        "一二三四五六日".forEach {
            Text(it.toString(), Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }
    }
    val lead = month.atDay(1).dayOfWeek.value - 1
    val rows = (lead + month.lengthOfMonth() + 6) / 7
    repeat(rows) { row ->
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { column ->
                val day = row * 7 + column + 1 - lead
                if (day !in 1..month.lengthOfMonth()) {
                    Spacer(Modifier.weight(1f))
                } else {
                    val date = month.atDay(day)
                    val endpoint = date == start || date == end
                    val active = date in start..end
                    val enabled = date <= today
                    Box(
                        Modifier.weight(1f).heightIn(min = 44.dp)
                            .clickable(enabled = enabled, role = Role.Button) { onPick(date) }
                            .semantics {
                                contentDescription = date.format(spokenDate) + if (date == today) "，今天" else ""
                                selected = active
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (active && start != end) {
                            val round = 20.dp
                            val band = RoundedCornerShape(
                                topStart = if (date == start) round else 0.dp, bottomStart = if (date == start) round else 0.dp,
                                topEnd = if (date == end) round else 0.dp, bottomEnd = if (date == end) round else 0.dp
                            )
                            Spacer(Modifier.fillMaxWidth().height(34.dp).background(c.accentSoft, band))
                        }
                        val marker = when {
                            endpoint -> Modifier.background(c.accent, CircleShape)
                            date == today -> Modifier.border(1.dp, c.accent, CircleShape)
                            else -> Modifier
                        }
                        Box(Modifier.sizeIn(minWidth = 34.dp, minHeight = 34.dp).then(marker),
                            contentAlignment = Alignment.Center) {
                            Text(day.toString(), style = MaterialTheme.typography.labelLarge, color = when {
                                !enabled -> c.textFaint.copy(alpha = 0.6f)
                                endpoint -> MaterialTheme.colorScheme.onPrimary
                                active || date == today -> c.accentDeep
                                else -> c.textStrong
                            })
                        }
                    }
                }
            }
        }
    }
}
