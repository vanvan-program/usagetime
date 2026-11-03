package dev.usagetime.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupDocumentCoordinatorTest {
    private class Fixture {
        val steps = mutableListOf<String>()
        val messages = mutableListOf<String>()
        var failureAt = ""
        var error: Exception = IllegalStateException("fixture")
        fun step(name: String) { steps += name; if (failureAt == name) throw error }
        suspend fun export(compressed: Boolean = false) = BackupDocumentCoordinator.exportBackup(
            write = { step("write"); step("close") },
            markExported = { step("mark") },
            message = { messages += it },
            success = if (compressed) "压缩完整备份已导出" else "备份已导出",
            failurePrefix = if (compressed) "导出未完成，请保留此前副本：" else "导出失败："
        )
        suspend fun runImport() = BackupDocumentCoordinator.importBackup(
            read = { step("read"); step("close"); 42 },
            loadNames = { step("names") }, updateSafety = { step("safety") },
            useDemo = { assertFalse(it); step("demo") },
            reload = { step("reload") }, message = { messages += it }
        )
    }
    @Test fun plainAndCompressedExportMarkOnlyAfterWriteAndClose() = runBlocking {
        for (compressed in listOf(false, true)) {
            val f = Fixture(); f.export(compressed)
            assertEquals(listOf("write", "close", "mark"), f.steps)
            assertEquals(listOf(if (compressed) "压缩完整备份已导出" else "备份已导出"), f.messages)
        }
    }
    @Test fun exportWriteOrCloseFailureDoesNotMarkSuccessfulBackup() = runBlocking {
        for (at in listOf("write", "close")) for (compressed in listOf(false, true)) {
            val f = Fixture(); f.failureAt = at; f.export(compressed)
            assertFalse(f.steps.contains("mark"))
            assertEquals(listOf(if (compressed) "导出未完成，请保留此前副本：fixture" else "导出失败：fixture"), f.messages)
        }
    }
    @Test fun exportMetadataFailureKeepsOldFailureMessage() = runBlocking {
        val f = Fixture(); f.failureAt = "mark"; f.export()
        assertEquals(listOf("导出失败：fixture"), f.messages)
    }
    @Test fun successfulImportKeepsEveryModelCallbackInOrder() = runBlocking {
        val f = Fixture(); f.runImport()
        assertEquals(listOf("read", "close", "names", "safety", "demo", "reload"), f.steps)
        assertEquals(listOf("已合并 42 条记录；旧记录保留，导入证据不升级为确定"), f.messages)
    }
    @Test fun eachImportFailureStopsSubsequentCallbacks() = runBlocking {
        val order = listOf("read", "close", "names", "safety", "demo", "reload")
        for (at in order) {
            val f = Fixture(); f.failureAt = at; f.runImport()
            assertEquals(order.take(order.indexOf(at) + 1), f.steps)
            assertEquals(listOf("导入失败：fixture"), f.messages)
        }
    }
    @Test fun missingExceptionMessagesKeepOriginalFallbacks() = runBlocking {
        val f = Fixture(); f.error = Exception(); f.failureAt = "read"; f.runImport()
        assertEquals(listOf("导入失败：备份格式不正确"), f.messages)
        val e = Fixture(); e.error = Exception(); e.failureAt = "write"; e.export()
        assertEquals(listOf("导出失败：null"), e.messages)
    }
    @Test fun cancellationRetainsExistingDocumentCallbackFailureFeedback() = runBlocking {
        for (at in listOf("read", "close", "names", "safety", "demo", "reload")) {
            val f = Fixture(); f.failureAt = at; f.error = CancellationException("cancel"); f.runImport()
            assertEquals(listOf("导入失败：cancel"), f.messages)
        }
        val f = Fixture(); f.failureAt = "write"; f.error = CancellationException("cancel"); f.export()
        assertEquals(listOf("导出失败：cancel"), f.messages)
    }
    @Test fun independentOperationsDoNotShareProgressOrFailureState() = runBlocking {
        val f = Fixture(); f.failureAt = "read"; f.runImport()
        f.failureAt = ""; f.steps.clear(); f.export()
        assertEquals(listOf("write", "close", "mark"), f.steps)
        assertEquals(listOf("导入失败：fixture", "备份已导出"), f.messages)
    }
}
