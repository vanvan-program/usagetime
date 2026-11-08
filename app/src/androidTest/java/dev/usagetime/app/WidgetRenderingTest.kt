package dev.usagetime.app

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.usagetime.core.AppCategory
import dev.usagetime.core.AppTotal
import dev.usagetime.core.Bucket
import dev.usagetime.core.Dates
import dev.usagetime.core.UsageReport
import dev.usagetime.core.Window
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class WidgetRenderingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 11, 8)
    private val start = Dates.midnight(date, zone)
    private val now = start + 17 * 3600000
    private val apps = listOf(
        AppTotal("fixture.read", 72 * 60000), AppTotal("fixture.chat", 48 * 60000),
        AppTotal("fixture.video", 36 * 60000), AppTotal("fixture.work", 24 * 60000),
        AppTotal("fixture.game", 12 * 60000)
    )
    private val names = mapOf(
        "fixture.read" to "阅读", "fixture.chat" to "微信", "fixture.video" to "哔哩哔哩",
        "fixture.work" to "日历", "fixture.game" to "原神"
    )
    private fun fixture(): WidgetSnapshot {
        val hourly = (0..16).map { hour ->
            val millis = if (hour in 8..15) 24 * 60000L else 0
            Bucket(start + hour * 3600000, start + (hour + 1) * 3600000, "$hour", millis, true, true)
        }
        val today = UsageReport(Window(start, now), hourly, apps, now - start, true)
        val yesterday = UsageReport(Window(start - 86400000, now - 86400000),
            hourly.map { it.copy(start = it.start - 86400000, end = it.end - 86400000) },
            listOf(AppTotal("fixture.read", 168 * 60000)), now - start, true)
        val values = listOf(240, 180, 0, 0, 310, 145, 192)
        val daily = values.mapIndexed { index, minutes ->
            val dayStart = Dates.midnight(date.minusDays(6L - index), zone)
            Bucket(dayStart, minOf(dayStart + 86400000, now), "", minutes * 60000L, index != 6, index != 2)
        }
        return WidgetSnapshot(now, zone, today, yesterday,
            UsageReport(Window(start - 6 * 86400000, now), daily,
                listOf(AppTotal("fixture.read", values.sum() * 60000L)), 0, false),
            mapOf("fixture.read" to AppCategory.STUDY, "fixture.chat" to AppCategory.SOCIAL,
                "fixture.video" to AppCategory.VIDEO, "fixture.work" to AppCategory.WORK, "fixture.game" to AppCategory.GAME),
            "主要使用时间 · 估计累计", now - 5 * 60000, true)
    }

    @Test fun allFiveProvidersAreRegisteredAndExistingProviderRemainsCompatible() {
        val manager = AppWidgetManager.getInstance(context)
        WidgetStyle.entries.forEach { style ->
            assertTrue(style.name, manager.installedProviders.any { it.provider == ComponentName(context, style.provider) })
        }
    }

    @Test fun renderEveryStyleInBothThemesAndCheckRowsFit() {
        val sizes = mapOf(
            WidgetStyle.QUICK to (160 to 84), WidgetStyle.TODAY to (240 to 250),
            WidgetStyle.APPS to (320 to 290), WidgetStyle.CATEGORIES to (240 to 180),
            WidgetStyle.WEEK to (320 to 170)
        )
        instrumentation.runOnMainSync {
            for (dark in listOf(false, true)) {
                sizes.forEach { (style, size) ->
                    val rendered = render(style, size.first, size.second, fixture(), dark)
                    assertEquals(if (style == WidgetStyle.WEEK) "17时47分" else "3时12分",
                        rendered.findViewById<TextView>(R.id.widget_total).text.toString())
                    val rows = rendered.findViewById<ViewGroup>(R.id.widget_rows)
                    if (rows != null) {
                        val expected = if (style == WidgetStyle.APPS) 5 else 3
                        assertEquals(style.name, rows.childCount, expected)
                        val last = rows.getChildAt(rows.childCount - 1)
                        assertTrue("${style.name} rows clipped: ${last.bottom} > ${rows.height}", last.bottom <= rows.height)
                    }
                    save(rendered, "${style.name.lowercase()}-${if (dark) "dark" else "light"}")
                }
            }
        }
    }

    @Test fun resizeAndLargeFontFallbackDoNotLoseTotalOrShowFakeZero() {
        instrumentation.runOnMainSync {
            val empty = fixture().copy(today = fixture().today.copy(apps = emptyList(),
                buckets = emptyList(), fullyCovered = false))
            val small = render(WidgetStyle.TODAY, 160, 140, empty, false)
            assertEquals("未记录", small.findViewById<TextView>(R.id.widget_total).text.toString())
            assertNull(small.findViewById<View>(R.id.widget_rows))
            save(small, "today-small-empty")
            val zero = fixture().copy(today = fixture().today.copy(apps = emptyList(),
                buckets = fixture().today.buckets.map { it.copy(millis = 0) }))
            assertEquals("0分", render(WidgetStyle.TODAY, 240, 250, zero, true)
                .findViewById<TextView>(R.id.widget_total).text.toString())
            val configuration = Configuration(context.resources.configuration).apply { fontScale = 1.6f }
            val largeContext = context.createConfigurationContext(configuration)
            val large = render(WidgetStyle.TODAY, 240, 250, fixture(), false, largeContext)
            assertEquals("3时12分", large.findViewById<TextView>(R.id.widget_total).text.toString())
            val largeRows = large.findViewById<ViewGroup>(R.id.widget_rows)
            assertNotNull(largeRows)
            assertTrue(largeRows!!.childCount in 1..3)
            assertTrue(large.findViewById<View>(R.id.widget_updated).bottom <= large.height)
            save(large, "today-large-font")
        }
    }

    @Test fun zeroSizedHostOptionsKeepTheRequestedWidgetInsteadOfDowngrading() {
        instrumentation.runOnMainSync {
            listOf(WidgetStyle.TODAY to "今日概览", WidgetStyle.APPS to "应用排行").forEach { (style, label) ->
                val rendered = render(style, 0, 0, fixture(), false)
                assertTrue(rendered.findViewById<TextView>(R.id.widget_title).text.startsWith(label))
                assertNotNull(rendered.findViewById<View>(R.id.widget_rows))
            }
        }
    }

    @Test fun twoRowHostDoesNotDropBackToTheMinimalQuickWidget() {
        instrumentation.runOnMainSync {
            listOf(WidgetStyle.TODAY to "今日概览", WidgetStyle.APPS to "应用排行").forEach { (style, label) ->
                val rendered = render(style, 276, 169, fixture(), false)
                assertTrue(rendered.findViewById<TextView>(R.id.widget_title).text.startsWith(label))
                val rows = rendered.findViewById<ViewGroup>(R.id.widget_rows)
                assertNotNull(rows)
                assertTrue(rows!!.childCount in 1..3)
            }
        }
    }

    @Test fun errorsRemainReadableAndOfferRecovery() {
        instrumentation.runOnMainSync {
            WidgetStyle.entries.forEach { style ->
                val views = WidgetRenderer(context).error(style, 100 + style.ordinal)
                val rendered = views.apply(ContextThemeWrapper(context, android.R.style.Theme_Material_Light_NoActionBar), null)
                assertEquals("读取失败", rendered.findViewById<TextView>(R.id.widget_total).text.toString())
                assertTrue(rendered.findViewById<View>(R.id.widget_root).hasOnClickListeners())
                assertTrue(rendered.findViewById<View>(R.id.widget_refresh).hasOnClickListeners())
            }
        }
    }

    private fun render(style: WidgetStyle, width: Int, height: Int, data: WidgetSnapshot, dark: Boolean,
        renderingContext: Context = context): View {
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
        }
        val views = WidgetRenderer(renderingContext, dark).render(style, 100 + style.ordinal, options, data) { names[it] }
        val rendered = views.apply(ContextThemeWrapper(renderingContext, android.R.style.Theme_Material_Light_NoActionBar), null)
        val density = renderingContext.resources.displayMetrics.density
        rendered.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        rendered.layout(0, 0, rendered.measuredWidth, rendered.measuredHeight)
        return rendered
    }

    private fun save(rendered: View, name: String) {
        val bitmap = Bitmap.createBitmap(rendered.width, rendered.height, Bitmap.Config.ARGB_8888)
        rendered.draw(Canvas(bitmap))
        val directory = File(context.filesDir, "widget-qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
