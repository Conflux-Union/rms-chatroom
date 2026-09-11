package cn.net.rms.chatroom.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.net.rms.chatroom.ui.theme.SurfaceLighter
import cn.net.rms.chatroom.ui.theme.TiColor
import cn.net.rms.chatroom.ui.theme.TextMuted
import cn.net.rms.chatroom.ui.theme.TextSecondary
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

// GFM parity with the web client (marked, gfm + breaks): single newlines render
// as line breaks, bare URLs autolink, tables / strikethrough / task lists on.
private val MESSAGE_MARKDOWN_EXTENSIONS = listOf(
    AutolinkExtension.create(),
    StrikethroughExtension.create(),
    TablesExtension.create(),
    TaskListItemsExtension.create(),
)

private val MESSAGE_PARSER: Parser = Parser.builder()
    .extensions(MESSAGE_MARKDOWN_EXTENSIONS)
    .build()

// @word mentions match the web client: highlighted on plain text only; inside
// code spans, code blocks and link labels they stay untouched.
private val MENTION_REGEX = Regex("@(\\w+)")

private val MarkdownBorder = TextMuted.copy(alpha = 0.4f)
private val MarkdownLinkStyle = SpanStyle(color = TiColor, textDecoration = TextDecoration.Underline)
private val MarkdownMentionStyle = SpanStyle(color = TiColor, fontWeight = FontWeight.Medium)
private val MarkdownInlineCodeStyle = SpanStyle(fontFamily = FontFamily.Monospace, background = SurfaceLighter)

fun parseMessageMarkdown(content: String): Node = MESSAGE_PARSER.parse(content)

/**
 * Renders a chat message as markdown blocks (GFM subset: headings, emphasis,
 * code, quotes, lists, task lists, tables, links). Images degrade to links and
 * raw HTML is stripped — matching the web client's sanitized pipeline.
 */
@Composable
fun MarkdownMessage(content: String, modifier: Modifier = Modifier) {
    val root = remember(content) { MESSAGE_PARSER.parse(content) }
    MarkdownBlockChildren(parent = root, inBlockQuote = false, modifier = modifier)
}

@Composable
private fun MarkdownBlockChildren(parent: Node, inBlockQuote: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var node = parent.firstChild
        while (node != null) {
            when (node) {
                is Paragraph -> MarkdownBodyText(
                    text = renderMarkdownInlines(node),
                    inBlockQuote = inBlockQuote
                )
                is Heading -> MarkdownBodyText(
                    text = renderMarkdownInlines(node),
                    inBlockQuote = inBlockQuote,
                    headingLevel = node.level
                )
                is FencedCodeBlock -> MarkdownCodeBlock(node.literal)
                is IndentedCodeBlock -> MarkdownCodeBlock(node.literal)
                is ThematicBreak -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    thickness = 1.dp,
                    color = MarkdownBorder
                )
                is BlockQuote -> MarkdownBlockQuote(node)
                is BulletList -> MarkdownList(node, startNumber = null, inBlockQuote = inBlockQuote)
                is OrderedList -> MarkdownList(node, startNumber = node.startNumber, inBlockQuote = inBlockQuote)
                is TableBlock -> MarkdownTable(node)
                // Rendered by the list marker as ☑/☐, never on its own
                is TaskListItemMarker -> Unit
                is HtmlBlock -> Unit
                else -> MarkdownBlockChildren(node, inBlockQuote)
            }
            node = node.next
        }
    }
}

// Headings stay chat-sized, only the top level stands out (web: 1.2em / 1.1em / 1em).
@Composable
private fun MarkdownBodyText(text: AnnotatedString, inBlockQuote: Boolean, headingLevel: Int? = null) {
    val base = MaterialTheme.typography.bodyMedium
    val style = when (headingLevel) {
        null -> base
        1 -> base.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
        2 -> base.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
        else -> base.copy(fontWeight = FontWeight.Bold)
    }
    Text(
        text = text,
        style = style,
        color = if (inBlockQuote) TextMuted else TextSecondary
    )
}

@Composable
private fun MarkdownBlockQuote(quote: BlockQuote) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(3.dp)
                .background(TextMuted.copy(alpha = 0.55f))
        )
        MarkdownBlockChildren(
            parent = quote,
            inBlockQuote = true,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
        )
    }
}

@Composable
private fun MarkdownList(list: ListBlock, startNumber: Int?, inBlockQuote: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var item = list.firstChild
        var number = startNumber
        while (item != null) {
            if (item is ListItem) {
                val taskMarker = taskListMarker(item)
                val marker = when {
                    taskMarker != null -> if (taskMarker.isChecked) "☑" else "☐"
                    number != null -> "${number}."
                    else -> "•"
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MarkdownBodyText(text = AnnotatedString(marker), inBlockQuote = inBlockQuote)
                    MarkdownBlockChildren(
                        parent = item,
                        inBlockQuote = inBlockQuote,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (number != null) number += 1
            item = item.next
        }
    }
}

// The task marker is a direct child of the list item, before its paragraph.
private fun taskListMarker(item: ListItem): TaskListItemMarker? =
    item.firstChild as? TaskListItemMarker

@Composable
private fun MarkdownCodeBlock(code: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceLighter,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = code.trimEnd('\n'),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = TextSecondary,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun MarkdownTable(table: TableBlock) {
    Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        var section = table.firstChild
        while (section != null) {
            val isHeader = section is TableHead
            var row = section.firstChild
            while (row != null) {
                Row(modifier = if (isHeader) Modifier.background(SurfaceLighter) else Modifier) {
                    var cell = row.firstChild
                    while (cell != null) {
                        Box(
                            modifier = Modifier
                                .border(0.5.dp, MarkdownBorder)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = renderMarkdownInlines(cell),
                                style = if (isHeader) {
                                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                },
                                color = TextSecondary
                            )
                        }
                        cell = cell.next
                    }
                }
                row = row.next
            }
            section = section.next
        }
    }
}

internal fun renderMarkdownInlines(block: Node?): AnnotatedString {
    val builder = AnnotatedString.Builder()
    renderInlineChildren(block, builder, SpanStyle(), inProtectedText = false)
    return builder.toAnnotatedString()
}

private fun renderInlineChildren(
    parent: Node?,
    builder: AnnotatedString.Builder,
    style: SpanStyle,
    inProtectedText: Boolean
) {
    var node = parent?.firstChild ?: return
    while (node != null) {
        when (node) {
            is MdText -> appendMarkdownText(builder, node.literal, style, inProtectedText)
            is Code -> builder.withStyle(style.merge(MarkdownInlineCodeStyle)) { append(node.literal) }
            is Emphasis -> renderInlineChildren(
                node, builder,
                style.merge(SpanStyle(fontStyle = FontStyle.Italic)), inProtectedText
            )
            is StrongEmphasis -> renderInlineChildren(
                node, builder,
                style.merge(SpanStyle(fontWeight = FontWeight.Bold)), inProtectedText
            )
            is Strikethrough -> renderInlineChildren(
                node, builder,
                style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)), inProtectedText
            )
            is Link -> builder.withLink(
                LinkAnnotation.Url(node.destination, TextLinkStyles(style = style.merge(MarkdownLinkStyle)))
            ) {
                renderInlineChildren(node, this, style, inProtectedText = true)
            }
            // Images render as links instead of loading remote content: remote
            // images would leak viewer IPs and enable tracking pixels, and media
            // belongs to the attachment system (same policy as the web client).
            is Image -> {
                val label = imageAltText(node) ?: node.destination
                builder.withLink(
                    LinkAnnotation.Url(node.destination, TextLinkStyles(style = style.merge(MarkdownLinkStyle)))
                ) {
                    withStyle(style.merge(MarkdownLinkStyle)) { append(label) }
                }
            }
            is SoftLineBreak, is HardLineBreak -> builder.withStyle(style) { append("\n") }
            // Task list checkboxes are drawn by the list marker; raw HTML is
            // stripped entirely (stricter than the web sanitizer allowlist).
            is TaskListItemMarker, is HtmlInline, is HtmlBlock -> Unit
            else -> renderInlineChildren(node, builder, style, inProtectedText)
        }
        node = node.next
    }
}

private fun imageAltText(image: Node): String? {
    var node = image.firstChild
    val alt = StringBuilder()
    while (node != null) {
        if (node is MdText) alt.append(node.literal)
        node = node.next
    }
    return alt.toString().takeIf { it.isNotBlank() }
}

private fun appendMarkdownText(
    builder: AnnotatedString.Builder,
    text: String,
    style: SpanStyle,
    inProtectedText: Boolean
) {
    if (inProtectedText) {
        builder.withStyle(style) { append(text) }
        return
    }
    var last = 0
    for (match in MENTION_REGEX.findAll(text)) {
        if (match.range.first > last) {
            builder.withStyle(style) { append(text.substring(last, match.range.first)) }
        }
        builder.withStyle(style.merge(MarkdownMentionStyle)) { append(match.value) }
        last = match.range.last + 1
    }
    if (last < text.length) {
        builder.withStyle(style) { append(text.substring(last)) }
    }
}
