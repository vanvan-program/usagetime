package dev.usagetime.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.UserManager
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.core.graphics.toColorInt
import dev.usagetime.core.*
import dev.usagetime.app.data.CategoryPreferences
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class UsageWidgetProvider : AppWidgetProvider() {
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, options: android.os.Bundle) {
        requestUpdate(context)
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        requestUpdate(context)
    }

    override fun onEnabled(context: Context) {
        requestUpdate(context)
    }

    companion object {
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "usage-widget", ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<UsageWidgetWorker>().build()
                )
            }
        }
    }
}

class UsageWidgetWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java))
        if (ids.isEmpty()) return Result.success()
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return Result.retry()
        val preferences = context.getSharedPreferences("usage-settings", Context.MODE_PRIVATE)
        val theme = preferences.getString("theme", "system")
        val dark = theme == "dark" || (theme == "system" &&
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        val views = RemoteViews(context.packageName, R.layout.usage_widget)
        views.setInt(R.id.widget_root, "setBackgroundResource", if (dark) R.drawable.widget_background_dark else R.drawable.widget_background)
        val strong = (if (dark) "#F2EFF9" else "#242133").toColorInt()
        val muted = (if (dark) "#C5BED4" else "#625B72").toColorInt()
        views.setTextColor(R.id.widget_title, muted)
        views.setTextColor(R.id.widget_total, strong)
        views.setTextColor(R.id.widget_categories, muted)
        views.setTextColor(R.id.widget_updated, muted)
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ))
        try {
            val store = (context as UsageTimeApplication).store
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val window = Window(Dates.midnight(Dates.date(now, zone), zone), now)
            val metric = UsageMetric.entries.firstOrNull { it.name == preferences.getString("widget-metric", "") } ?: UsageMetric.PRIMARY
            val legacy = preferences.getBoolean("widget-legacy", false)
            val report = if (legacy) store.aggregateLegacy(window, zone, Grain.HOUR, null)
                else store.aggregate(window, zone, Grain.HOUR, metric, null, preferences.getBoolean("show-confirmed", false))
            views.setTextViewText(R.id.widget_title, "今日${if (legacy) "旧版用时" else metric.label}")
            views.setTextViewText(R.id.widget_total,
                if (report.buckets.any { it.available }) durationText(report.total) else "未记录")
            val categories = UsageInsights.categories(report.apps, CategoryPreferences(context).read())
            views.setTextViewText(R.id.widget_categories,
                categories.take(3).joinToString("\n") { "${it.category.label}  ${durationText(it.millis)}" }.ifEmpty {
                    if (store.permitted()) "打开应用补记后更新" else "打开应用开启使用情况访问"
                })
            val at = store.last()?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("M/d HH:mm")) }
            views.setTextViewText(R.id.widget_updated,
                "${if (report.fullyCovered) "" else "部分记录 · "}上次补记 ${at ?: "暂无"}")
            manager.updateAppWidget(ids, views)
            return Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            views.setTextViewText(R.id.widget_total, "暂时无法读取")
            views.setTextViewText(R.id.widget_categories, "点击打开用时记重试")
            views.setTextViewText(R.id.widget_updated, "原有记录仍保存在本机")
            manager.updateAppWidget(ids, views)
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
