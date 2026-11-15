package dev.usagetime.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.usagetime.core.UsageReport
import dev.usagetime.core.durationText

@Composable
internal fun HindsightToolbar(
    model: UsageModel,
    showCalendar: Boolean,
    onCalendar: () -> Unit,
    onRefresh: () -> Unit
) {
    val c = hindsightColors()
    val headerTop = c.accentSoft.copy(alpha = if (c.dark) 0.5f else 0.55f).compositeOver(c.canvas)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(headerTop, c.canvas)))
            .heightIn(min = 58.dp)
            .padding(start = 18.dp, end = 14.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark(34.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "用时记",
            style = MaterialTheme.typography.titleMedium,
            color = c.textStrong,
            fontWeight = FontWeight.SemiBold
        )
        if (model.demo) {
            Spacer(Modifier.width(8.dp))
            HindsightBadge("示例数据")
        }
        Spacer(Modifier.weight(1f))
        if (showCalendar) {
            GlassIconButton(AppIcons.CalendarMonth, "选择自定义日期", onCalendar)
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.control)
                .border(1.dp, c.border, RoundedCornerShape(12.dp))
                .clickable(enabled = !model.busy, onClick = onRefresh),
            contentAlignment = Alignment.Center
        ) {
            if (model.busy) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = c.accent
                )
            } else {
                Icon(
                    Icons.Outlined.Refresh,
                    "补记与刷新",
                    tint = c.textRegular,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
internal fun HindsightBottomNavigation(
    labels: List<String>,
    icons: List<ImageVector>,
    selected: Int,
    onNavigate: (Int) -> Unit
) {
    val c = hindsightColors()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(26.dp),
        color = c.nav,
        border = BorderStroke(1.dp, c.border),
        shadowElevation = 18.dp
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.height(68.dp)
        ) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                NavigationBarItem(
                    selected = active,
                    onClick = { onNavigate(index) },
                    icon = {
                        Box(
                            Modifier
                                .width(44.dp)
                                .height(29.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) c.accentSoft else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                icons[index],
                                contentDescription = null,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    },
                    label = {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.accent,
                        selectedTextColor = c.accent,
                        unselectedIconColor = c.textFaint,
                        unselectedTextColor = c.textFaint,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    }
}

@Composable
internal fun HindsightNavigationRail(
    labels: List<String>,
    icons: List<ImageVector>,
    selected: Int,
    onNavigate: (Int) -> Unit
) {
    val c = hindsightColors()
    Surface(
        modifier = Modifier
            .statusBarsPadding()
            .padding(14.dp)
            .width(92.dp)
            .fillMaxHeight(),
        shape = RoundedCornerShape(26.dp),
        color = c.nav,
        border = BorderStroke(1.dp, c.border),
        shadowElevation = 16.dp
    ) {
        NavigationRail(
            containerColor = Color.Transparent,
            header = {
                Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                    BrandMark(38.dp)
                }
            }
        ) {
            Spacer(Modifier.height(8.dp))
            labels.forEachIndexed { index, label ->
                val active = selected == index
                NavigationRailItem(
                    selected = active,
                    onClick = { onNavigate(index) },
                    icon = {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) c.accentSoft else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icons[index], null, modifier = Modifier.size(20.dp))
                        }
                    },
                    label = {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                    },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = c.accent,
                        selectedTextColor = c.accent,
                        unselectedIconColor = c.textFaint,
                        unselectedTextColor = c.textFaint,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    }
}

@Composable
internal fun ErrorBanner(error: String, onRetry: () -> Unit) {
    val c = hindsightColors()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = c.danger.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, c.danger.copy(alpha = 0.16f))
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = c.danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = c.textStrong)
                Text("已保存的记录会继续尝试读取。", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            }
            TextButton(onClick = onRetry) { Text("重试", color = c.danger) }
        }
    }
}

@Composable
internal fun PermissionScreen(model: UsageModel, onGrant: () -> Unit) {
    val c = hindsightColors()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HindsightCard(
            background = Brush.linearGradient(
                listOf(c.accentSoft.copy(alpha = 0.9f), c.peachSoft.copy(alpha = 0.8f))
            ),
            contentPadding = 24.dp
        ) {
            BrandMark(52.dp)
            Spacer(Modifier.height(22.dp))
            Text(
                "开始记录你的用时",
                style = MaterialTheme.typography.headlineLarge,
                color = c.textStrong
            )
            Spacer(Modifier.height(9.dp))
            Text(
                "请在系统页面找到“用时记”，开启使用情况访问权限。",
                style = MaterialTheme.typography.bodyLarge,
                color = c.textRegular
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "授权后会导入系统仍保留的近期记录，并持续保存到本机。更早的记录无法凭空恢复，图表会明确留空。",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HindsightBadge("无需账号")
                HindsightBadge("同步自愿开启")
            }
        }
        Button(
            onClick = onGrant,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)
        ) {
            Text("前往授权", fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = { model.useDemo(true) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, c.accent.copy(alpha = 0.28f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.accent)
        ) {
            Text("先体验示例数据", fontWeight = FontWeight.SemiBold)
        }
        Text(
            "无需账号 · 默认本机保存 · 云端同步自愿开启",
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = c.textFaint,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun QualityStatus(report: UsageReport, label: String = "数据可信状态") {
    var showHelp by remember(label) { mutableStateOf(false) }
    val c = hindsightColors()
    report.quality?.let { quality ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                label,
                if (quality.unknown > 0 || !report.fullyCovered) c.warning else c.success
            )
            Spacer(Modifier.width(5.dp))
            IconButton(
                onClick = { showHelp = true },
                modifier = Modifier.size(26.dp).semantics { contentDescription = "查看${label}说明" }
            ) {
                Icon(Icons.Outlined.Info, null, tint = c.textFaint, modifier = Modifier.size(14.dp))
            }
        }
        Text(
            "已确认 ${durationText(quality.confirmed)} · 候选估计 ${durationText(quality.inferred)}",
            style = MaterialTheme.typography.labelSmall,
            color = c.textMuted
        )
        Text(
            "无法确定 ${durationText(quality.unknown)} · 未闭合 ${durationText(quality.provisional)}",
            style = MaterialTheme.typography.labelSmall,
            color = c.textFaint
        )
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("$label 说明") },
            text = {
                Column(
                    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("已确认：有足够证据，可直接计入累计的时长。")
                    Text("候选估计：根据应用生命周期推断，但尚未证明为实际使用的时长。")
                    Text("无法确定：证据不足，暂时无法归属到具体应用的时长。")
                    Text("未闭合：当前周期尚未结算的区间，不计入累计。")
                    Text("候选估计可能与无法确定区间重叠，不能相加；候选也不代表已证明的实际用时。", color = c.textMuted)
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } }
        )
    }
}

@Composable
internal fun DiagnosticDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("采集诊断（仅本机）") },
        text = {
            Text(
                text,
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
