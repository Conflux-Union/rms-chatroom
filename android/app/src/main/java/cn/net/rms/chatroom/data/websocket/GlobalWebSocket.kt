package cn.net.rms.chatroom.data.websocket

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.data.model.VoiceUser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import javax.inject.Inject
import javax.inject.Singleton

sealed class GlobalWebSocketEvent {
    data class VoiceUsersUpdate(val users: Map<Long, List<VoiceUser>>) : GlobalWebSocketEvent()
    data class ReadPositionSync(
        val channelId: Long,
        val lastReadMessageId: Long,
        val hasMention: Boolean,
        val lastMentionMessageId: Long?
    ) : GlobalWebSocketEvent()
    object Connected : GlobalWebSocketEvent()
    object Disconnected : GlobalWebSocketEvent()
    data class Error(val error: String) : GlobalWebSocketEvent()
}

@Singleton
class GlobalWebSocket @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "GlobalWebSocket"
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_TIMEOUT_MS = 3_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    private var webSocket: WebSocket? = null
    private val _events = MutableSharedFlow<GlobalWebSocketEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<GlobalWebSocketEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Expose voice users as StateFlow for direct observation
    private val _voiceChannelUsers = MutableStateFlow<Map<Long, List<VoiceUser>>>(emptyMap())
    val voiceChannelUsers: StateFlow<Map<Long, List<VoiceUser>>> = _voiceChannelUsers.asStateFlow()

    private var currentToken: String? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = false
    private var waitingForPong = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var heartbeatTimeoutJob: Job? = null
    private var reconnectJob: Job? = null

    fun connect(token: String) {
        if (_connectionState.value == ConnectionState.CONNECTED && currentToken == token) {
            Log.d(TAG, "Already connected with same token")
            return
        }

        disconnect(sendEvent = false)

        currentToken = token
        shouldReconnect = true
        reconnectAttempts = 0

        doConnect()
    }

    private fun doConnect() {
        val token = currentToken ?: return

        if (_connectionState.value == ConnectionState.RECONNECTING) {
            // Keep reconnecting state
        } else {
            _connectionState.value = ConnectionState.CONNECTING
        }

        val url = "${BuildConfig.WS_BASE_URL}/ws/global?token=$token"
        Log.d(TAG, "Connecting to Global WebSocket")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Global WebSocket connected")
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0
                _events.tryEmit(GlobalWebSocketEvent.Connected)
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Global WebSocket failure: ${t.message}", t)
                _connectionState.value = ConnectionState.DISCONNECTED
                stopHeartbeat()
                _events.tryEmit(GlobalWebSocketEvent.Error(t.message ?: "WebSocket error"))
                _events.tryEmit(GlobalWebSocketEvent.Disconnected)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Global WebSocket closed: code=$code, reason=$reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                stopHeartbeat()
                _events.tryEmit(GlobalWebSocketEvent.Disconnected)

                if (code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Global WebSocket closing: code=$code, reason=$reason")
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            Log.v(TAG, "Received message: $text")
            val json = JsonParser.parseString(text).asJsonObject
            val type = json.get("type")?.asString
            Log.d(TAG, "Message type: $type")

            when (type) {
                "voice_users_update" -> {
                    // Parse users: Map<channelId, List<VoiceUser>>
                    val usersJson = json.getAsJsonObject("users")
                    val usersMap = mutableMapOf<Long, List<VoiceUser>>()

                    usersJson?.entrySet()?.forEach { (channelIdStr, usersArray) ->
                        val channelId = channelIdStr.toLongOrNull() ?: return@forEach
                        val userListType = object : TypeToken<List<VoiceUser>>() {}.type
                        val users: List<VoiceUser> = gson.fromJson(usersArray, userListType)
                        usersMap[channelId] = users
                    }

                    Log.d(TAG, "Voice users update: ${usersMap.size} channels")
                    _voiceChannelUsers.value = usersMap
                    _events.tryEmit(GlobalWebSocketEvent.VoiceUsersUpdate(usersMap))
                }
                "pong" -> {
                    if (json.has("data") && json.get("data").asString == "cute") {
                        Log.v(TAG, "Received pong: cute")
                        handlePong()
                    }
                }
                "read_position_sync" -> {
                    val channelId = json.get("channel_id")?.asLong ?: return
                    val lastReadMessageId = json.get("last_read_message_id")?.asLong ?: return
                    val hasMention = json.get("has_mention")?.asBoolean ?: false
                    val lastMentionMessageIdElement = json.get("last_mention_message_id")
                    val lastMentionMessageId = if (lastMentionMessageIdElement != null && !lastMentionMessageIdElement.isJsonNull) {
                        lastMentionMessageIdElement.asLong
                    } else {
                        null
                    }

                    Log.d(TAG, "Read position sync: channel=$channelId, lastRead=$lastReadMessageId, hasMention=$hasMention")
                    _events.tryEmit(GlobalWebSocketEvent.ReadPositionSync(
                        channelId = channelId,
                        lastReadMessageId = lastReadMessageId,
                        hasMention = hasMention,
                        lastMentionMessageId = lastMentionMessageId
                    ))
                }
                "connected" -> {
                    Log.d(TAG, "Global WebSocket server confirmed connection")
                }
                else -> {
                    Log.d(TAG, "Unknown global message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse global message: $text", e)
        }
    }

    private fun handlePong() {
        waitingForPong = false
        heartbeatTimeoutJob?.cancel()
        heartbeatTimeoutJob = null
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_connectionState.value == ConnectionState.CONNECTED && !waitingForPong) {
                    sendPing()
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatTimeoutJob?.cancel()
        heartbeatTimeoutJob = null
        waitingForPong = false
    }

    private fun sendPing() {
        try {
            val pingJson = gson.toJson(mapOf("type" to "ping", "data" to "tribios"))
            val sent = webSocket?.send(pingJson) ?: false
            if (sent) {
                Log.v(TAG, "Sent ping: tribios")
                waitingForPong = true

                heartbeatTimeoutJob?.cancel()
                heartbeatTimeoutJob = scope.launch {
                    delay(HEARTBEAT_TIMEOUT_MS)
                    if (waitingForPong) {
                        Log.w(TAG, "Heartbeat timeout, reconnecting...")
                        waitingForPong = false
                        disconnect(sendEvent = false)
                        scheduleReconnect()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ping", e)
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            shouldReconnect = false
            _events.tryEmit(GlobalWebSocketEvent.Error("Max reconnect attempts reached"))
            return
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = calculateReconnectDelay()
            Log.d(TAG, "Scheduling reconnect in ${delayMs}ms (attempt ${reconnectAttempts + 1})")

            _connectionState.value = ConnectionState.RECONNECTING
            delay(delayMs)

            if (shouldReconnect && isActive) {
                reconnectAttempts++
                doConnect()
            }
        }
    }

    private fun calculateReconnectDelay(): Long {
        val delay = INITIAL_RECONNECT_DELAY_MS * (1L shl minOf(reconnectAttempts, 5))
        return minOf(delay, MAX_RECONNECT_DELAY_MS)
    }

    fun disconnect(sendEvent: Boolean = true) {
        Log.d(TAG, "Disconnecting Global WebSocket")
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()

        webSocket?.close(1000, "User disconnected")
        webSocket = null

        _connectionState.value = ConnectionState.DISCONNECTED
        currentToken = null

        if (sendEvent) {
            _events.tryEmit(GlobalWebSocketEvent.Disconnected)
        }
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    /**
     * Send read position update to server for cross-device sync.
     */
    fun sendReadPositionUpdate(
        channelId: Long,
        lastReadMessageId: Long,
        hasMention: Boolean = false,
        lastMentionMessageId: Long? = null
    ) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send read position update: not connected")
            return
        }

        try {
            val message = buildMap {
                put("type", "read_position_update")
                put("channel_id", channelId)
                put("last_read_message_id", lastReadMessageId)
                put("has_mention", hasMention)
                if (lastMentionMessageId != null) {
                    put("last_mention_message_id", lastMentionMessageId)
                }
            }
            val json = gson.toJson(message)
            val sent = webSocket?.send(json) ?: false
            if (sent) {
                Log.d(TAG, "Sent read position update: channel=$channelId, lastRead=$lastReadMessageId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending read position update", e)
        }
    }

    fun updateVoiceChannelUsers(users: Map<Long, List<VoiceUser>>) {
        _voiceChannelUsers.value = users
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up GlobalWebSocket")
        disconnect(sendEvent = false)
        scope.cancel()
    }
}
