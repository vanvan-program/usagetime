package dev.usagetime.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.usagetime.app.sync.DesktopAppUse
import dev.usagetime.app.sync.DesktopDay
import dev.usagetime.app.sync.DesktopDevice
import dev.usagetime.app.sync.DesktopIndex
import dev.usagetime.app.sync.DesktopSync
import dev.usagetime.app.sync.PhoneSyncSettings
import dev.usagetime.core.durationText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun DesktopUsageScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableStateOf(DesktopIndex()) }
    var deviceId by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var day by remember { mutableStateOf<DesktopDay?>(null) }
    var pulling by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var lastSuccess by remember { mutableStateOf(0L) }
    var revision by remember { mutableStateOf(0) }
    var weekTotals by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val enabled = remember { runCatching { PhoneSyncSettings(context).load().enabled }.getOrDefault(false) }
    val today = LocalDate.now().toString()
    val device = index.device(deviceId)

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) { DesktopSync.readIndex(context) }
        index = loaded
        if (deviceId.isBlank()) deviceId = loaded.latest()?.id.orEmpty()
        val selected = deviceId.ifBlank { loaded.latest()?.id.orEmpty() }
        day = withContext(Dispatchers.IO) {
            if (selected.isBlank()) null else DesktopSync.readDay(context, selected, date)
        }
        weekTotals = withContext(Dispatchers.IO) {
            val summaries = loaded.device(selected)?.days.orEmpty()
            (6 downTo 0).associate { offset ->
                val value = LocalDate.parse(date).minusDays(offset.toLong()).toString()
                value to if (summaries.containsKey(value)) {
                    DesktopSync.readDay(context, selected, value)?.recordedMillis ?: 0L
                } else {
                    -1L
                }
            }
        }
        status = DesktopSync.status(context)
        lastSuccess = DesktopSync.lastSuccess(context)
        loading = false
    }

    suspend fun pull() {
        pulling = true
        error = null
        try {
            try { DesktopSync.pull(context) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "无法拉取电脑用时" }
            revision++
            reload()
        } finally { pulling = false }
    }

    LaunchedEffect(Unit) {
        loading = true
        reload()
        if (enabled && System.currentTimeMillis() - lastSuccess > 10 * 60_000) pull()
    }
    LaunchedEffect(deviceId, date, revision) {
        if (deviceId.isNotBlank()) {
            day = withContext(Dispatchers.IO) { DesktopSync.readDay(context, deviceId, date) }
        }
        weekTotals = withContext(Dispatchers.IO) {
            val summaries = index.device(deviceId)?.days.orEmpty()
            (6 downTo 0).associate { offset ->
                val value = LocalDate.parse(date).minusDays(offset.toLong()).toString()
                value to if (summaries.containsKey(value)) {
                    DesktopSync.readDay(context, deviceId, value)?.recordedMillis ?: 0L
                } else {
                    -1L
                }
            }
        }
        status = DesktopSync.status(context)
        lastSuccess = DesktopSync.lastSuccess(context)
    }

    val summaries = device?.days.orEmpty()
    val week = (6 downTo 0).map { offset ->
        val value = LocalDate.parse(date).minusDays(offset.toLong()).toString()
        value to summaries[value]
    }
    val maximum = maxOf(1L, weekTotals.values.maxOrNull() ?: 1L)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HindsightPageTitle(
                title = "电脑用时",
                subtitle = "来自 Hindsight 修改版 · 独立统计，不与手机用时相加",
                eyebrow = "云端同步"
            )
        }
        item {
            HindsightCard(contentPadding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                !enabled -> "云端同步未开启"
                                pulling -> "正在拉取电脑用时…"
                                error != null -> "拉取失败，已保留上次缓存"
                                else -> status
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = hindsightColors().textStrong
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (lastSuccess > 0) "上次成功 " + timeLabel(lastSuccess) else "尚未成功拉取过",
                            style = MaterialTheme.typography.labelSmall,
                            color = hindsightColors().textFaint
                        )
                    }
                    TextButton(
                        enabled = enabled && !pulling,
                        onClick = { scope.launch { pull() } },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (pulling) "同步中" else "立即拉取")
                    }
                }
                (error ?: DesktopSync.error(context))?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (index.devices.size > 1) {
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    index.devices.forEach { item ->
                        HindsightChip(
                            label = item.name,
                            selected = item.id == deviceId,
                            onClick = { deviceId = item.id }
                        )
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassIconButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "前一天", onClick = {
                    date = LocalDate.parse(date).minusDays(1).toString()
                })
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        dateLabel(date),
                        style = MaterialTheme.typography.titleSmall,
                        color = hindsightColors().textStrong
                    )
                    Text(
                        device?.let { "${it.name} · ${platformLabel(it.platform)}" } ?: "等待电脑数据",
                        style = MaterialTheme.typography.labelSmall,
                        color = hindsightColors().textMuted
                    )
                }
                GlassIconButton(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    "后一天",
                    onClick = { date = LocalDate.parse(date).plusDays(1).toString() },
                    enabled = date < today
                )
            }
        }
        if (index.devices.isEmpty()) {
            item {
                HindsightCard {
                    Text("还没有收到电脑用时", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (!enabled) {
                            "先在「设置 → 云端同步」填写 WebDAV 与配对码并启用，然后在 Hindsight 修改版里打开同一个配对码。"
                        } else {
                            "在 Hindsight 修改版侧栏打开「手机用时」，填写相同 WebDAV 账号和配对码并保存。电脑数据会加密发布，手机自动拉取。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = hindsightColors().textMuted
                    )
                }
            }
        } else {
            item {
                HindsightCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "电脑应用用时",
                                style = MaterialTheme.typography.labelMedium,
                                color = hindsightColors().textMuted
                            )
                            Spacer(Modifier.height(4.dp))
                            BigTimeValue(day?.recordedMillis ?: 0, available = day != null)
                        }
                        HindsightBadge(platformLabel(device?.platform.orEmpty()))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            day == null -> "这一天还没有同步到手机，下面的信息不代表零用时。"
                            day!!.recordedMillis == 0L -> "Hindsight 在这一天没有累计到应用用时。"
                            else -> "记录区间 " + clock(day!!.firstMillis, day!!.zone) + " – " +
                                clock(day!!.lastMillis, day!!.zone) + " · " + day!!.apps.size + " 个应用"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = hindsightColors().textMuted
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "只统计 Hindsight 实际记录的桌面应用；未运行、被排除或未记录的时间不会显示为零。电脑与手机的时间不重叠求和。",
                        style = MaterialTheme.typography.labelSmall,
                        color = hindsightColors().textFaint
                    )
                }
            }
            item {
                HindsightCard {
                    HindsightCardTitle("最近七天", "柱高按当天电脑用时")
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        week.forEach { (value, hash) ->
                            val amount = if (hash == null) null else weekTotals[value]?.takeIf { it >= 0 } ?: 0L
                            Column(
                                Modifier.weight(1f).clickable { date = value },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    amount?.let { durationText(it) } ?: "—",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (value == date) hindsightColors().accent else hindsightColors().textMuted,
                                    maxLines = 1
                                )
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    Modifier.height(72.dp).width(18.dp).clip(CircleShape)
                                        .background(hindsightColors().fill),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    if (amount != null && amount > 0) {
                                        Box(
                                            Modifier.fillMaxWidth()
                                                .fillMaxHeight((amount.toFloat() / maximum).coerceIn(0.03f, 1f))
                                                .clip(CircleShape)
                                                .background(
                                                    if (value == date) hindsightColors().accent
                                                    else hindsightColors().accent.copy(alpha = 0.45f)
                                                )
                                        )
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    value.substring(5).replace('-', '/'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = hindsightColors().textFaint
                                )
                            }
                        }
                    }
                }
            }
            if (day != null && day!!.apps.isNotEmpty()) {
                item {
                    HindsightCard {
                        HindsightCardTitle("应用排行", "电脑端应用，按当天累计排序")
                        Spacer(Modifier.height(6.dp))
                        day!!.apps.take(20).forEachIndexed { position, app ->
                            if (position > 0) HindsightDivider()
                            DesktopAppRow(position + 1, app, day!!.recordedMillis)
                        }
                    }
                }
                item {
                    HindsightCard {
                        HindsightCardTitle("分类分布", "按 Hindsight 的分类规则归类")
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)
                                .background(hindsightColors().fill)
                        ) {
                            day!!.categories.forEach { category ->
                                Box(
                                    Modifier.weight(category.millis.coerceAtLeast(0).toFloat().coerceAtLeast(0.001f))
                                        .fillMaxHeight()
                                        .background(parseColor(category.color, hindsightColors().accent))
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        day!!.categories.forEachIndexed { position, category ->
                            if (position > 0) Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(8.dp).clip(CircleShape)
                                        .background(parseColor(category.color, hindsightColors().accent))
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    category.label,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = hindsightColors().textStrong
                                )
                                Text(
                                    durationText(category.millis) + " · " +
                                        percent(category.millis, day!!.recordedMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = hindsightColors().textMuted
                                )
                            }
                        }
                    }
                }
            } else if (day != null) {
                item { EmptyHint("这一天没有已累计的电脑应用用时。") }
            } else if (!loading) {
                item { EmptyHint("这一天尚未同步；可以点上方「立即拉取」重试。") }
            }
        }
    }
}

@Composable
private fun DesktopAppRow(rank: Int, app: DesktopAppUse, total: Long) {
    val c = hindsightColors()
    val accent = parseColor(app.color, appAccent(rank - 1))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "$rank",
            Modifier.width(16.dp),
            style = MaterialTheme.typography.labelSmall,
            color = c.textFaint,
            textAlign = TextAlign.Center
        )
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                app.label.take(1).uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = accent
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textStrong,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RatioBar(if (total > 0) app.millis.toFloat() / total else 0f, accent, Modifier.width(44.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    app.categoryLabel + " · " + percent(app.millis, total) + " · " + app.sessions + " 次记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            durationText(app.millis),
            Modifier.widthIn(min = 60.dp),
            style = MaterialTheme.typography.labelLarge,
            color = c.textStrong,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End
        )
    }
}

@Composable
internal fun CompactDesktopCard(model: UsageModel) {
    val context = LocalContext.current
    var device by remember { mutableStateOf<DesktopDevice?>(null) }
    var day by remember { mutableStateOf<DesktopDay?>(null) }
    LaunchedEffect(model.now, model.desktopRevision) {
        val index = withContext(Dispatchers.IO) { DesktopSync.readIndex(context) }
        val latest = index.latest()
        device = latest
        day = withContext(Dispatchers.IO) {
            latest?.let { DesktopSync.readDay(context, it.id, LocalDate.now().toString()) }
        }
    }
    val value = device ?: return
    HindsightCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                    .background(hindsightColors().accentSoft),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    Icons.Outlined.Computer,
                    contentDescription = null,
                    tint = hindsightColors().accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("电脑用时", style = MaterialTheme.typography.titleSmall, color = hindsightColors().textStrong)
                Text(
                    value.name + " · 今天 " + (day?.let { durationText(it.recordedMillis) } ?: "尚未同步"),
                    style = MaterialTheme.typography.labelSmall,
                    color = hindsightColors().textMuted
                )
            }
            TextButton(onClick = { model.navigate(5) }) { Text("查看") }
        }
    }
}

private fun parseColor(text: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(text)) }.getOrDefault(fallback)

private fun platformLabel(value: String): String = when (value) {
    "win", "windows" -> "Windows"
    "mac", "macos" -> "macOS"
    "linux" -> "Linux"
    "" -> "电脑"
    else -> value
}

private fun dateLabel(value: String): String = runCatching {
    LocalDate.parse(value).format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
}.getOrDefault(value)

private fun clock(millis: Long, zone: String): String {
    if (millis <= 0) return "—"
    val z = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.systemDefault())
    return Instant.ofEpochMilli(millis).atZone(z).format(DateTimeFormatter.ofPattern("HH:mm"))
}

private fun timeLabel(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M/d HH:mm"))

private fun percent(value: Long, total: Long): String =
    if (total > 0) "%.1f%%".format(Locale.CHINA, value.toDouble() / total * 100) else "0%"
