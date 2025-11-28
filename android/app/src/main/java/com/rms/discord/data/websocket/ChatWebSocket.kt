package com.rms.discord.data.websocket

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.rms.discord.BuildConfig
import com.rms.discord.data.model.Message
import com.rms.discord.data.model.VoiceUser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import javax.inject.Inject
import javax.inject.Singleton

sealed class WebSocketEvent {
    data class NewMessage(val message: Message) : WebSocketEvent()
    data class UserJoined(val user: VoiceUser) : WebSocketEvent()
    data class UserLeft(val userId: Long) : WebSocketEvent()
    data class Connected(val channelId: Long) : WebSocketEvent()
    object Disconnected : WebSocketEvent()
    data class Error(val error: String) : WebSocketEvent()
}

@Singleton
class ChatWebSocket @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) {
    private var webSocket: WebSocket? = null
    private val eventChannel = Channel<WebSocketEvent>(Channel.BUFFERED)
    val events: Flow<WebSocketEvent> = eventChannel.receiveAsFlow()

    fun connect(token: String, channelId: Long) {
        disconnect()

        val url = "${BuildConfig.WS_BASE_URL}/ws/chat/$channelId?token=$token"
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                eventChannel.trySend(WebSocketEvent.Connected(channelId))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                eventChannel.trySend(WebSocketEvent.Error(t.message ?: "WebSocket error"))
                eventChannel.trySend(WebSocketEvent.Disconnected)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                eventChannel.trySend(WebSocketEvent.Disconnected)
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            when (json.get("type")?.asString) {
                "message" -> {
                    val data = json.getAsJsonObject("data")
                    val message = gson.fromJson(data, Message::class.java)
                    eventChannel.trySend(WebSocketEvent.NewMessage(message))
                }
                "user_joined" -> {
                    val user = gson.fromJson(json.getAsJsonObject("user"), VoiceUser::class.java)
                    eventChannel.trySend(WebSocketEvent.UserJoined(user))
                }
                "user_left" -> {
                    val userId = json.get("user_id").asLong
                    eventChannel.trySend(WebSocketEvent.UserLeft(userId))
                }
            }
        } catch (e: Exception) {
            eventChannel.trySend(WebSocketEvent.Error("Failed to parse message: ${e.message}"))
        }
    }

    fun sendMessage(content: String) {
        val json = gson.toJson(mapOf("type" to "message", "content" to content))
        webSocket?.send(json)
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }
}
