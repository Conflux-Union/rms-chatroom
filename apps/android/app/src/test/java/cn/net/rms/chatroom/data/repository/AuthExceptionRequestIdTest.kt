package cn.net.rms.chatroom.data.repository

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import java.net.UnknownHostException

class AuthExceptionRequestIdTest {

    private fun httpException(code: Int, requestId: String?): HttpException {
        val builder = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://chatroom.rms.net.cn/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Server Error")
        if (requestId != null) {
            builder.header("X-Request-Id", requestId)
        }
        val raw = builder.build()
        return HttpException(retrofit2.Response.error<Any>("".toResponseBody(null), raw))
    }

    @Test
    fun `extracts request id from http exception response`() {
        val id = "cxu_chat_req_0123456789abcdef0123456a"
        val e = httpException(500, id).toAuthException()

        assertEquals(id, e.requestId)
        assertTrue(e.message!!.contains("服务器错误 (500)"))
        assertTrue(e.message!!.contains("请求ID: $id"))
    }

    @Test
    fun `message stays clean without request id header`() {
        val e = httpException(403, null).toAuthException()

        assertNull(e.requestId)
        assertTrue(e.message!!.startsWith("服务器错误 (403): "))
        assertFalse(e.message!!.contains("请求ID"))
    }

    @Test
    fun `network errors carry no request id`() {
        val e = UnknownHostException("dns failure").toAuthException()

        assertNull(e.requestId)
        assertFalse(e.message!!.contains("请求ID"))
    }

    @Test
    fun `pattern finds request id embedded in error text`() {
        val text = "发送失败: 服务器错误 (500): oops\n请求ID: cxu_chat_req_0123456789abcdef0123456a"

        assertEquals("cxu_chat_req_0123456789abcdef0123456a", REQUEST_ID_PATTERN.find(text)?.value)
    }

    @Test
    fun `pattern ignores malformed ids`() {
        assertNull(REQUEST_ID_PATTERN.find("请求ID: cxu_chat_req_short"))
        assertNull(REQUEST_ID_PATTERN.find("no id here"))
    }
}
