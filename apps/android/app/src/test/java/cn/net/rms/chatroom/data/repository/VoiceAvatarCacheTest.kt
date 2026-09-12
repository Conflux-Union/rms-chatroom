package cn.net.rms.chatroom.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAvatarCacheTest {
    @Test
    fun `adds non-blank avatar urls keyed by user id`() {
        val merged = mergeAvatarUrls(
            emptyMap(),
            mapOf("1" to "https://sso/avatars/1.png", "2" to null)
        )

        assertEquals(mapOf("1" to "https://sso/avatars/1.png"), merged)
    }

    @Test
    fun `blank url in a push round never evicts a known avatar`() {
        val current = mapOf("1" to "https://sso/avatars/1.png")

        assertEquals(
            current,
            mergeAvatarUrls(current, mapOf("1" to "", "2" to "  "))
        )
    }

    @Test
    fun `newer push overwrites a stale url`() {
        val merged = mergeAvatarUrls(
            mapOf("1" to "https://old/1.png"),
            mapOf("1" to "https://new/1.png")
        )

        assertEquals("https://new/1.png", merged["1"])
    }
}
