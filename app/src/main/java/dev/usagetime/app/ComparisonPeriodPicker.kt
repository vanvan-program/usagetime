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
import androidx.compose.runtime.remember
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
    var visibleMonth by remember(initial, monthly) { mutableStateOf(YearMonth.from(initial)) }
    var choosingMonth by remember(monthly) { mutableStateOf(monthly) }
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        visibleMonth = if (choosingMonth) visibleMonth.minusYears(1) else visibleMonth.minusMonths(1)
                    }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                            if (choosingMonth) "上一年" else "上一月"
                        )
                    }
                    TextButton(
                        onClick = { if (!monthly) choosingMonth = !choosingMonth },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (choosingMonth) "${visibleMonth.year}年"
                            else "${visibleMonth.year}年${visibleMonth.monthValue}月"
                        )
                        if (!monthly) Icon(Icons.Outlined.ArrowDropDown, "切换年月选择")
                    }
                    IconButton(
                        onClick = {
                            visibleMonth = if (choosingMonth) visibleMonth.plusYears(1).coerceAtMost(currentMonth)
                            else visibleMonth.plusMonths(1)
                        },
                        enabled = if (choosingMonth) visibleMonth.year < today.year else visibleMonth < currentMonth
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            if (choosingMonth) "下一年" else "下一月"
                        )
                    }
                }
                if (choosingMonth) {
                    (1..12).chunked(3).forEach { months ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            months.forEach { month ->
                                val candidate = YearMonth.of(visibleMonth.year, month)
                                val selected = if (monthly) candidate == YearMonth.from(initial) else candidate == visibleMonth
                                androidx.compose.material3.FilterChip(
                                    selected = selected,
                                    onClick = {
                                        if (monthly) select(candidate.atDay(1))
                                        else {
                                            visibleMonth = candidate
                                            choosingMonth = false
                                        }
                                    },
                                    enabled = candidate <= currentMonth,
                                    label = { Text("${month}月") },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                )
                            }
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
