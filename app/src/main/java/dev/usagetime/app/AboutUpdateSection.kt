package dev.usagetime.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.usagetime.app.sync.AppUpdateChecker
import dev.usagetime.app.sync.AppUpdateInfo
import dev.usagetime.app.sync.UpdateCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun openExternalUrl(
    context: Context,
    url: String,
    onFailure: (String) -> Unit
) {
    if (!AppUpdateChecker.isAllowedExternalUrl(url)) {
        onFailure("无法打开不受信任的链接")
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        onFailure("未找到可用于打开链接的浏览器")
    }
}

@Composable
internal fun AboutUpdateCard(
    onEnterDemo: (() -> Unit)?,
    onMessage: (String) -> Unit
) {
    val c = hindsightColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAboutDialog by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("手动点击时从 GitHub Releases 检查新版本") }
    var statusError by remember { mutableStateOf(false) }
    var availableRelease by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var dialogRelease by remember { mutableStateOf<AppUpdateInfo?>(null) }

    fun triggerCheck() {
        if (checking) return
        scope.launch {
            checking = true
            statusError = false
            statusText = "正在连接 GitHub 检查更新…"
            try {
                when (val result = AppUpdateChecker.checkLatestReleaseSuspend(BuildConfig.VERSION_NAME)) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        availableRelease = result.release
                        dialogRelease = result.release
                        statusText = "发现新版本 ${result.release.tagName}，点击可再次检查"
                    }
                    is UpdateCheckResult.UpToDate -> {
                        availableRelease = null
                        statusText = "已是最新版本（v${BuildConfig.VERSION_NAME}）"
                        onMessage("当前已是最新版本（v${BuildConfig.VERSION_NAME}）")
                    }
                    UpdateCheckResult.NoReleaseYet -> {
                        availableRelease = null
                        statusText = "GitHub 仓库暂未发布正式版本"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                statusError = true
                val message = AppUpdateChecker.friendlyErrorMessage(error)
                statusText = message
                onMessage(message)
            } finally {
                checking = false
            }
        }
    }

    HindsightCard {
        SettingRow(
            title = "关于与当前版本",
            subtitle = "用时记 v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
            onClick = { showAboutDialog = true }
        )
        HindsightDivider()
        SettingRow(
            title = "检查更新",
            subtitle = statusText,
            enabled = !checking,
            onClick = ::triggerCheck,
            trailing = if (checking) {
                { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent) }
            } else {
                null
            }
        )
        if (statusError) {
            Text(
                "仅在手动检查时请求 GitHub；失败可稍后重试或直接打开 Releases 页面。",
                style = MaterialTheme.typography.labelSmall,
                color = c.danger
            )
            Spacer(Modifier.height(4.dp))
        }
        availableRelease?.let { release ->
            TextButton(
                onClick = { dialogRelease = release },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("查看并下载新版本 ${release.tagName}", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        }
        HindsightDivider()
        SettingRow(
            title = "GitHub Releases",
            subtitle = "${AppUpdateChecker.GITHUB_REPO_OWNER}/${AppUpdateChecker.GITHUB_REPO_NAME} · 查看项目与历史版本",
            onClick = { openExternalUrl(context, AppUpdateChecker.RELEASES_WEB_URL, onMessage) }
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "主要使用与屏幕可见是两个独立指标。其他应用的焦点及真实窗口遮挡无法由公开 UsageEvents 完全确认；单 resumed 仅作推断，多 resumed 无焦点证据时主要归属未知。桌面与系统界面排除。",
            style = MaterialTheme.typography.bodySmall,
            color = c.textMuted
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "记录默认保存在本机；只有启用 WebDAV 同步或手动点击检查更新时才会联网。卸载会删除本地数据，请先导出完整备份。",
            style = MaterialTheme.typography.bodySmall,
            color = c.textMuted
        )
        if (onEnterDemo != null) {
            TextButton(onClick = onEnterDemo, contentPadding = PaddingValues(0.dp)) {
                Text("体验示例数据", color = c.accent)
            }
        }
    }

    if (showAboutDialog) {
        AboutDetailsDialog(
            checking = checking,
            statusText = statusText,
            onDismiss = { showAboutDialog = false },
            onCheckUpdate = {
                showAboutDialog = false
                triggerCheck()
            },
            onOpenRepo = {
                openExternalUrl(context, AppUpdateChecker.REPO_WEB_URL, onMessage)
            }
        )
    }

    dialogRelease?.let { release ->
        UpdateAvailableDialog(
            release = release,
            onDismiss = { dialogRelease = null },
            onOpenUrl = { url -> openExternalUrl(context, url, onMessage) }
        )
    }
}

@Composable
private fun AboutDetailsDialog(
    checking: Boolean,
    statusText: String,
    onDismiss: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenRepo: () -> Unit
) {
    val c = hindsightColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandMark(30.dp)
                Text("关于用时记")
            }
        },
        text = {
            Column(
                Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "当前版本：v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textStrong,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "更新状态：$statusText",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted
                )
                Text(
                    "GitHub 仓库：${AppUpdateChecker.REPO_WEB_URL}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted
                )
                Text(
                    "开源协议：GNU GPL v3.0-only",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted
                )
                Text(
                    "隐私说明：无需注册账号，使用记录默认仅存本机；除主动点击检查更新或自愿配置 WebDAV 同步外，不主动联网。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !checking, onClick = onCheckUpdate) {
                Text(if (checking) "检查中…" else "检查更新")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onOpenRepo) { Text("GitHub 主页") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun UpdateAvailableDialog(
    release: AppUpdateInfo,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val c = hindsightColors()
    val sizeText = AppUpdateChecker.formatApkSize(release.apkSizeBytes)
    val publishDate = release.publishedAt?.take(10)?.takeIf { it.length == 10 }
    val primaryUrl = release.apkUrl ?: release.releaseUrl
    val primaryLabel = if (release.apkUrl != null) "浏览器下载安装包" else "前往 Release 下载"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 v${release.versionName}") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "当前版本 v${BuildConfig.VERSION_NAME} → 最新版本 ${release.tagName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textStrong,
                    fontWeight = FontWeight.SemiBold
                )
                release.apkName?.let { name ->
                    val detail = if (sizeText != null) "$name（$sizeText）" else name
                    Text("安装包：$detail", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
                publishDate?.let { date ->
                    Text("发布日期：$date", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
                Text(
                    text = release.notes.ifBlank { "请前往 GitHub Releases 页面查看本次更新说明。" },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textRegular,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onOpenUrl(primaryUrl)
                }
            ) {
                Text(primaryLabel)
            }
        },
        dismissButton = {
            Row {
                if (release.apkUrl != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenUrl(release.releaseUrl)
                        }
                    ) {
                        Text("查看 Release")
                    }
                }
                TextButton(onClick = onDismiss) { Text("暂不更新") }
            }
        }
    )
}
