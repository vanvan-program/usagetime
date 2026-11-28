package dev.usagetime.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

class ReportDatePickerTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 1, 8)
    private var result: Pair<LocalDate, LocalDate>? = null
    private var dismissed = false

    private fun show(range: Boolean, dark: Boolean = false): StateRestorationTester {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            HindsightTheme(dark) {
                ReportCalendarPicker(today.minusDays(3), today, today, range, "日期", { dismissed = true }) { a, b ->
                    result = a to b
                }
            }
        }
        return restore
    }

    private fun capture(name: String) {
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun reverseRangeSurvivesRecreationAndYearBoundary() {
        val restore = show(range = true, dark = true)
        capture("report-calendar-dark.png")
        compose.onNodeWithContentDescription("2026年1月9日").assertIsNotEnabled()
        compose.onNodeWithContentDescription("2026年1月5日").assertIsSelected().performClick()
        compose.onNodeWithContentDescription("上一月").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("2025年12月").assertExists()
        compose.onNodeWithText("2026/1/5 · 再点一天完成范围").assertExists()
        assertNull(result)
        compose.onNodeWithContentDescription("2025年12月30日").performClick()
        assertEquals(LocalDate.of(2025, 12, 30) to today.minusDays(3), result)
    }

    @Test fun cancelledFirstTapDoesNotCommit() {
        show(range = true)
        capture("report-calendar-light.png")
        val day = compose.onNodeWithContentDescription("2026年1月3日")
        day.performClick()
        compose.onNodeWithText("取消").performClick()
        assertTrue(dismissed)
        assertNull(result)
    }

    @Test fun sameDayRangeIsAllowed() {
        show(range = true)
        val day = compose.onNodeWithContentDescription("2026年1月3日")
        day.performClick()
        day.performClick()
        assertEquals(today.minusDays(5) to today.minusDays(5), result)
    }

    @Test fun shortcutsCountBothEndpoints() {
        show(range = true)
        compose.onNodeWithText("最近7天").performClick()
        assertEquals(today.minusDays(6) to today, result)
        compose.onNodeWithText("最近30天").performClick()
        assertEquals(today.minusDays(29) to today, result)
    }

    @Test fun singleDayPicksImmediatelyAfterMonthJump() {
        show(range = false)
        compose.onNodeWithContentDescription("切换年月选择").performClick()
        compose.onNodeWithContentDescription("上一年").performClick()
        compose.onNodeWithText("2月").performClick()
        compose.onNodeWithContentDescription("2025年2月28日").performClick()
        assertEquals(LocalDate.of(2025, 2, 28) to LocalDate.of(2025, 2, 28), result)
    }
}
