package cn.net.rms.chatroom.data.websocket

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.data.model.Attachment
import cn.net.rms.chatroom.data.model.Message
import cn.net.rms.chatroom.data.model.VoiceUser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.util.concurrent.TimeUnit
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

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

@Singleton
class ChatWebSocket @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "ChatWebSocket"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    private var webSocket: WebSocket? = null
    private val _events = MutableSharedFlow<WebSocketEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<WebSocketEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var currentToken: String? = null
    private var currentChannelId: Long? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    fun connect(token: String, channelId: Long) {
        disconnect(sendEvent = false)

        currentToken = token
        currentChannelId = channelId
        shouldReconnect = true
        reconnectAttempts = 0

        doConnect()
    }

    private fun doConnect() {
        val token = currentToken ?: return
        val channelId = currentChannelId ?: return

        if (_connectionState.value == ConnectionState.RECONNECTING) {
            // Keep reconnecting state
        } else {
            _connectionState.value = ConnectionState.CONNECTING
        }

        val url = "${BuildConfig.WS_BASE_URL}/ws/chat/$channelId?token=$token"
        Log.d(TAG, "Connecting to WebSocket: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to channel $channelId")
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0
                _events.tryEmit(WebSocketEvent.Connected(channelId))
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _connectionState.value = ConnectionState.DISCONNECTED
                stopHeartbeat()
                _events.tryEmit(WebSocketEvent.Error(t.message ?: "WebSocket error"))
                _events.tryEmit(WebSocketEvent.Disconnected)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: code=$code, reason=$reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                stopHeartbeat()
                _events.tryEmit(WebSocketEvent.Disconnected)

                // Only reconnect if it wasn't a normal close initiated by us
                if (code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: code=$code, reason=$reason")
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val type = json.get("type")?.asString

            when (type) {
                "message" -> {
                    // Parse attachments if present
                    val attachments = if (json.has("attachments") && !json.get("attachments").isJsonNull) {
                        val attachmentsType = object : TypeToken<List<Attachment>>() {}.type
                        gson.fromJson<List<Attachment>>(json.get("attachments"), attachmentsType)
                    } else {
                        null
                    }

                    val message = Message(
                        id = json.get("id").asLong,
                        channelId = json.get("channel_id")?.asLong ?: 0L,
                        userId = json.get("user_id").asLong,
                        username = json.get("username").asString,
                        content = json.get("content")?.asString ?: "",
                        createdAt = json.get("created_at").asString,
                        attachments = attachments
                    )
                    Log.d(TAG, "Received message: ${message.id} from ${message.username}, attachments: ${attachments?.size ?: 0}")
                    _events.tryEmit(WebSocketEvent.NewMessage(message))
                }
                "user_joined" -> {
                    val user = gson.fromJson(json.getAsJsonObject("user"), VoiceUser::class.java)
                    _events.tryEmit(WebSocketEvent.UserJoined(user))
                }
                "user_left" -> {
                    val userId = json.get("user_id").asLong
                    _events.tryEmit(WebSocketEvent.UserLeft(userId))
                }
                "pong", "connected" -> {
                    Log.v(TAG, "Received $type")
                }
                else -> {
                    Log.d(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
            _events.tryEmit(WebSocketEvent.Error("Failed to parse message: ${e.message}"))
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    sendPing()
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendPing() {
        try {
            val pingJson = gson.toJson(mapOf("type" to "ping"))
            val sent = webSocket?.send(pingJson) ?: false
            if (sent) {
                Log.v(TAG, "Sent ping")
            } else {
                Log.w(TAG, "Failed to send ping, connection may be lost")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ping", e)
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) {
            Log.d(TAG, "Reconnect disabled, not scheduling")
            return
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS)")
            shouldReconnect = false
            _events.tryEmit(WebSocketEvent.Error("Max reconnect attempts reached"))
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = calculateReconnectDelay()
            Log.d(TAG, "Scheduling reconnect in ${delayMs}ms (attempt ${reconnectAttempts + 1}/$MAX_RECONNECT_ATTEMPTS)")

            _connectionState.value = ConnectionState.RECONNECTING
            delay(delayMs)

            if (shouldReconnect && isActive) {
                reconnectAttempts++
                Log.d(TAG, "Attempting reconnect #$reconnectAttempts")
                doConnect()
            }
        }
    }

    private fun calculateReconnectDelay(): Long {
        // Exponential backoff: 1s, 2s, 4s, 8s, ... up to 30s
        val delay = INITIAL_RECONNECT_DELAY_MS * (1L shl minOf(reconnectAttempts, 5))
        return minOf(delay, MAX_RECONNECT_DELAY_MS)
    }

    fun sendMessage(content: String, attachmentIds: List<Long> = emptyList()): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send message, not connected")
            return false
        }

        return try {
            val payload = mutableMapOf<String, Any>(
                "type" to "message",
                "content" to content
            )
            if (attachmentIds.isNotEmpty()) {
                payload["attachment_ids"] = attachmentIds
            }
            val json = gson.toJson(payload)
            webSocket?.send(json) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            false
        }
    }

    fun disconnect(sendEvent: Boolean = true) {
        Log.d(TAG, "Disconnecting WebSocket")
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()

        webSocket?.close(1000, "User disconnected")
        webSocket = null

        _connectionState.value = ConnectionState.DISCONNECTED
        currentToken = null
        currentChannelId = null

        if (sendEvent) {
            _events.tryEmit(WebSocketEvent.Disconnected)
        }
    }

    fun reconnect() {
        val token = currentToken
        val channelId = currentChannelId
        if (token != null && channelId != null) {
            Log.d(TAG, "Manual reconnect requested")
            disconnect(sendEvent = false)
            // Restore token and channelId after disconnect cleared them
            currentToken = token
            currentChannelId = channelId
            shouldReconnect = true
            reconnectAttempts = 0
            doConnect()
        } else {
            Log.w(TAG, "Cannot reconnect, no previous connection info")
        }
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    fun cleanup() {
        Log.d(TAG, "Cleaning up ChatWebSocket")
        disconnect(sendEvent = false)
        scope.cancel()
    }
}
