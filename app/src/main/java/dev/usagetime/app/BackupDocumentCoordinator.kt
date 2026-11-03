package dev.usagetime.app

/** Runs in the caller's existing UI scope; owns no jobs, model state or streams. */
internal object BackupDocumentCoordinator {
    suspend fun exportBackup(
        write: suspend () -> Unit,
        markExported: () -> Unit,
        message: (String) -> Unit,
        success: String,
        failurePrefix: String
    ) {
        try {
            write()
            markExported()
            message(success)
        } catch (e: Exception) {
            message("$failurePrefix${e.message}")
        }
    }

    suspend fun importBackup(
        read: suspend () -> Long,
        loadNames: suspend () -> Unit,
        updateSafety: () -> Unit,
        useDemo: (Boolean) -> Unit,
        reload: () -> Unit,
        message: (String) -> Unit
    ) {
        try {
            val count = read()
            loadNames()
            updateSafety()
            useDemo(false)
            reload()
            message("已合并 $count 条记录；旧记录保留，导入证据不升级为确定")
        } catch (e: Exception) {
            message("导入失败：${e.message ?: "备份格式不正确"}")
        }
    }
}
