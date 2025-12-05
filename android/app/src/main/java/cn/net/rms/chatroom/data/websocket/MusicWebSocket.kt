package cn.net.rms.chatroom.data.websocket

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.data.model.Song
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

sealed class MusicWebSocketEvent {
    data class MusicStateUpdate(
        val roomName: String,
        val isPlaying: Boolean,
        val currentSong: Song?,
        val currentIndex: Int,
        val positionMs: Long,
        val durationMs: Long,
        val state: String,
        val queueLength: Int
    ) : MusicWebSocketEvent()
    object Connected : MusicWebSocketEvent()
    object Disconnected : MusicWebSocketEvent()
    data class Error(val error: String) : MusicWebSocketEvent()
}

@Singleton
class MusicWebSocket @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "MusicWebSocket"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    private var webSocket: WebSocket? = null
    private val _events = MutableSharedFlow<MusicWebSocketEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<MusicWebSocketEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var currentToken: String? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
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

        val url = "${BuildConfig.WS_BASE_URL}/ws/music?token=$token"
        Log.d(TAG, "Connecting to Music WebSocket")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Music WebSocket connected")
                _connectionState.value = ConnectionState.CONNECTED
                reconnectAttempts = 0
                _events.tryEmit(MusicWebSocketEvent.Connected)
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Music WebSocket failure: ${t.message}", t)
                _connectionState.value = ConnectionState.DISCONNECTED
                stopHeartbeat()
                _events.tryEmit(MusicWebSocketEvent.Error(t.message ?: "WebSocket error"))
                _events.tryEmit(MusicWebSocketEvent.Disconnected)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Music WebSocket closed: code=$code, reason=$reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                stopHeartbeat()
                _events.tryEmit(MusicWebSocketEvent.Disconnected)

                if (code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Music WebSocket closing: code=$code, reason=$reason")
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val type = json.get("type")?.asString

            when (type) {
                "music_state" -> {
                    val data = json.getAsJsonObject("data")
                    val roomName = data.get("room_name")?.asString ?: ""
                    val state = data.get("state")?.asString ?: "idle"
                    val positionMs = data.get("position_ms")?.asLong ?: 0L
                    val durationMs = data.get("duration_ms")?.asLong ?: 0L
                    val currentIndex = data.get("current_index")?.asInt ?: 0
                    val queueLength = data.get("queue_length")?.asInt ?: 0

                    val currentSong = if (data.has("current_song") && !data.get("current_song").isJsonNull) {
                        val songObj = data.getAsJsonObject("current_song")
                        Song(
                            mid = songObj.get("mid")?.asString ?: "",
                            name = songObj.get("name")?.asString ?: "",
                            artist = songObj.get("artist")?.asString ?: "",
                            album = songObj.get("album")?.asString ?: "",
                            duration = songObj.get("duration")?.asInt ?: 0,
                            cover = songObj.get("cover")?.asString ?: ""
                        )
                    } else null

                    Log.d(TAG, "Music state update: room=$roomName, state=$state, song=${currentSong?.name}")

                    _events.tryEmit(MusicWebSocketEvent.MusicStateUpdate(
                        roomName = roomName,
                        isPlaying = state == "playing",
                        currentSong = currentSong,
                        currentIndex = currentIndex,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        state = state,
                        queueLength = queueLength
                    ))
                }
                "connected" -> {
                    Log.d(TAG, "Music WebSocket server confirmed connection")
                }
                "pong" -> {
                    Log.v(TAG, "Received pong")
                }
                else -> {
                    Log.d(TAG, "Unknown music message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse music message: $text", e)
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ping", e)
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) {
            return
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            shouldReconnect = false
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
        Log.d(TAG, "Disconnecting Music WebSocket")
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()

        webSocket?.close(1000, "User disconnected")
        webSocket = null

        _connectionState.value = ConnectionState.DISCONNECTED
        currentToken = null

        if (sendEvent) {
            _events.tryEmit(MusicWebSocketEvent.Disconnected)
        }
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
}
