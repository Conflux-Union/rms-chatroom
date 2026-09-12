package cn.net.rms.chatroom.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import cn.net.rms.chatroom.ui.theme.SealDark
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Heading
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMarkdownTest {

    private fun render(content: String): AnnotatedString =
        renderMarkdownInlines(parseMessageMarkdown(content).firstChild)

    private fun AnnotatedString.linkUrls(): List<String> =
        getLinkAnnotations(0, length).map { (it.item as LinkAnnotation.Url).url }

    private fun AnnotatedString.hasSpan(match: (AnnotatedString.Range<SpanStyle>) -> Boolean): Boolean =
        spanStyles.any(match)

    // Web client renders with marked's breaks:true: a single newline is a line break.
    @Test
    fun `single newline renders as line break`() {
        assertEquals("line1\nline2", render("line1\nline2").text)
    }

    @Test
    fun `emphasis spans are applied`() {
        val s = render("**bold** *italic* ~~strike~~ `code`")
        assertTrue(s.hasSpan { it.item.fontWeight == FontWeight.Bold })
        assertTrue(s.hasSpan { it.item.fontStyle == FontStyle.Italic })
        assertTrue(s.hasSpan { it.item.textDecoration == TextDecoration.LineThrough })
        assertTrue(s.hasSpan { it.item.fontFamily == FontFamily.Monospace })
    }

    // GFM parity: marked autolinks bare URLs.
    @Test
    fun `bare URL is autolinked`() {
        val s = render("visit https://example.com now")
        assertEquals(listOf("https://example.com"), s.linkUrls())
        assertTrue(s.text.contains("https://example.com"))
    }

    // Images render as links (never loaded) to avoid viewer IP leaks — same
    // policy as the web client's renderer override.
    @Test
    fun `image renders as link with alt text`() {
        val s = render("![cat gif](https://example.com/track.gif)")
        assertEquals("cat gif", s.text)
        assertEquals(listOf("https://example.com/track.gif"), s.linkUrls())
    }

    @Test
    fun `image without alt links to the url itself`() {
        val s = render("![](https://example.com/track.gif)")
        assertEquals("https://example.com/track.gif", s.text)
        assertEquals(listOf("https://example.com/track.gif"), s.linkUrls())
    }

    @Test
    fun `mentions are highlighted on plain text`() {
        val s = render("ping @alice and @bob42!")
        val mentionSpans = s.spanStyles.filter {
            it.item.fontWeight == FontWeight.Medium && it.item.color == SealDark
        }
        assertEquals(2, mentionSpans.size)
        assertTrue(s.spanStyles.any { it.item.color == SealDark && s.text.substring(it.start, it.end) == "@alice" })
        assertTrue(s.spanStyles.any { it.item.color == SealDark && s.text.substring(it.start, it.end) == "@bob42" })
    }

    // Mentions inside code spans and link labels stay untouched, like the web
    // client's highlight pass.
    @Test
    fun `mentions inside code and links are not highlighted`() {
        val code = render("keep `@alice` plain")
        assertFalse(code.spanStyles.any { it.item.fontWeight == FontWeight.Medium })

        val link = render("[@alice](https://example.com)")
        assertFalse(link.spanStyles.any { it.item.fontWeight == FontWeight.Medium })
        assertEquals(listOf("https://example.com"), link.linkUrls())
    }

    @Test
    fun `gfm structures are parsed`() {
        val root = parseMessageMarkdown(
            """
            # Title
            ## Sub
            - item
            - [x] done
            - [ ] todo
            > quoted
            1. first
            2. second

            | a | b |
            |---|---|
            | 1 | 2 |
            """.trimIndent()
        )

        var node = root.firstChild
        val heading1 = node as? Heading
        assertNotNull(heading1)
        assertEquals(1, heading1!!.level)
        node = node.next
        assertEquals(2, (node as Heading).level)
        node = node.next

        val bullets = node as BulletList
        val secondItem = bullets.firstChild!!.next!!
        // The task marker is a direct child of the list item, before its paragraph
        val secondMarker = secondItem.firstChild as? TaskListItemMarker
        assertNotNull(secondMarker)
        assertTrue(secondMarker!!.isChecked)
        val thirdMarker = secondItem.next!!.firstChild as? TaskListItemMarker
        assertNotNull(thirdMarker)
        assertFalse(thirdMarker!!.isChecked)
        node = node.next

        assertTrue(node is BlockQuote)
        node = node.next

        val ordered = node as OrderedList
        assertEquals(1, ordered.startNumber)
        node = node.next

        val table = node as TableBlock
        assertTrue(table.firstChild is TableHead)
        assertTrue(table.firstChild!!.next is TableBody)
    }

    @Test
    fun `table rows are collected with header flag`() {
        val table = parseMessageMarkdown("| a | b |\n|---|---|\n| 1 | 2 |").firstChild as TableBlock
        val rows = tableRows(table)
        assertEquals(2, rows.size)
        assertTrue(rows[0].isHeader)
        assertFalse(rows[1].isHeader)
        assertEquals(2, rows[0].cells.size)
        assertEquals(2, rows[1].cells.size)
    }

    @Test
    fun `empty content renders empty`() {
        assertEquals("", render("").text)
        assertEquals("", renderMarkdownInlines(null).text)
    }

    @Test
    fun `plain text without markdown stays verbatim`() {
        val s = render("héllo world (中文) 100% a_b")
        assertEquals("héllo world (中文) 100% a_b", s.text)
    }
}
