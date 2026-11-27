package dev.usagetime.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.usagetime.app.sync.AppUpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import java.net.URI

private val releaseMarkdownParser = Parser.builder().extensions(
    listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create())
).build()

@Composable
internal fun ReleaseNotesMarkdown(
    markdown: String,
    baseUrl: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String? = null,
    header: (@Composable () -> Unit)? = null
) {
    val blocks by produceState(emptyList<Node>(), markdown) {
        value = emptyList()
        value = withContext(Dispatchers.Default) { releaseMarkdownParser.parse(markdown).children().toList() }
    }
    val openUrl = rememberUpdatedState(onOpenUrl)
    val accent = hindsightColors().accent
    val links = remember(baseUrl, accent) {
        { destination: String ->
            releaseNotesLink(destination, baseUrl, accent) { url -> openUrl.value(url) }
        }
    }

    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (header != null) item { header() }
        if (markdown.isBlank() && emptyMessage != null) {
            item { Text(emptyMessage, color = hindsightColors().textMuted) }
        }
        items(blocks) { ReleaseNotesBlock(it, links) }
    }
}

@Composable
private fun ReleaseNotesBlock(
    node: Node,
    linkFor: (String) -> LinkAnnotation?
) {
    val colors = hindsightColors()
    when (node) {
        is Heading -> Text(
            text = remember(node, linkFor) { releaseNotesText(node, linkFor) },
            style = if (node.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            color = colors.textStrong,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() }
        )
        is Paragraph -> Text(
            text = remember(node, linkFor) { releaseNotesText(node, linkFor) },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textRegular
        )
        is ListBlock -> {
            val spacing = if (node.isTight) 4.dp else 8.dp
            val start = (node as? OrderedList)?.markerStartNumber ?: 1
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                node.children().forEachIndexed { index, item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val marker = if (node is OrderedList) "${start + index}." else "•"
                        val task = item.firstChild as? TaskListItemMarker
                        if (task == null) {
                            Text(marker, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
                        } else {
                            Checkbox(
                                checked = task.isChecked,
                                onCheckedChange = null,
                                modifier = Modifier.semantics {
                                    stateDescription = if (task.isChecked) "已完成" else "未完成"
                                }
                            )
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing)) {
                            item.children().forEach { ReleaseNotesBlock(it, linkFor) }
                        }
                    }
                }
            }
        }
        is BlockQuote -> Column(
            Modifier.fillMaxWidth().background(colors.cardElevated, MaterialTheme.shapes.small).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            node.children().forEach { ReleaseNotesBlock(it, linkFor) }
        }
        is FencedCodeBlock -> ReleaseNotesCode(node.literal)
        is IndentedCodeBlock -> ReleaseNotesCode(node.literal)
        is ThematicBreak -> HorizontalDivider()
        is TableBlock -> ReleaseNotesTable(node, linkFor)
    }
}

@Composable
private fun ReleaseNotesTable(table: TableBlock, linkFor: (String) -> LinkAnnotation?) {
    val rows = remember(table) {
        table.children().flatMap { it.children() }
            .map { row -> row.children().filterIsInstance<TableCell>().toList() }
            .toList()
    }
    val columns = rows.firstOrNull()?.size ?: return
    if (columns == 0) return

    val colors = hindsightColors()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = (maxWidth / columns).coerceAtLeast(96.dp)
        Column(Modifier.horizontalScroll(rememberScrollState()).width(cellWidth * columns)) {
            rows.forEach { row ->
                Row(Modifier.background(if (row.firstOrNull()?.isHeader == true) colors.fill else colors.card)) {
                    row.forEach { cell ->
                        Text(
                            text = remember(cell, linkFor) { releaseNotesText(cell, linkFor) },
                            modifier = Modifier.width(cellWidth).padding(horizontal = 8.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textRegular,
                            fontWeight = if (cell.isHeader) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = when (cell.alignment) {
                                TableCell.Alignment.CENTER -> TextAlign.Center
                                TableCell.Alignment.RIGHT -> TextAlign.End
                                else -> TextAlign.Start
                            }
                        )
                    }
                }
                HorizontalDivider(color = colors.border)
            }
        }
    }
}

@Composable
private fun ReleaseNotesCode(code: String) {
    val colors = hindsightColors()
    Text(
        text = code.removeSuffix("\n"),
        modifier = Modifier.fillMaxWidth().background(colors.cardElevated, MaterialTheme.shapes.small).padding(8.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = colors.textRegular
    )
}

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

private fun releaseNotesText(node: Node, linkFor: (String) -> LinkAnnotation?): AnnotatedString =
    buildAnnotatedString { node.children().forEach { appendReleaseNotesInline(it, linkFor) } }

private fun AnnotatedString.Builder.appendReleaseNotesInline(node: Node, linkFor: (String) -> LinkAnnotation?) {
    val appendChildren = { node.children().forEach { appendReleaseNotesInline(it, linkFor) } }
    when (node) {
        is MarkdownText -> append(node.literal)
        is SoftLineBreak, is HardLineBreak -> append('\n')
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendChildren() }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendChildren() }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendChildren() }
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.literal) }
        is Link -> {
            val link = linkFor(node.destination)
            if (link == null) appendChildren() else withLink(link) { appendChildren() }
        }
        else -> appendChildren()
    }
}

private fun releaseNotesLink(
    destination: String,
    baseUrl: String,
    color: Color,
    onOpenUrl: (String) -> Unit
): LinkAnnotation? {
    if (destination.isBlank()) return null
    val url = runCatching { URI(baseUrl).resolve(destination).toASCIIString() }.getOrNull() ?: return null
    if (!AppUpdateChecker.isAllowedExternalUrl(url)) return null
    return LinkAnnotation.Url(
        url,
        TextLinkStyles(style = SpanStyle(color = color, textDecoration = TextDecoration.Underline))
    ) { onOpenUrl(url) }
}
