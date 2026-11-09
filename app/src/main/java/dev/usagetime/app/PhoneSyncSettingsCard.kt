package dev.usagetime.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.usagetime.app.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PhoneSyncSettingsCard(showDesktopData: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var error by remember { mutableStateOf<String?>(null) }
    val initial = remember {
        runCatching { PhoneSyncSettings(context).load() }.getOrElse {
            error = "无法读取同步配置，请重新填写"
            PhoneSyncConfig()
        }
    }
    var config by remember { mutableStateOf(initial) }
    var expanded by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(initial.enabled) }
    var status by remember { mutableStateOf("") }
    var desktopStatus by remember { mutableStateOf("") }
    var pulling by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(showDesktopData) {
        while (true) {
            val prefs = context.getSharedPreferences("phone-sync-status", android.content.Context.MODE_PRIVATE)
            val last = prefs.getLong("lastSuccess", 0)
            status = (prefs.getString("message", "尚未同步") ?: "尚未同步") +
                if (last > 0) "\n上次成功：" + java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")) else ""
            if (showDesktopData) {
                val desktopLast = DesktopSync.lastSuccess(context)
                desktopStatus = DesktopSync.status(context) +
                    if (desktopLast > 0) " · " + java.time.Instant.ofEpochMilli(desktopLast)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")) else ""
            }
            delay(2000)
        }
    }
    HindsightCard {
        Text("云端同步 · Hindsight", style = MaterialTheme.typography.titleMedium)
        Text(
            if (showDesktopData) "通过 WebDAV 双向加密同步：手机上传用时，拉取电脑 Hindsight 修改版发布的应用用时。默认每小时在 Wi-Fi 下同步，系统省电可能延迟。"
            else "通过 WebDAV 加密上传手机用时，默认每小时在 Wi-Fi 下同步，系统省电可能延迟。开启「显示电脑数据」后才会拉取电脑用时。",
            style = MaterialTheme.typography.bodySmall)
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (showDesktopData) Text("电脑用时：$desktopStatus", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起配置" else "配置自动同步") }
        if (expanded) {
            OutlinedTextField(config.url, { config = config.copy(url = it); saved = false },
                label = { Text("WebDAV HTTPS 地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(config.user, { config = config.copy(user = it); saved = false },
                label = { Text("账号") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(config.password, { config = config.copy(password = it); saved = false },
                label = { Text("应用专用密码") }, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(config.deviceName, { config = config.copy(deviceName = it); saved = false },
                label = { Text("手机名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(config.pairingKey, { config = config.copy(pairingKey = it); saved = false },
                label = { Text("配对码（多台手机需填写相同值）") }, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(config.enabled, { config = config.copy(enabled = it); saved = false })
                Text("启用自动上传", modifier = Modifier.padding(top = 14.dp))
            }
            Text("配对码是解密密钥，只粘贴到自己的 Hindsight 修改版。请保存好；丢失后无法解密云端文件。",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    runCatching { PhoneSyncSettings(context).save(config) }
                        .onSuccess { error = null; saved = true }
                        .onFailure { error = it.message }
                }) { Text(if (saved) "已保存" else "保存配置") }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(config.pairingKey))
                }) { Text("复制配对码") }
            }
            Button(enabled = saved && config.enabled, onClick = { PhoneSyncSettings.now(context) }) { Text("立即同步") }
            TextButton(enabled = saved && config.enabled, onClick = { PhoneSyncSettings.now(context, republish = true) }) {
                Text("重新上传全部历史")
            }
            if (showDesktopData) Button(
                enabled = saved && config.enabled && !pulling,
                onClick = {
                    scope.launch {
                        pulling = true
                        try { withContext(Dispatchers.IO) { DesktopSync.pull(context) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                        finally { pulling = false }
                    }
                }
            ) { Text(if (pulling) "正在拉取电脑用时…" else "立即拉取电脑用时") }
            Text("关闭后停止后续任务，不删除云端历史。手机与电脑分别展示、不合并求和；不是完整备份或手机间恢复。",
                style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
