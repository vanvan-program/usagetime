package dev.usagetime.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HindsightComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ratioBarRendersProportionalWidths() {
        compose.setContent {
            HindsightTheme(dark = false) {
                Column {
                    listOf(0f, 0.25f, 1f).forEachIndexed { index, fraction ->
                        RatioBar(
                            progress = fraction,
                            color = Color.Red,
                            modifier = Modifier.width(100.dp).testTag("ratio-$index"),
                            height = 12.dp
                        )
                    }
                }
            }
        }
        listOf(0f, 0.25f, 1f).forEachIndexed { index, fraction ->
            val pixels = compose.onNodeWithTag("ratio-$index").captureToImage().toPixelMap()
            val filledPixels = (0 until pixels.width).count { column ->
                val pixel = pixels[column, pixels.height / 2]
                pixel.red > 0.9f && pixel.green < 0.1f && pixel.blue < 0.1f
            }
            assertEquals(fraction, filledPixels.toFloat() / pixels.width, 0.025f)
        }
    }

    @Test fun zeroUsageAndMissingRecordsAreDistinct() {
        compose.setContent {
            HindsightTheme(dark = false) {
                Column {
                    BigTimeValue(0L)
                    BigTimeValue(0L, available = false)
                    HindsightChip("周对比", selected = true, onClick = {})
                }
            }
        }
        compose.onNodeWithText("0").assertExists()
        compose.onNodeWithText("分").assertExists()
        compose.onNodeWithText("未记录").assertExists()
        compose.onNodeWithText("周对比").assertIsSelected()
    }
}
