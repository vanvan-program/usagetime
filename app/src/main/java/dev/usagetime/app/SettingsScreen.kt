package dev.usagetime.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.usagetime.core.UsageMetric
import java.time.Instant
import java.time.format.DateTimeFormatter

private val LAST_SAVED_FORMATTER = DateTimeFormatter.ofPattern("yyyy.M.d HH:mm")
private val COPY_SHORT_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val COPY_SECOND_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm:ss")

@Composable
internal fun SettingsScreen(
    model: UsageModel,
    onPermission: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onCsv: () -> Unit,
    onDiagnostics: () -> Unit,
    onRebuild: () -> Unit,
    onSafety: () -> Unit,
    onCompressedExport: () -> Unit
) {
    val c = hindsightColors()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 28.dp)
    ) {
        HindsightPageTitle(
            title = "设置",
            subtitle = "记录方式、数据管理与外观。",
            eyebrow = "偏好"
        )
        Spacer(Modifier.height(14.dp))
        if (model.demo) {
            HindsightCard(
                background = Brush.linearGradient(
                    listOf(c.accentSoft.copy(alpha = 0.9f), c.peachSoft.copy(alpha = 0.72f))
                )
            ) {
                Text("正在体验示例数据", style = MaterialTheme.typography.titleMedium, color = c.textStrong)
                Spacer(Modifier.height(4.dp))
                Text("示例不会写入真实记录数据库。", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                TextButton(
                    onClick = {
                        model.useDemo(false)
                        model.refresh()
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("切换真实记录", color = c.accent)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        SectionLabel("记录与统计")
        HindsightCard {
            MetricControls(model)
            Spacer(Modifier.height(8.dp))
            HindsightDivider()
            SettingRow(
                title = "显示确定时间",
                subtitle = "控制累计是否计入已确认时间；未知与暂定区间始终展示",
                trailing = {
                    Switch(
                        checked = model.showConfirmed,
                        onCheckedChange = { model.showConfirmed(it) },
                        colors = hindsightSwitchColors()
                    )
                }
            )
            HindsightDivider()
            SettingRow(
                title = "自动补记",
                subtitle = "约每 6 小时保存，打开时补记",
                trailing = {
                    Switch(
                        checked = model.recording,
                        onCheckedChange = { model.enableRecording(it) },
                        colors = hindsightSwitchColors()
                    )
                }
            )
            HindsightDivider()
            SettingRow(
                title = "使用情况访问",
                subtitle = if (model.permitted) "已授权" else "尚未授权",
                onClick = onPermission
            )
            HindsightDivider()
            SettingRow(
                title = "采集诊断",
                subtitle = "查看事件缺口、时钟和重试记录",
                onClick = onDiagnostics
            )
            HindsightDivider()
            SettingRow(
                title = "重算已保存历史",
                subtitle = "只重算已有事件；保留旧版与导入历史",
                onClick = onRebuild,
                enabled = !model.demo
            )
            Spacer(Modifier.height(10.dp))
            model.display.last?.let { last ->
                Text(
                    "最近保存：${Instant.ofEpochMilli(last).atZone(model.zone).format(LAST_SAVED_FORMATTER)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textFaint
                )
            }
            Text(
                "${model.zone.id} · 每周从周一开始",
                style = MaterialTheme.typography.labelSmall,
                color = c.textFaint
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "后台任务受系统省电调度影响；长时间未补记或权限中断时，部分记录可能无法恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = c.textMuted
            )
        }

        SectionLabel("数据管理")
        HindsightCard {
            SettingRow(
                title = "数据安全与占用",
                subtitle = "查看占用、本机安全副本和恢复",
                onClick = onSafety,
                enabled = !model.demo
            )
            model.safetyInfo.warning?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = c.danger)
            }
            HindsightDivider()
            SettingRow(
                title = "导出完整备份",
                subtitle = "JSON · 保存已记录历史",
                onClick = onExport,
                enabled = !model.demo
            )
            HindsightDivider()
            SettingRow(
                title = "导出压缩完整备份",
                subtitle = "JSON.GZ · 适合长期归档",
                onClick = onCompressedExport,
                enabled = !model.demo
            )
            HindsightDivider()
            SettingRow(
                title = "导入备份",
                subtitle = "JSON / JSON.GZ · 合并历史，保留当前记录",
                onClick = onImport
            )
            HindsightDivider()
            SettingRow(
                title = "导出当前报表",
                subtitle = "CSV · 当前范围的应用时长",
                onClick = onCsv,
                enabled = !model.demo
            )
        }

        SectionLabel("电脑数据")
        HindsightCard {
            SettingRow(
                title = "显示电脑数据",
                subtitle = "开启后显示并从云端拉取电脑用时",
                trailing = {
                    Switch(
                        checked = model.showDesktopData,
                        onCheckedChange = model::showDesktopData,
                        colors = hindsightSwitchColors()
                    )
                }
            )
        }

        SectionLabel("自动同步")
        if (!model.demo) PhoneSyncSettingsCard(model.showDesktopData)

        SectionLabel("外观")
        HindsightCard {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
                    HindsightPillChip(label = label, selected = model.appearance == key) { model.theme(key) }
                }
            }
        }

        SectionLabel("桌面小组件")
        WidgetSettingsCard()

        SectionLabel("关于用时记")
        AboutUpdateCard(
            onEnterDemo = if (!model.demo) { { model.useDemo(true) } } else null,
            onMessage = { model.message = it }
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun hindsightSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = hindsightColors().accent,
    uncheckedThumbColor = hindsightColors().textFaint,
    uncheckedTrackColor = hindsightColors().fillStrong,
    uncheckedBorderColor = hindsightColors().border
)

@Composable
private fun MetricControls(model: UsageModel) {
    val c = hindsightColors()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        UsageMetric.entries.forEach { metric ->
            HindsightChip(
                label = metric.label,
                selected = model.selection.metric == metric && !model.selection.legacy,
                onClick = { model.change { it.copy(metric = metric, legacy = false) } }
            )
        }
        HindsightChip(
            label = "旧版历史",
            selected = model.selection.legacy,
            onClick = { model.change { it.copy(legacy = true) } }
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        if (model.selection.legacy) {
            "保留的旧算法记录；不能恢复为确定的双指标。"
        } else if (model.selection.metric == UsageMetric.PRIMARY) {
            "多应用同时 resumed 且无焦点证据时，归属为未知。"
        } else {
            "不同应用允许重叠；生命周期候选不等于已证明的窗口可见。"
        },
        style = MaterialTheme.typography.labelSmall,
        color = c.textMuted
    )
}

@Composable
internal fun StorageSafetyDialog(model: UsageModel, onDismiss: () -> Unit) {
    var action by remember { mutableStateOf<Int?>(null) }
    var selectedTime by remember { mutableStateOf<Long?>(null) }
    val info = model.safetyInfo
    fun size(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)
    val date = info.latest?.let {
        Instant.ofEpochMilli(it).atZone(model.zone).format(COPY_SHORT_FORMATTER)
    } ?: "尚无"
    if (action != null) {
        val restoreDate = selectedTime?.let {
            Instant.ofEpochMilli(it).atZone(model.zone).format(COPY_SECOND_FORMATTER)
        } ?: date
        val selected = action!!
        AlertDialog(
            onDismissRequest = { action = null },
            title = {
                Text(
                    when (selected) {
                        1 -> "恢复本机安全副本"
                        2 -> "撤销上次恢复"
                        else -> "删除恢复前副本"
                    }
                )
            },
            text = {
                Text(
                    when (selected) {
                        1 -> "将恢复到 $restoreDate 的副本。当前数据库会保留以供撤销，较新的使用事件需要重新补记。"
                        2 -> "将切回上次恢复前的数据，当前数据库也会保留。只有通过校验才会切换。"
                        else -> "将永久删除上次恢复前的副本，之后无法撤销该次恢复。其中可能有当前库没有的较新记录，请先确认已另行保存。当前使用记录和安全副本不会删除。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.safetyAction(selected, selectedTime)
                        onDismiss()
                    }
                ) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("取消") } }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("数据安全与占用") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text("数据库及恢复文件：${size(info.databaseBytes)}")
                    Text("安全副本：${info.snapshots} 份 · ${size(info.snapshotBytes)}")
                    Text("设备可用空间：${size(info.freeBytes)}")
                    Text("最近安全副本：$date")
                    val exported = info.lastExport?.let {
                        Instant.ofEpochMilli(it).atZone(model.zone).format(COPY_SHORT_FORMATTER)
                    } ?: "尚未记录"
                    Text("上次导出完整备份：$exported")
                    Text(
                        "约每 12 小时成功补记后创建校验副本，保留两份，优先保留不同版本；实际执行受系统调度影响。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        "本机副本也会随卸载或清除数据而删除。请定期将完整备份导出到应用外。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    info.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton(
                        enabled = !model.busy,
                        onClick = {
                            model.safetyAction(0)
                            onDismiss()
                        }
                    ) { Text("立即创建安全副本") }
                    info.copies.forEachIndexed { index, time ->
                        val copyLabel = Instant.ofEpochMilli(time).atZone(model.zone)
                            .format(COPY_SECOND_FORMATTER)
                        TextButton(
                            enabled = !model.busy && !info.canUndo,
                            onClick = {
                                selectedTime = time
                                action = 1
                            }
                        ) {
                            Text("恢复${if (index == 0) "最新" else "上一份"}副本 · $copyLabel")
                        }
                    }
                    if (info.canUndo) {
                        TextButton(enabled = !model.busy, onClick = { action = 2 }) { Text("撤销上次恢复") }
                        TextButton(enabled = !model.busy, onClick = { action = 3 }) { Text("删除恢复前副本") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }
}
