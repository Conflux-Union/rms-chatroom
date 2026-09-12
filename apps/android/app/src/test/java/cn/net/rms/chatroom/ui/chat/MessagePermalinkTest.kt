package cn.net.rms.chatroom.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagePermalinkTest {
    @Test
    fun `builds canonical web permalink`() {
        assertEquals(
            "https://chatroom.rms.net.cn/1/8/123",
            buildMessagePermalink("https://chatroom.rms.net.cn/", 1, 8, 123)
        )
    }
}
