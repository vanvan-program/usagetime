package dev.usagetime.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.usagetime.app.data.*
import kotlinx.coroutines.runBlocking

class UsageFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun demoStatisticsDetailsAndComparison(){
        compose.waitUntil(10000){compose.onAllNodesWithText("先体验示例数据").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("先体验示例数据").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("使用趋势").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("示例数据").assertExists()
        compose.onNodeWithText("折线").performScrollTo().performClick()
        compose.onNodeWithText("应用",useUnmergedTree=true).performClick()
        compose.onNodeWithText("搜索应用").performScrollTo().performTextInput("微信")
        compose.onNode(hasText("微信") and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回应用列表").assertExists()
        compose.onNodeWithText("可归属累计（含推断）").assertExists()
        compose.onNodeWithText("对比",useUnmergedTree=true).performClick()
        compose.onNodeWithText("月对比").performClick()
        compose.onNodeWithText("月对比").assertIsSelected()
        compose.onNodeWithText("完整周期").performClick()
        compose.onNodeWithText("设置",useUnmergedTree=true).performClick()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.onNodeWithText("浅色").performClick()
    }
    @Test fun databaseDeduplicatesAndRejectsInvalidBackup()=runBlocking{
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=UsageStore(context,"qa-${java.util.UUID.randomUUID()}.db")
        val body="""{"format":"usagetime-backup","version":1,"intervals":[{"app":"qa.fixture","start":1000,"end":61000}],"coverage":[{"start":1000,"end":61000,"reliable":true}],"apps":[{"app":"qa.fixture","label":"QA"}]}"""
        store.importJson(body);store.importJson(body)
        assertEquals(1,store.readLegacy(dev.usagetime.core.Window(0,62000)).intervals.count{it.app=="qa.fixture"})
        assertTrue(store.exportJson().contains("usagetime-backup"))
        assertTrue(runCatching{store.importJson(body.replace("61000","-1"))}.isFailure)
    }
}
