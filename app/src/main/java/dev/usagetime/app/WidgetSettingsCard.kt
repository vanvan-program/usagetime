package dev.usagetime.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun WidgetSettingsCard() {
    val context = LocalContext.current
    val manager = remember(context) { AppWidgetManager.getInstance(context) }
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
                        manager.requestPinAppWidget(ComponentName(context, style.provider), null, null)
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
