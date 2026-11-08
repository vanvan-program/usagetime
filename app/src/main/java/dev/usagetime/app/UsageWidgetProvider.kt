package dev.usagetime.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.usagetime.app.data.CategoryPreferences
import dev.usagetime.core.Dates
import dev.usagetime.core.Grain
import dev.usagetime.core.UsageMetric
import dev.usagetime.core.Window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

enum class WidgetStyle(
    val label: String,
    val dimensions: String,
    val description: String,
    val provider: Class<out UsageWidgetProvider>,
    val layout: Int,
) {
    QUICK("用时速览", "2×1", "只看今日用时，适合紧凑桌面", QuickWidgetProvider::class.java, R.layout.usage_widget_quick),
    TODAY("今日概览", "3×3", "总用时、昨日同期、小时走势和前三应用", UsageWidgetProvider::class.java, R.layout.usage_widget),
    APPS("应用排行", "4×3", "前五应用的用时、占比和排行", AppsWidgetProvider::class.java, R.layout.usage_widget_apps),
    CATEGORIES("分类分布", "3×2", "分类占比环图与前三分类", CategoriesWidgetProvider::class.java, R.layout.usage_widget_categories),
    WEEK("七日趋势", "4×2", "近七天用时、每日数值和已记录日均", WeekWidgetProvider::class.java, R.layout.usage_widget_week),
}

open class UsageWidgetProvider : AppWidgetProvider() {
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, options: Bundle) {
        refreshAsync(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refreshAsync(context)
    }

    override fun onEnabled(context: Context) {
        refreshAsync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshAsync(context)
    }

    private fun refreshAsync(context: Context) {
        if (installed(context).isEmpty()) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetUpdater.refresh(appContext)
            } catch (_: CancellationException) {
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "dev.usagetime.app.WIDGET_REFRESH"
        const val EXTRA_PAGE = "widget_page"
        const val EXTRA_APP = "widget_app"
        const val EXTRA_WEEK = "widget_week"

        fun installed(context: Context): Map<WidgetStyle, IntArray> {
            val manager = AppWidgetManager.getInstance(context)
            return WidgetStyle.entries.associateWith {
                manager.getAppWidgetIds(ComponentName(context, it.provider))
            }.filterValues { it.isNotEmpty() }
        }

        fun requestUpdate(context: Context) {
            if (installed(context).isNotEmpty()) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "usage-widget", ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<UsageWidgetWorker>().build()
                )
            }
        }

        fun refreshIntent(context: Context, style: WidgetStyle, id: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, id, Intent(context, style.provider).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}

class QuickWidgetProvider : UsageWidgetProvider()
class AppsWidgetProvider : UsageWidgetProvider()
class CategoriesWidgetProvider : UsageWidgetProvider()
class WeekWidgetProvider : UsageWidgetProvider()

object WidgetUpdater {
    suspend fun refresh(context: Context) {
        val installed = UsageWidgetProvider.installed(context)
        if (installed.isEmpty() || !context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val manager = AppWidgetManager.getInstance(context)
        val preferences = context.getSharedPreferences("usage-settings", Context.MODE_PRIVATE)
        val renderer = WidgetRenderer(context)
        try {
            val store = (context as UsageTimeApplication).store
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val day = Dates.date(now, zone)
            val start = Dates.midnight(day, zone)
            val localTime = Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
            val yesterdayStart = Dates.midnight(day.minusDays(1), zone)
            val yesterdayEnd = day.minusDays(1).atTime(localTime).atZone(zone).toInstant().toEpochMilli()
            val metric = UsageMetric.entries.firstOrNull { it.name == preferences.getString("widget-metric", "") }
                ?: UsageMetric.PRIMARY
            val legacy = preferences.getBoolean("widget-legacy", false)
            val confirmed = preferences.getBoolean("show-confirmed", false)
            suspend fun report(window: Window, grain: Grain) =
                if (legacy) store.aggregateLegacy(window, zone, grain, null)
                else store.aggregate(window, zone, grain, metric, null, confirmed)
            val snapshot = WidgetSnapshot(
                now = now, zone = zone,
                today = report(Window(start, now), Grain.HOUR),
                yesterday = report(Window(yesterdayStart, yesterdayEnd), Grain.HOUR),
                week = if (WidgetStyle.WEEK in installed) report(Window(Dates.midnight(day.minusDays(6), zone), now), Grain.DAY) else null,
                assignments = CategoryPreferences(context).read(),
                metricLabel = if (legacy) "旧版用时" else "${metric.label} · ${if (confirmed) "确认＋估计" else "估计累计"}",
                lastCollected = store.last(), permitted = store.permitted()
            )
            installed.forEach { (style, ids) ->
                ids.forEach { id ->
                    manager.updateAppWidget(id, renderer.render(style, id, manager.getAppWidgetOptions(id), snapshot, store::cachedLabel))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            installed.forEach { (style, ids) ->
                ids.forEach { id -> manager.updateAppWidget(id, renderer.error(style, id)) }
            }
            throw error
        }
    }
}

class UsageWidgetWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (UsageWidgetProvider.installed(context).isEmpty()) return Result.success()
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return Result.retry()
        return try {
            WidgetUpdater.refresh(context)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
