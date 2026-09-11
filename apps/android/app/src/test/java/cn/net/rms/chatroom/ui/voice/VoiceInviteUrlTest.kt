package cn.net.rms.chatroom.ui.voice

import cn.net.rms.chatroom.data.model.InviteCreateResponse
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInviteUrlTest {
    @Test
    fun `builds canonical web URL from token-only response`() {
        val response = Gson().fromJson(
            """{"token":"invite-token"}""",
            InviteCreateResponse::class.java
        )

        assertEquals(
            "https://chatroom.rms.net.cn/voice/invite/invite-token",
            buildVoiceInviteUrl("https://chatroom.rms.net.cn/", response.token)
        )
    }
}
