package dev.usagetime.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.core.net.toUri
import dev.usagetime.app.sync.ApkDownloader
import dev.usagetime.app.sync.AppUpdateChecker
import dev.usagetime.app.sync.AppUpdateInfo
import dev.usagetime.app.sync.DownloadProgress
import dev.usagetime.app.sync.InstallResult
import dev.usagetime.app.sync.UpdateCheckResult
import dev.usagetime.app.sync.UpdateDownloadException
import dev.usagetime.app.sync.UpdateDownloadState
import dev.usagetime.app.sync.UpdateFormatting
import dev.usagetime.app.sync.UpdateInstaller
import dev.usagetime.app.sync.UpdateMirrors
import dev.usagetime.app.sync.UpdatePreferences
import dev.usagetime.app.sync.UpdateService
import dev.usagetime.app.sync.UpdateSourceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    var statusText by remember { mutableStateOf("手动点击时检查更新，下载支持国内镜像") }
    var statusError by remember { mutableStateOf(false) }
    var availableRelease by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var dialogRelease by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var sourceMode by remember { mutableStateOf(UpdatePreferences.sourceMode(context)) }
    var downloadState by remember { mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    fun changeSourceMode(mode: UpdateSourceMode) {
        sourceMode = mode
        UpdatePreferences.setSourceMode(context, mode)
    }

    fun clearDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadState = UpdateDownloadState.Idle
    }

    fun triggerCheck() {
        if (checking) return
        clearDownload()
        availableRelease = null
        scope.launch {
            checking = true
            statusError = false
            statusText = "正在检查更新…"
            try {
                when (val result = UpdateService.checkLatest(BuildConfig.VERSION_NAME, sourceMode)) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        availableRelease = result.release
                        dialogRelease = result.release
                        statusText = "发现新版本 ${result.release.tagName}，点击可再次检查"
                    }
                    is UpdateCheckResult.UpToDate -> {
                        statusText = "已是最新版本（v${BuildConfig.VERSION_NAME}）"
                        onMessage("当前已是最新版本（v${BuildConfig.VERSION_NAME}）")
                    }
                    UpdateCheckResult.NoReleaseYet -> {
                        statusText = "仓库暂未发布正式版本"
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

    fun startDownload(release: AppUpdateInfo) {
        if (downloadJob?.isActive == true) return
        if (release.apkUrl == null) {
            onMessage("该版本没有可下载的 APK，请前往 Releases 页面查看")
            return
        }
        downloadState = UpdateDownloadState.Running(
            progress = DownloadProgress(0L, release.apkSizeBytes),
            sourceLabel = "准备中",
            attempt = 1,
            attemptCount = 1
        )
        downloadJob = scope.launch {
            try {
                ApkDownloader.download(context.applicationContext, release, sourceMode) { state ->
                    downloadState = state
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (downloadState !is UpdateDownloadState.Failed) {
                    val message = (error as? UpdateDownloadException)?.message
                        ?: ApkDownloader.friendlyError(error)
                    downloadState = UpdateDownloadState.Failed(message)
                }
                val failed = downloadState as? UpdateDownloadState.Failed
                onMessage(failed?.message ?: "下载失败，请稍后重试")
            }
        }
    }

    fun installDownloaded() {
        val ready = downloadState as? UpdateDownloadState.Ready ?: return
        when (val result = UpdateInstaller.install(context, ready.file)) {
            InstallResult.NeedsPermission -> {
                onMessage("请允许用时记安装应用，然后返回重试")
                try {
                    context.startActivity(UpdateInstaller.unknownSourcesSettingsIntent(context))
                } catch (_: Exception) {
                    onMessage("无法打开安装权限设置，请在系统设置中允许用时记安装应用")
                }
            }
            InstallResult.Started -> Unit
            is InstallResult.Rejected -> {
                onMessage(result.message)
                ready.file.delete()
                downloadState = UpdateDownloadState.Idle
            }
            is InstallResult.Failed -> onMessage(result.message)
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
                "仅在手动检查或下载更新时联网；检查或下载失败可重试，也可直接打开 Releases 页面。",
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
        Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("更新下载源", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
            UpdateSourceChips(mode = sourceMode, onSelect = ::changeSourceMode)
            Text(
                UpdateMirrors.describeCandidates(sourceMode),
                style = MaterialTheme.typography.labelSmall,
                color = c.textMuted
            )
        }
        if (dialogRelease == null && downloadState != UpdateDownloadState.Idle) {
            HindsightDivider()
            UpdateDownloadInline(
                state = downloadState,
                release = availableRelease,
                onOpen = { dialogRelease = availableRelease },
                onInstall = ::installDownloaded,
                onRetry = { availableRelease?.let(::startDownload) },
                onCancel = ::clearDownload
            )
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
            "记录默认保存在本机；只有启用 WebDAV 同步、手动检查更新或下载更新时才会联网。卸载会删除本地数据，请先导出完整备份。",
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
            downloadState = downloadState,
            sourceMode = sourceMode,
            onSelectSource = ::changeSourceMode,
            onStartDownload = { startDownload(release) },
            onCancelDownload = ::clearDownload,
            onInstall = ::installDownloaded,
            onOpenUrl = { url -> openExternalUrl(context, url, onMessage) },
            onDismiss = { dialogRelease = null }
        )
    }
}

@Composable
private fun UpdateSourceChips(
    mode: UpdateSourceMode,
    onSelect: (UpdateSourceMode) -> Unit
) {
    val c = hindsightColors()
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        UpdateSourceMode.entries.forEach { option ->
            val selected = option == mode
            TextButton(
                onClick = { onSelect(option) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    option.shortLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) c.accent else c.textMuted,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun UpdateDownloadInline(
    state: UpdateDownloadState,
    release: AppUpdateInfo?,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val c = hindsightColors()
    when (state) {
        UpdateDownloadState.Idle -> Unit
        is UpdateDownloadState.Running, UpdateDownloadState.Verifying -> {
            SettingRow(
                title = "正在下载新版本",
                subtitle = downloadStateText(state),
                onClick = onOpen,
                trailing = {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = c.accent
                    )
                }
            )
            TextButton(onClick = onCancel, contentPadding = PaddingValues(0.dp)) {
                Text("取消下载", color = c.textMuted)
            }
        }
        is UpdateDownloadState.Ready -> {
            SettingRow(
                title = "更新已下载",
                subtitle = "点击安装 v${release?.versionName ?: ""}",
                onClick = onInstall
            )
        }
        is UpdateDownloadState.Failed -> {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = c.danger)
            Row {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("重试", color = c.accent, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("查看详情", color = c.textMuted)
                }
            }
        }
    }
}

private fun downloadStateText(state: UpdateDownloadState): String = when (state) {
    UpdateDownloadState.Idle -> ""
    is UpdateDownloadState.Running -> buildString {
        append("已下载 ")
        append(UpdateFormatting.bytes(state.progress.bytesRead))
        state.progress.totalBytes?.let { total ->
            append(" / ")
            append(UpdateFormatting.bytes(total))
        }
        state.progress.percent?.let { percent ->
            append(" · ")
            append(percent)
            append("%")
        }
        UpdateFormatting.speed(state.bytesPerSecond)?.let { speed ->
            append(" · ")
            append(speed)
        }
    }
    UpdateDownloadState.Verifying -> "正在校验安装包…"
    is UpdateDownloadState.Ready -> "已下载完成（${UpdateFormatting.bytes(state.sizeBytes)}）"
    is UpdateDownloadState.Failed -> state.message
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
                    "隐私说明：无需注册账号，使用记录默认仅存本机；除主动检查/下载更新或自愿配置 WebDAV 同步外，不主动联网。",
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
    downloadState: UpdateDownloadState,
    sourceMode: UpdateSourceMode,
    onSelectSource: (UpdateSourceMode) -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val c = hindsightColors()
    val sizeText = AppUpdateChecker.formatApkSize(release.apkSizeBytes)
    val publishDate = release.publishedAt?.take(10)?.takeIf { it.length == 10 }
    val hasApk = release.apkUrl != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 v${release.versionName}") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
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
                if (hasApk) {
                    Text("下载源", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                    UpdateSourceChips(mode = sourceMode, onSelect = onSelectSource)
                }
                UpdateDownloadBlock(
                    state = downloadState,
                    hasApk = hasApk,
                    onCancelDownload = onCancelDownload,
                    onInstall = onInstall,
                    onRetry = onStartDownload
                )
                Text(
                    text = release.notes.ifBlank { "请前往 GitHub Releases 页面查看本次更新说明。" },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textRegular,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            when (downloadState) {
                UpdateDownloadState.Idle -> if (hasApk) {
                    TextButton(onClick = onStartDownload) { Text("应用内下载并安装") }
                } else {
                    TextButton(onClick = { onOpenUrl(release.releaseUrl) }) { Text("前往 Release 下载") }
                }
                is UpdateDownloadState.Running, UpdateDownloadState.Verifying -> TextButton(onClick = onCancelDownload) {
                    Text("取消下载")
                }
                is UpdateDownloadState.Ready -> TextButton(onClick = onInstall) {
                    Text("立即安装")
                }
                is UpdateDownloadState.Failed -> TextButton(onClick = onStartDownload) {
                    Text("重试")
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        onOpenUrl(release.apkUrl ?: release.releaseUrl)
                    }
                ) {
                    Text(if (hasApk) "浏览器下载" else "查看 Release")
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun UpdateDownloadBlock(
    state: UpdateDownloadState,
    hasApk: Boolean,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit
) {
    val c = hindsightColors()
    when (state) {
        UpdateDownloadState.Idle -> {
            if (hasApk) {
                Text(
                    "应用内下载完成后会调起系统安装器；失败会自动切换下载源，也可改用浏览器下载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted
                )
            }
        }
        is UpdateDownloadState.Running -> {
            val fraction = state.progress.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                "正在从 ${state.sourceLabel} 下载（第 ${state.attempt}/${state.attemptCount} 个来源）",
                style = MaterialTheme.typography.bodySmall,
                color = c.textRegular
            )
            Text(
                downloadStateText(state),
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted
            )
        }
        UpdateDownloadState.Verifying -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("正在校验安装包…", style = MaterialTheme.typography.bodySmall, color = c.textRegular)
        }
        is UpdateDownloadState.Ready -> {
            Text(
                "已下载完成（${UpdateFormatting.bytes(state.sizeBytes)}），点击“立即安装”交给系统安装器。",
                style = MaterialTheme.typography.bodySmall,
                color = c.textRegular
            )
        }
        is UpdateDownloadState.Failed -> {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = c.danger)
            TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) {
                Text("重试下载", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}