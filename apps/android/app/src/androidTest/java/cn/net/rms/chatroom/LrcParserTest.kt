package cn.net.rms.chatroom

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.net.rms.chatroom.util.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LrcParserTest {

    @Test
    fun parse_expandsMultipleTimeTags_andAppliesOffset() {
        val lines = LrcParser.parse(
            "[ti:Song]\n[ar:Artist]\n[offset:500]\n[00:01.50][00:05.00]hello\n[00:03]world\nno timestamp\n[00:10.250]end"
        )
        assertEquals(
            listOf(1000L to "hello", 2500L to "world", 4500L to "hello", 9750L to "end"),
            lines
        )
    }

    @Test
    fun merge_attachesTranslationByRoundedTimestamp() {
        // Provider pairs carry identical timestamps, written with different
        // fraction widths ([00:01.5] vs [00:01.500] both resolve to 1500ms).
        val lines = LrcParser.parse("[00:01.5]hello\n[00:03.00]world")
        val merged = LrcParser.merge(lines, "[00:01.500]你好\n[00:03.0]世界")
        assertEquals("你好", merged[0].translation)
        assertEquals("世界", merged[1].translation)
        assertEquals(listOf(null, null), LrcParser.merge(lines, "").map { it.translation })
    }

    @Test
    fun activeIndex_tracksPlaybackPosition() {
        val merged = LrcParser.merge(
            LrcParser.parse("[00:01.50]hello\n[00:03.00]world"),
            ""
        )
        assertEquals(-1, LrcParser.activeIndex(merged, 0))
        assertEquals(0, LrcParser.activeIndex(merged, 1600))
        assertEquals(0, LrcParser.activeIndex(merged, 2999))
        assertEquals(1, LrcParser.activeIndex(merged, 3010))
        assertEquals(1, LrcParser.activeIndex(merged, 99999))
    }

    @Test
    fun parse_emptyInput_returnsEmpty() {
        assertEquals(emptyList<Pair<Long, String>>(), LrcParser.parse(""))
    }
}
