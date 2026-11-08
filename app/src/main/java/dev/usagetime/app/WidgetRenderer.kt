package dev.usagetime.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.toColorInt
import dev.usagetime.core.Dates
import dev.usagetime.core.durationText
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlin.math.roundToInt

class WidgetRenderer(private val context: Context, private val darkOverride: Boolean? = null) {
    private val preferences = context.getSharedPreferences("usage-settings", Context.MODE_PRIVATE)
    private val dark = darkOverride ?: when (preferences.getString("theme", "system")) {
        "dark" -> true
        "light" -> false
        else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    private val strong = (if (dark) "#F2EFF9" else "#242133").toColorInt()
    private val muted = (if (dark) "#C5BED4" else "#625B72").toColorInt()
    private val accent = (if (dark) "#B7A7FF" else "#6650C8").toColorInt()
    private val track = (if (dark) "#4B435D" else "#DED7EE").toColorInt()
    private val warning = (if (dark) "#F6C66D" else "#805400").toColorInt()
    private val categoryColors = if (dark) intArrayOf(
        "#B7A7FF".toColorInt(), "#91BFFF".toColorInt(), "#FFABD1".toColorInt(),
        "#F1C478".toColorInt(), "#8CD4B3".toColorInt(), "#B5AEC1".toColorInt()
    ) else intArrayOf(
        "#6650C8".toColorInt(), "#356BB0".toColorInt(), "#AF4D7B".toColorInt(),
        "#926315".toColorInt(), "#287553".toColorInt(), "#766D87".toColorInt()
    )

    fun render(
        requested: WidgetStyle, id: Int, options: Bundle, snapshot: WidgetSnapshot,
        label: (String) -> String?,
    ): RemoteViews {
        val scale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val (rawWidth, rawHeight) = hostSize(options, requested)
        val height = rawHeight / scale
        val width = rawWidth / scale
        val style = if (height < 100 || width < 140 ||
            (requested in listOf(WidgetStyle.TODAY, WidgetStyle.APPS) && height < 150) ||
            (requested == WidgetStyle.CATEGORIES && height < 130)) WidgetStyle.QUICK else requested
        val views = base(style, requested, id)
        val date = Dates.date(snapshot.now, snapshot.zone)
        val dateLabel = date.format(DateTimeFormatter.ofPattern("M/d"))
        views.setTextViewText(R.id.widget_title, if (style == WidgetStyle.QUICK) "今日用时" else "${style.label} · $dateLabel")
        views.setTextViewText(R.id.widget_total, snapshot.totalText)
        views.setContentDescription(R.id.widget_root, "${requested.label}，${snapshot.metricLabel}，今日${snapshot.totalText}")
        views.setOnClickPendingIntent(R.id.widget_root, openIntent(requested))
        if (style == WidgetStyle.QUICK) {
            views.setTextViewText(R.id.widget_updated, footer(snapshot))
            views.setViewVisibility(R.id.widget_updated, if (height >= 100) View.VISIBLE else View.GONE)
            return views
        }
        views.setTextViewText(R.id.widget_updated, footer(snapshot))
        views.setTextViewText(R.id.widget_comparison, snapshot.comparison)
        views.setTextViewText(R.id.widget_metric, snapshot.metricLabel)
        if (height < 280) views.setViewVisibility(R.id.widget_metric, View.GONE)
        when (style) {
            WidgetStyle.TODAY -> {
                views.setImageViewBitmap(R.id.widget_chart, hourlyChart(snapshot))
                views.setContentDescription(R.id.widget_chart, hourlyDescription(snapshot))
                val rowCount = if (height >= 220) 3 else if (height >= 190) 2 else 1
                appRows(views, snapshot, rowCount, label, false)
            }
            WidgetStyle.APPS -> {
                val rowCount = if (height >= 260) 5 else if (height >= 230) 4 else if (height >= 200) 3 else 2
                appRows(views, snapshot, rowCount, label, true)
            }
            WidgetStyle.CATEGORIES -> {
                views.setImageViewBitmap(R.id.widget_chart, categoryChart(snapshot))
                views.setContentDescription(R.id.widget_chart,
                    snapshot.categories.joinToString("；") { "${it.category.label}，${durationText(it.millis)}" }.ifEmpty { "暂无分类用时" })
                views.removeAllViews(R.id.widget_rows)
                val rowCount = if (height >= 175) 3 else 2
                if (width < 200) views.setViewVisibility(R.id.widget_chart, View.GONE)
                snapshot.categories.take(rowCount).forEach { category ->
                    views.addView(R.id.widget_rows, row(
                        category.category.label, compactWidgetDuration(category.millis),
                        widgetShare(category.millis, snapshot.today.total), categoryColors[category.category.ordinal], null
                    ))
                }
                val remainder = snapshot.categories.drop(rowCount)
                views.setTextViewText(R.id.widget_comparison,
                    if (remainder.isNotEmpty()) "其余${remainder.size}类 ${compactWidgetDuration(remainder.sumOf { it.millis })}"
                    else if (snapshot.available && snapshot.today.total == 0L) "已记录零用时" else snapshot.comparison)
                views.setViewVisibility(R.id.widget_empty, if (snapshot.categories.isEmpty()) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.widget_rows, if (snapshot.categories.isEmpty()) View.GONE else View.VISIBLE)
                views.setTextViewText(R.id.widget_empty, if (snapshot.available) "暂无分类用时" else "尚无保存记录")
            }
            WidgetStyle.WEEK -> {
                val report = snapshot.week
                views.setTextViewText(R.id.widget_total,
                    if (snapshot.days.any { it.available }) compactWidgetDuration(report?.total ?: 0) else "未记录")
                views.setTextViewText(R.id.widget_comparison, snapshot.weekSummary)
                views.setTextViewText(R.id.widget_title, "七日趋势 · ${date.minusDays(6).format(DateTimeFormatter.ofPattern("M/d"))}—$dateLabel")
                views.setImageViewBitmap(R.id.widget_chart, weekChart(snapshot))
                views.setContentDescription(R.id.widget_chart, snapshot.days.joinToString("；") {
                    "${it.date}，${if (it.available) durationText(it.millis) else "未记录"}${if (it.available && !it.complete) "，部分记录" else ""}"
                })
                views.setContentDescription(R.id.widget_root, "七日趋势，${snapshot.metricLabel}，${snapshot.weekSummary}")
            }
            WidgetStyle.QUICK -> Unit
        }
        return views
    }

    fun error(style: WidgetStyle, id: Int): RemoteViews = base(WidgetStyle.QUICK, style, id).apply {
        setTextViewText(R.id.widget_title, style.label)
        setTextViewText(R.id.widget_total, "读取失败")
        setTextViewText(R.id.widget_updated, "点击打开重试")
        setOnClickPendingIntent(R.id.widget_root, openIntent(style))
    }

    private fun base(layout: WidgetStyle, provider: WidgetStyle, id: Int): RemoteViews =
        RemoteViews(context.packageName, layout.layout).apply {
            setInt(R.id.widget_root, "setBackgroundResource", if (dark) R.drawable.widget_background_dark else R.drawable.widget_background)
            setTextColor(R.id.widget_title, muted)
            setTextColor(R.id.widget_total, strong)
            setTextColor(R.id.widget_updated, muted)
            setInt(R.id.widget_refresh, "setColorFilter", accent)
            setOnClickPendingIntent(R.id.widget_refresh, UsageWidgetProvider.refreshIntent(context, provider, id))
            if (layout != WidgetStyle.QUICK) {
                setTextColor(R.id.widget_comparison, muted)
                setTextColor(R.id.widget_metric, muted)
                if (layout != WidgetStyle.WEEK) setTextColor(R.id.widget_empty, muted)
            }
        }

    private fun footer(snapshot: WidgetSnapshot): String {
        val at = snapshot.lastCollected?.let {
            Instant.ofEpochMilli(it).atZone(snapshot.zone).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
        }
        return "${if (snapshot.available && !snapshot.today.fullyCovered) "部分记录 · " else ""}补记 ${at ?: "暂无"}"
    }

    private fun appRows(views: RemoteViews, snapshot: WidgetSnapshot, count: Int, label: (String) -> String?, shares: Boolean) {
        views.removeAllViews(R.id.widget_rows)
        snapshot.today.apps.sortedByDescending { it.millis }.take(count).forEachIndexed { index, app ->
            val name = label(app.app) ?: app.app.substringAfterLast('.')
            val share = widgetShare(app.millis, snapshot.today.total)
            val value = compactWidgetDuration(app.millis) + if (shares) " · $share%" else ""
            views.addView(R.id.widget_rows, row("${index + 1}  $name", value, share, accent, app.app))
        }
        val empty = snapshot.today.apps.isEmpty()
        views.setViewVisibility(R.id.widget_comparison, if (empty) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_rows, if (empty) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_empty, if (empty) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_empty, if (snapshot.available) "已记录，暂无应用用时" else snapshot.comparison)
    }

    private fun row(name: String, value: String, share: Int, color: Int, app: String?): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_stat_row).apply {
            setTextViewText(R.id.widget_row_name, name)
            setTextViewText(R.id.widget_row_value, value)
            setTextColor(R.id.widget_row_name, strong)
            setTextColor(R.id.widget_row_value, muted)
            setImageViewBitmap(R.id.widget_row_bar, shareBar(share, color))
            setContentDescription(R.id.widget_row_root, "$name，$value，占比$share%")
            setOnClickPendingIntent(R.id.widget_row_root, openIntent(WidgetStyle.APPS, app))
        }

    private fun openIntent(style: WidgetStyle, app: String? = null): PendingIntent {
        val destination = "usagetime://widget/${style.name}/${Uri.encode(app.orEmpty())}"
        val intent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse(destination)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(UsageWidgetProvider.EXTRA_PAGE, if (style == WidgetStyle.APPS || style == WidgetStyle.CATEGORIES) 1 else 0)
            putExtra(UsageWidgetProvider.EXTRA_WEEK, style == WidgetStyle.WEEK)
            app?.let { putExtra(UsageWidgetProvider.EXTRA_APP, it) }
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun defaultHeight(style: WidgetStyle): Int = when (style) {
        WidgetStyle.QUICK -> 80
        WidgetStyle.TODAY -> 250
        WidgetStyle.APPS -> 290
        WidgetStyle.CATEGORIES -> 180
        WidgetStyle.WEEK -> 180
    }

    private fun hostSize(options: Bundle, requested: WidgetStyle): Pair<Int, Int> {
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val portrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            @Suppress("DEPRECATION")
            val sizes = options.getParcelableArray(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            val preferred = sizes
                ?.filterIsInstance<SizeF>()
                ?.filter { if (portrait) it.height >= it.width else it.width >= it.height }
                ?.maxByOrNull { if (portrait) it.height else it.width }
            if (preferred != null) {
                return preferred.width.roundToInt() to preferred.height.roundToInt()
            }
        }
        val width = minWidth.takeIf { it > 0 } ?: maxWidth.takeIf { it > 0 } ?: 240
        val height = maxHeight.takeIf { it > 0 } ?: minHeight.takeIf { it > 0 } ?: defaultHeight(requested)
        return width to height
    }

    private fun shareBar(share: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(400, 6, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = track
        canvas.drawRoundRect(0f, 0f, 400f, 6f, 3f, 3f, paint)
        if (share > 0) {
            paint.color = color
            canvas.drawRoundRect(0f, 0f, 4f * share, 6f, 3f, 3f, paint)
        }
        return bitmap
    }

    private fun hourlyDescription(snapshot: WidgetSnapshot): String =
        "今日小时走势。" + snapshot.today.buckets.joinToString("；") {
            val hour = Instant.ofEpochMilli(it.start).atZone(snapshot.zone).hour
            "${hour}时，${if (it.available) durationText(it.millis) else "未记录"}"
        }

    private fun hourlyChart(snapshot: WidgetSnapshot): Bitmap {
        val bitmap = Bitmap.createBitmap(720, 112, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val buckets = snapshot.today.buckets.groupBy { Instant.ofEpochMilli(it.start).atZone(snapshot.zone).hour }
        val totals = buckets.mapValues { (_, rows) -> rows.sumOf { it.millis } }
        val maximum = (totals.values.maxOrNull() ?: 0).coerceAtLeast(1)
        val currentHour = Instant.ofEpochMilli(snapshot.now).atZone(snapshot.zone).hour
        for (hour in 0..23) {
            val left = hour * 30f + 3f
            val rows = buckets[hour].orEmpty()
            val available = rows.any { it.available }
            val value = totals[hour] ?: 0
            paint.color = if (hour <= currentHour) track else android.graphics.Color.TRANSPARENT
            if (!available && hour <= currentHour) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                canvas.drawRoundRect(left, 61f, left + 20, 68f, 2f, 2f, paint)
                paint.style = Paint.Style.FILL
            } else if (available) {
                paint.color = if (value == 0L) track else accent
                val barHeight = if (value == 0L) 2f else (value.toDouble() / maximum * 62).toFloat()
                canvas.drawRoundRect(left, 68f - barHeight, left + 20, 68f, 3f, 3f, paint)
                if (rows.any { !it.complete }) {
                    paint.color = warning
                    canvas.drawCircle(left + 10, 77f, 2f, paint)
                }
            }
        }
        paint.color = muted
        paint.textSize = 22f
        for (hour in listOf(0, 6, 12, 18)) canvas.drawText("%02d".format(hour), hour * 30f, 108f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("24时", 720f, 108f, paint)
        return bitmap
    }

    private fun categoryChart(snapshot: WidgetSnapshot): Bitmap {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 24f; color = track }
        val bounds = RectF(18f, 18f, 182f, 182f)
        canvas.drawOval(bounds, paint)
        var angle = -90f
        if (snapshot.today.total > 0) snapshot.categories.forEach { category ->
            val sweep = category.millis.toDouble() / snapshot.today.total * 360
            paint.color = categoryColors[category.category.ordinal]
            canvas.drawArc(bounds, angle, sweep.toFloat(), false, paint)
            angle += sweep.toFloat()
        }
        paint.style = Paint.Style.FILL
        paint.color = strong
        paint.textSize = 32f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(if (snapshot.available) "${snapshot.categories.size}类" else "未记录", 100f, 110f, paint)
        return bitmap
    }

    private fun weekChart(snapshot: WidgetSnapshot): Bitmap {
        val bitmap = Bitmap.createBitmap(840, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val maximum = snapshot.days.filter { it.available }.maxOfOrNull { it.millis }?.coerceAtLeast(1) ?: 1
        snapshot.days.forEachIndexed { index, day ->
            val center = 60f + index * 120
            val height = if (day.available) (day.millis.toDouble() / maximum * 96).toFloat() else 0f
            paint.color = if (index == 6) accent else categoryColors[1]
            if (day.available && day.millis > 0) {
                canvas.drawRoundRect(center - 24, 142f - height, center + 24, 142f, 6f, 6f, paint)
            } else if (day.available) {
                paint.color = track
                canvas.drawRect(center - 24, 140f, center + 24, 142f, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = track
                canvas.drawRoundRect(center - 24, 127f, center + 24, 142f, 4f, 4f, paint)
                paint.style = Paint.Style.FILL
            }
            paint.textSize = 26f
            paint.color = muted
            paint.textSize = 32f
            val value = if (day.millis < 3600000) compactWidgetDuration(day.millis)
                else "%.1f时".format(java.util.Locale.CHINA, day.millis / 3600000.0)
            canvas.drawText(if (day.available) value else "—", center, min(128f - height, 32f), paint)
            if (day.available && !day.complete) {
                paint.color = warning
                canvas.drawCircle(center, 152f, 3f, paint)
            }
            paint.textSize = 32f
            paint.color = if (index == 6) accent else muted
            canvas.drawText(if (index == 6) "今天" else day.date.format(DateTimeFormatter.ofPattern("M/d")), center, 190f, paint)
        }
        return bitmap
    }
}
