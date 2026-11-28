package dev.usagetime.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
internal fun ComparisonPeriodPicker(
    initial: LocalDate,
    monthly: Boolean,
    label: String,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
    today: LocalDate = LocalDate.now()
) {
    var visibleMonth by rememberSaveable(initial, monthly) { mutableStateOf(YearMonth.from(initial)) }
    var choosingMonth by rememberSaveable(monthly) { mutableStateOf(monthly) }
    val selectedStart = comparisonPeriodStart(initial, monthly)
    val currentMonth = YearMonth.from(today)
    val colors = hindsightColors()
    fun select(date: LocalDate) {
        onSelect(comparisonPeriodAnchor(date, monthly, today))
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text("选择$label") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { select(today) }) { Text(if (monthly) "本月" else "本周") }
                    TextButton(onClick = { select(if (monthly) today.minusMonths(1) else today.minusWeeks(1)) }) {
                        Text(if (monthly) "上月" else "上周")
                    }
                }
                CalendarMonthHeader(
                    visibleMonth, currentMonth, choosingMonth,
                    onNavigate = { visibleMonth = it },
                    onToggle = if (monthly) null else ({ choosingMonth = !choosingMonth })
                )
                if (choosingMonth) {
                    CalendarMonthGrid(visibleMonth.year, if (monthly) YearMonth.from(initial) else visibleMonth, currentMonth) { month ->
                        if (monthly) select(month.atDay(1))
                        else {
                            visibleMonth = month
                            choosingMonth = false
                        }
                    }
                } else {
                    Text("周一至周日，点击整周选择", style = MaterialTheme.typography.bodySmall)
                    comparisonWeeks(visibleMonth, today).forEach { start ->
                        val end = start.plusDays(6)
                        val pattern = if (start.year != end.year) "yyyy/M/d" else "M/d"
                        val formatter = DateTimeFormatter.ofPattern(pattern)
                        androidx.compose.material3.FilterChip(
                            selected = start == selectedStart,
                            onClick = { select(start) },
                            label = { Text("${start.format(formatter)} – ${end.format(formatter)}") },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun CalendarMonthHeader(
    visible: YearMonth,
    latest: YearMonth,
    choosingMonth: Boolean,
    onNavigate: (YearMonth) -> Unit,
    onToggle: (() -> Unit)?
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onNavigate(if (choosingMonth) visible.minusYears(1) else visible.minusMonths(1)) }) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, if (choosingMonth) "上一年" else "上一月")
        }
        val label = if (choosingMonth) "${visible.year}年" else "${visible.year}年${visible.monthValue}月"
        androidx.compose.foundation.layout.Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (onToggle == null) Text(label, style = MaterialTheme.typography.titleSmall)
            else TextButton(onClick = onToggle) {
                Text(label)
                Icon(Icons.Outlined.ArrowDropDown, "切换年月选择")
            }
        }
        IconButton(
            onClick = { onNavigate(if (choosingMonth) visible.plusYears(1).coerceAtMost(latest) else visible.plusMonths(1)) },
            enabled = if (choosingMonth) visible.year < latest.year else visible < latest
        ) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, if (choosingMonth) "下一年" else "下一月")
        }
    }
}

@Composable
internal fun CalendarMonthGrid(year: Int, selected: YearMonth, latest: YearMonth, onSelect: (YearMonth) -> Unit) {
    (1..12).chunked(3).forEach { months ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            months.forEach { number ->
                val month = YearMonth.of(year, number)
                androidx.compose.material3.FilterChip(
                    selected = month == selected,
                    onClick = { onSelect(month) },
                    enabled = month <= latest,
                    label = { Text("${number}月") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                )
            }
        }
    }
}
