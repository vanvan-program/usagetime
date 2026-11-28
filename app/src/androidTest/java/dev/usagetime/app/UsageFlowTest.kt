package dev.usagetime.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.usagetime.app.data.*
import dev.usagetime.core.Grain
import dev.usagetime.core.Period
import kotlinx.coroutines.runBlocking

class UsageFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun tab(label: String) = compose.onNode(hasText(label) and isSelectable())

    @Test fun demoStatisticsDetailsAndComparison(){
        compose.waitUntil(10000){compose.onAllNodesWithText("先体验示例数据").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("先体验示例数据").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("使用趋势").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("示例数据").assertExists()
        compose.onNodeWithText("折线").performScrollTo().performClick()
        tab("应用").performClick()
        compose.onNodeWithText("搜索应用").performScrollTo().performTextInput("微信")
        compose.onNode(hasText("微信") and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回应用列表").assertExists()
        compose.onNodeWithText("主要使用时间 · 候选估计累计").assertExists()
        tab("对比").performClick()
        compose.onNodeWithText("月对比").performClick()
        compose.onNodeWithText("月对比").assertIsSelected()
        compose.onNodeWithText("完整周期").performClick()
        tab("设置").performClick()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        compose.onNodeWithText("浅色").performClick()
    }
    @Test fun toolbarRangeIsSharedWithCategoryNavigatorAndWeekSelectsWholePeriod() {
        val model = compose.runOnIdle { ViewModelProvider(compose.activity)[UsageModel::class.java] }
        compose.runOnIdle { model.useDemo(true) }
        compose.waitUntil(10_000) { model.display.report != null && !model.computing }
        val today = compose.runOnIdle { dev.usagetime.core.Dates.date(model.now, model.zone) }
        compose.onNodeWithContentDescription("选择自定义日期").performClick()
        compose.onNodeWithText("最近7天").performClick()
        compose.runOnIdle {
            assertEquals(Period.CUSTOM, model.selection.period)
            assertEquals(today.minusDays(6) to today, model.selection.custom)
        }
        tab("分类").performClick()
        compose.onNodeWithContentDescription("选择统计周期").performClick()
        compose.onNodeWithText("选择日期范围").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("本周").performClick()
        compose.onNodeWithContentDescription("选择统计周期").performClick()
        compose.onNodeWithText("选择统计周").assertExists()
        compose.onNodeWithText("上周").performClick()
        compose.runOnIdle {
            assertEquals(Period.WEEK, model.selection.period)
            assertEquals(dev.usagetime.core.Dates.week(today).minusWeeks(1), model.selection.anchor)
        }
    }

    @Test fun summaryTabsKeepTheRenderedReportAndDateArrowsStillReload() {
        val model = compose.runOnIdle { ViewModelProvider(compose.activity)[UsageModel::class.java] }
        compose.runOnIdle { model.useDemo(true) }
        compose.waitUntil(10_000) { model.display.report != null && !model.computing && !model.busy }
        val shared = compose.runOnIdle { model.display }
        assertTrue(shared.report!!.total > 0)

        repeat(3) {
            for ((label, page) in listOf("应用" to 1, "分类" to 4, "总览" to 0)) {
                tab(label).performClick()
                compose.runOnIdle {
                    assertEquals(page, model.selection.page)
                    assertSame(shared, model.display)
                    assertFalse(model.computing)
                }
            }
        }

        tab("分类").performClick()
        compose.onNodeWithText("7天").performClick()
        compose.waitUntil(10_000) { model.display.report != null && !model.computing && !model.busy }
        val current = compose.runOnIdle { model.display.report!!.window }
        compose.onNodeWithContentDescription("下一周期").assertIsNotEnabled()
        compose.onNodeWithContentDescription("上一周期").performClick()
        compose.waitUntil(10_000) { model.display.report != null && !model.computing && !model.busy }
        assertTrue(compose.runOnIdle { model.display.report!!.window.end } <= current.start)
        compose.onNodeWithContentDescription("下一周期").performClick()
        compose.waitUntil(10_000) { model.display.report != null && !model.computing && !model.busy }
        assertEquals(current, compose.runOnIdle { model.display.report!!.window })
        compose.onNodeWithContentDescription("下一周期").assertIsNotEnabled()
    }

    @Test fun denseChartKeepsIndividualDataPointSelectionWhenChangingStyle() {
        val model = compose.runOnIdle { ViewModelProvider(compose.activity)[UsageModel::class.java] }
        compose.runOnIdle {
            model.useDemo(true)
            model.change { it.copy(period = Period.ALL, grain = Grain.DAY) }
        }
        compose.waitUntil(10_000) {
            (model.display.report?.buckets?.size ?: 0) >= 80 && !model.computing && !model.busy
        }
        compose.onNodeWithText("折线").performScrollTo()
        fun findChart(style: String) = compose.onNode(hasContentDescription(style, substring = true))
        fun selectedPoint(style: String) =
            findChart(style).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

        findChart("柱状图").performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertTrue(action(45f))
        }
        assertEquals(45f, selectedPoint("柱状图"), 0f)
        compose.onNodeWithText("折线").performClick()
        assertEquals(45f, selectedPoint("折线图"), 0f)
        compose.onNodeWithContentDescription("下一个数据点").performScrollTo().performClick()
        assertEquals(46f, selectedPoint("折线图"), 0f)
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
