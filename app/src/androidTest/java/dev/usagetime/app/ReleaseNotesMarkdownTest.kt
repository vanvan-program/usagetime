package dev.usagetime.app

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReleaseNotesMarkdownTest {
    @get:Rule val compose = createComposeRule()

    @Test fun formatsReleaseNotesAndOpensOnlyAllowedLinks() {
        val baseUrl = "https://github.com/vanvan-program/usagetime/blob/v0.4.13/docs/"
        val markdown = """
            ## 本版更新

            3. **修复公告**，支持 `Markdown`
               - 嵌套条目

            [查看说明](QA.md) 和 [其他站点](https://example.com)

            ```text
            代码里的 **符号** 保持原样
            ```
        """.trimIndent()
        val openedUrls = mutableListOf<String>()

        compose.setContent {
            HindsightTheme(dark = false) {
                ReleaseNotesMarkdown(markdown, "${baseUrl}RELEASE-0.4.13.md", openedUrls::add)
            }
        }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("本版更新").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("3.").assertExists()
        compose.onNodeWithText("嵌套条目").assertExists()
        compose.onNodeWithText("代码里的 **符号** 保持原样").assertExists()
        val listItem = compose.onNodeWithText("修复公告，支持 Markdown")
            .fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(listItem.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(listItem.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })

        val linkedParagraph = compose.onNodeWithText("查看说明 和 其他站点")
        val text = linkedParagraph.fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url
        assertEquals("${baseUrl}QA.md", link.url)
        linkedParagraph.performFirstLinkClick()
        compose.runOnIdle { assertEquals(listOf(link.url), openedUrls) }
    }

    @Test fun rendersGitHubExtensionsAndScrollsToTheEndOfALongAnnouncement() {
        val firstSection = """
            | 版本 | 改动 |
            | --- | --- |
            | 43 | 公告排版 |

            - [x] 已检查
            - [ ] 待检查

            ~~旧公告~~

        """.trimIndent()
        val filler = "这段文字代表发布说明里的详细更新记录。".repeat(12)
        val markdown = "$firstSection\n\n" + List(24) { "$filler\n\n" }.joinToString("") + "## 公告结尾"

        compose.setContent {
            HindsightTheme(dark = false) {
                ReleaseNotesMarkdown(
                    markdown = markdown,
                    baseUrl = "https://github.com/vanvan-program/usagetime",
                    onOpenUrl = {},
                    modifier = Modifier.testTag("releaseNotes")
                )
            }
        }

        compose.waitUntil(5_000) { compose.onAllNodesWithText("公告排版").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("版本").assertExists()
        compose.onNodeWithText("已检查").assertExists()
        compose.onNodeWithText("待检查").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已完成")).assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "未完成")).assertExists()
        val deleted = compose.onNodeWithText("旧公告").fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(deleted.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })

        compose.onNodeWithTag("releaseNotes").performScrollToNode(hasText("公告结尾"))
        compose.onNodeWithText("公告结尾").assertExists()
    }
}
