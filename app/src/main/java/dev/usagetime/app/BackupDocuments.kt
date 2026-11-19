package dev.usagetime.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.usagetime.core.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BackupDocuments(
    val export: () -> Unit,
    val compressedExport: () -> Unit,
    val importBackup: () -> Unit,
    val csv: () -> Unit
)

@Composable
internal fun rememberBackupDocuments(model: UsageModel): BackupDocuments {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()

    fun exportBackup(uri: Uri?, compressed: Boolean) {
        if (uri == null) return
        scope.launch {
            BackupDocumentCoordinator.exportBackup(
                write = {
                    withContext(Dispatchers.IO) {
                        val output = resolver.openOutputStream(uri) ?: error("无法写入文件")
                        output.use {
                            if (compressed) model.store.writeCompressedBackup(it) else model.store.writeBackup(it)
                        }
                    }
                },
                markExported = model::fullBackupExported,
                message = { model.message = it },
                success = if (compressed) "压缩完整备份已导出" else "备份已导出",
                failurePrefix = if (compressed) "导出未完成，请保留此前副本：" else "导出失败："
            )
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        exportBackup(it, compressed = false)
    }
    val compressedExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) {
        exportBackup(it, compressed = true)
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            try {
                val snapshot = model.exportReport()
                withContext(Dispatchers.IO) {
                    val body = CsvReportFormat.build(snapshot, model::label)
                    resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                        ?: error("无法写入文件")
                }
                model.message = "报表已导出"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                model.message = "导出失败：${error.message}"
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            BackupDocumentCoordinator.importBackup(
                read = {
                    withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)?.use { model.store.importBackup(it) }
                            ?: error("无法读取文件")
                    }
                },
                loadNames = { model.store.loadNames() },
                updateSafety = model::updateSafetyInfo,
                useDemo = model::useDemo,
                reload = model::reload,
                message = { model.message = it }
            )
        }
    }

    fun date() = Dates.date(model.now, model.zone)
    return BackupDocuments(
        export = { export.launch("用时记备份_${date()}.json") },
        compressedExport = { compressedExport.launch("用时记备份_${date()}.json.gz") },
        importBackup = {
            import.launch(arrayOf("application/json", "text/plain", "application/gzip", "application/x-gzip", "application/octet-stream"))
        },
        csv = { csv.launch("用时记报表_${date()}.csv") }
    )
}
