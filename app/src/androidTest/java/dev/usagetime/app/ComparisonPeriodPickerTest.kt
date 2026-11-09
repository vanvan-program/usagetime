package dev.usagetime.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.io.File

class ComparisonPeriodPickerTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 11, 9)

    @Test fun monthGridSelectsMonthWithoutChoosingDay() {
        var selected: LocalDate? = null
        var dismissed = false
        compose.setContent {
            HindsightTheme(dark = false) {
                ComparisonPeriodPicker(today, true, "统计月", { dismissed = true }, { selected = it }, today)
            }
        }
        compose.onNodeWithText("11月").assertIsSelected()
        compose.onNodeWithText("12月").assertIsNotEnabled()
        capture("comparison-month-light.png")
        compose.onNodeWithText("10月").performClick()
        compose.runOnIdle {
            assertEquals(LocalDate.of(2026, 10, 1), selected)
            assertTrue(dismissed)
        }
    }

    @Test fun weekListSelectsWholeCrossMonthWeek() {
        var selected: LocalDate? = null
        compose.setContent {
            HindsightTheme(dark = true) {
                ComparisonPeriodPicker(today, false, "对照周", {}, { selected = it }, today)
            }
        }
        compose.onNodeWithText("11/9 – 11/15").assertIsSelected()
        capture("comparison-week-dark.png")
        compose.onNodeWithText("10/26 – 11/1").performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 26), selected) }
    }

    @Test fun weekPickerCanJumpToAnotherYearAndMonth() {
        compose.setContent {
            HindsightTheme(dark = false) {
                ComparisonPeriodPicker(today, false, "统计周", {}, {}, today)
            }
        }
        compose.onNodeWithContentDescription("切换年月选择").performClick()
        compose.onNodeWithContentDescription("上一年").performClick()
        compose.onNodeWithText("2月").performClick()
        compose.onNodeWithText("2025年2月").assertExists()
        compose.onNodeWithText("1/27 – 2/2").assertExists()
    }

    @Test fun returningFromYearGridDoesNotOpenFutureMonth() {
        compose.setContent {
            HindsightTheme(dark = false) {
                ComparisonPeriodPicker(LocalDate.of(2025, 12, 1), false, "统计周", {}, {}, today)
            }
        }
        compose.onNodeWithContentDescription("切换年月选择").performClick()
        compose.onNodeWithContentDescription("下一年").performClick()
        compose.onNodeWithContentDescription("切换年月选择").performClick()
        compose.onNodeWithText("2026年11月").assertExists()
        compose.onNodeWithText("11/9 – 11/15").assertExists()
    }

    private fun capture(name: String) {
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
