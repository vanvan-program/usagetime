package dev.usagetime.app.sync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.usagetime.app.BuildConfig
import java.io.File

internal sealed interface InstallResult {
    data object Started : InstallResult
    data object NeedsPermission : InstallResult
    data class Rejected(val message: String) : InstallResult
    data class Failed(val message: String) : InstallResult
}

/** Hands a downloaded APK to the system package installer. */
internal object UpdateInstaller {
    internal const val MIME_APK = "application/vnd.android.package-archive"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    fun install(context: Context, file: File): InstallResult {
        if (!file.isFile || file.length() <= 0L) return InstallResult.Rejected("安装包文件不存在，请重新下载")
        if (!canInstallPackages(context)) return InstallResult.NeedsPermission

        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: return InstallResult.Rejected("安装包格式无效，请重新下载")
        if (archive.packageName != context.packageName) {
            return InstallResult.Rejected("安装包不是用时记，已拒绝安装")
        }
        if (archive.longVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
            return InstallResult.Rejected("安装包版本不高于当前版本，已拒绝安装")
        }

        return try {
            val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME_APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            InstallResult.Started
        } catch (_: ActivityNotFoundException) {
            InstallResult.Failed("未找到系统安装程序")
        } catch (error: Exception) {
            InstallResult.Failed(error.message?.takeIf { it.isNotBlank() } ?: "无法启动系统安装程序")
        }
    }
}
