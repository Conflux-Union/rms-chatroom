package cn.net.rms.chatroom.util

/**
 * LRC lyric parsing, ported from the web's utils/lyrics.ts so both clients
 * accept the same raw LRC payloads from /api/music/song/:mid/lyric.
 */

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null
)

object LrcParser {

    // [mm:ss.xx] / [mm:ss.xxx] / [mm:ss] — providers emit all three.
    private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val offsetTag = Regex("^\\s*\\[offset:\\s*([+-]?\\d+)\\s*]")

    /** Fractional part is centiseconds with two digits, milliseconds with three. */
    private fun fractionToMs(frac: String): Int {
        // Kotlin regex returns "" for a non-participating optional group.
        if (frac.isEmpty()) return 0
        return when (frac.length) {
            1 -> frac.toInt() * 100
            2 -> frac.toInt() * 10
            else -> frac.toInt()
        }
    }

    fun parse(text: String): List<Pair<Long, String>> {
        if (text.isEmpty()) return emptyList()
        var offsetMs = 0L
        val lines = mutableListOf<Pair<Long, String>>()
        for (raw in text.split("\n", "\r\n")) {
            val offset = offsetTag.find(raw)
            if (offset != null) {
                // Positive offset shifts lyric timestamps up (lines show earlier).
                offsetMs = offset.groupValues[1].toLong()
                continue
            }
            val tags = timeTag.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val content = timeTag.replace(raw, "").trim()
            if (content.isEmpty()) continue
            for (tag in tags) {
                val timeMs = tag.groupValues[1].toLong() * 60000 +
                    tag.groupValues[2].toLong() * 1000 +
                    fractionToMs(tag.groupValues[3]) -
                    offsetMs
                if (timeMs >= 0) lines += timeMs to content
            }
        }
        lines.sortBy { it.first }
        return lines
    }

    // Timestamps are matched rounded to 10ms to absorb float jitter between
    // the two LRC payloads without ever colliding two real lines.
    private fun timeKey(timeMs: Long): Long = Math.round(timeMs / 10.0)

    fun merge(lines: List<Pair<Long, String>>, translation: String): List<LyricLine> {
        val result = lines.map { LyricLine(it.first, it.second) }
        if (translation.isEmpty() || result.isEmpty()) return result
        val byTime = HashMap<Long, String>()
        for ((timeMs, text) in parse(translation)) byTime[timeKey(timeMs)] = text
        return result.map { line ->
            line.copy(translation = byTime[timeKey(line.timeMs)])
        }
    }

    /** Index of the line active at timeMs (last line already passed), -1 before the first. */
    fun activeIndex(lines: List<LyricLine>, timeMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs > timeMs) break
            idx = i
        }
        return idx
    }
}
