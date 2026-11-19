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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
import dev.usagetime.app.sync.UpdatePreferences
import dev.usagetime.app.sync.UpdateService
import dev.usagetime.app.sync.UpdateSourceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showVersionNotes by rememberSaveable { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val checkPrompt = "手动查询最新正式版本"
    var statusText by remember { mutableStateOf(checkPrompt) }
    var availableRelease by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var dialogRelease by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var sourceMode by remember { mutableStateOf(UpdatePreferences.sourceMode(context)) }
    var downloadState by remember { mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val downloading = downloadState is UpdateDownloadState.Running || downloadState == UpdateDownloadState.Verifying

    fun changeSourceMode(mode: UpdateSourceMode) {
        if (mode != sourceMode) {
            sourceMode = mode
            UpdatePreferences.setSourceMode(context, mode)
            statusText = checkPrompt
        }
        showSourceDialog = false
    }

    fun clearDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadState = UpdateDownloadState.Idle
    }

    fun triggerCheck() {
        if (checking || downloading) return
        clearDownload()
        availableRelease = null
        checking = true
        val mode = sourceMode
        scope.launch {
            var sourceLabel = mode.label
            try {
                val result = UpdateService.checkLatest(BuildConfig.VERSION_NAME, mode, onSource = { source ->
                    sourceLabel = source.label
                    statusText = "正在通过 ${source.label} 检查…"
                })
                val summary = when (result) {
                    is UpdateCheckResult.UpdateAvailable -> {
                        availableRelease = result.release
                        dialogRelease = result.release
                        "发现新版本 ${result.release.tagName}"
                    }
                    is UpdateCheckResult.UpToDate -> {
                        if (AppUpdateChecker.isNewerVersion(BuildConfig.VERSION_NAME, result.latestVersion)) {
                            "最新发布 v${result.latestVersion}，低于当前版本"
                        } else {
                            "已是最新版本 v${result.latestVersion}"
                        }
                    }
                    UpdateCheckResult.NoReleaseYet -> "仓库暂未发布正式版本"
                }
                statusText = "$summary · $sourceLabel"
                if (result !is UpdateCheckResult.UpdateAvailable) onMessage(summary)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = "$sourceLabel · ${AppUpdateChecker.friendlyErrorMessage(error)}"
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
                    if (isActive) downloadState = state
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
            title = "版本更新介绍",
            subtitle = "查看 v${BuildConfig.VERSION_NAME} 的更新内容",
            onClick = { showVersionNotes = true }
        )
        HindsightDivider()
        SettingRow(
            title = "更新源",
            subtitle = sourceMode.label,
            enabled = !checking && !downloading,
            onClick = { showSourceDialog = true }
        )
        HindsightDivider()
        SettingRow(
            title = "检查更新",
            subtitle = statusText,
            enabled = !checking && !downloading,
            onClick = ::triggerCheck,
            trailing = if (checking) {
                { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent) }
            } else {
                null
            }
        )

        availableRelease?.let { release ->
            TextButton(
                onClick = { dialogRelease = release },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("查看并下载新版本 ${release.tagName}", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
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

    if (showVersionNotes) {
        VersionNotesDialog(onDismiss = { showVersionNotes = false })
    }

    if (showSourceDialog) {
        UpdateSourceDialog(
            mode = sourceMode,
            onSelect = ::changeSourceMode,
            onDismiss = { showSourceDialog = false }
        )
    }

    dialogRelease?.takeUnless { showSourceDialog }?.let { release ->
        UpdateAvailableDialog(
            release = release,
            downloadState = downloadState,
            sourceMode = sourceMode,
            onSelectSource = { showSourceDialog = true },
            onStartDownload = { startDownload(release) },
            onCancelDownload = ::clearDownload,
            onInstall = ::installDownloaded,
            onOpenUrl = { url -> openExternalUrl(context, url, onMessage) },
            onDismiss = { dialogRelease = null }
        )
    }
}

@Composable
private fun UpdateSourceDialog(
    mode: UpdateSourceMode,
    onSelect: (UpdateSourceMode) -> Unit,
    onDismiss: () -> Unit
) {
    val c = hindsightColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("更新源") },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                UpdateSourceMode.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = option == mode, role = Role.RadioButton, onClick = { onSelect(option) })
                            .heightIn(min = 48.dp)
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == mode, onClick = null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium, color = c.textStrong)
                            Text(option.description, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun VersionNotesDialog(onDismiss: () -> Unit) {
    val c = hindsightColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("版本更新介绍") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "用时记 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textStrong
                )
                val notes = BuildConfig.VERSION_UPDATE_NOTES
                if (notes.isBlank()) {
                    Text("此版本暂无内置更新介绍。", color = c.textMuted)
                } else {
                    notes.lineSequence().forEach { note ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", color = c.textMuted)
                            Text(note, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.textRegular)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
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
    onSelectSource: () -> Unit,
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
                if (hasApk && downloadState !is UpdateDownloadState.Running &&
                    downloadState != UpdateDownloadState.Verifying
                ) {
                    TextButton(onClick = onSelectSource, contentPadding = PaddingValues(0.dp)) {
                        Text("更新源 · ${sourceMode.label}", color = c.accent)
                    }
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
                    "下载完成后，点击「立即安装」打开系统安装器。",
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