package cn.net.rms.chatroom.data.websocket

import android.util.Log
import cn.net.rms.chatroom.data.auth.TokenAuthenticator
import cn.net.rms.chatroom.data.monitor.NetworkMonitor
import cn.net.rms.chatroom.data.telemetry.TelemetryReporter
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

/**
 * Shared connection machinery for the app-level WebSocket endpoints (chat,
 * global, music): token refresh before every handshake, app-level heartbeat,
 * jittered exponential backoff, and immediate reconnect when the network
 * returns. Subclasses provide the endpoint URL and frame parsing; the base
 * owns the socket lifecycle.
 *
 * Incoming frames are queued on an unbounded channel and pumped into
 * [events] by a single coroutine, so a burst can never silently drop events
 * and event order matches frame order.
 */
abstract class BaseWebSocket<E>(
    private val client: OkHttpClient,
    protected val gson: Gson,
    private val tokenAuthenticator: TokenAuthenticator,
    private val telemetryReporter: TelemetryReporter,
    private val networkMonitor: NetworkMonitor,
    private val tag: String,
    private val wsName: String,
) {
    companion object {
        // The server pings connections idle >60s and closes those idle >90s;
        // a 25s heartbeat stays well under both while waking the radio far
        // less often than the previous 5s interval.
        private const val HEARTBEAT_INTERVAL_MS = 25_000L
        private const val HEARTBEAT_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<E>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<E> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val eventQueue = Channel<E>(Channel.UNLIMITED)

    protected var webSocket: WebSocket? = null
        private set

    private var currentToken: String? = null
    private var shouldReconnect = false
    private var reconnectAttempts = 0
    private var waitingForPong = false
    private var lastDisconnectReason: String? = null

    // Bumped on every socket replacement; callbacks from a replaced
    // connection are recognized as stale and ignored.
    private val generation = AtomicLong()

    private var heartbeatJob: Job? = null
    private var heartbeatTimeoutJob: Job? = null
    private var reconnectJob: Job? = null

    init {
        scope.launch {
            for (event in eventQueue) _events.emit(event)
        }
        scope.launch {
            networkMonitor.isOnline.collect { online ->
                if (online) onNetworkRestored()
            }
        }
    }

    /** Endpoint URL including the auth token. */
    protected abstract fun buildUrl(token: String): String

    /**
     * Map one server frame to a broadcast event. May update subclass-owned
     * state as a side effect; return null for frames that carry no event.
     * The heartbeat "pong" frame never reaches here.
     */
    protected abstract fun handleMessage(json: JsonObject): E?

    protected abstract fun connectedEvent(): E
    protected abstract fun disconnectedEvent(): E
    protected abstract fun errorEvent(message: String): E

    /** Readiness beyond holding a token (music also requires a room). */
    protected open fun isReadyToConnect(): Boolean = true

    protected open fun onParseError(e: Exception) {}

    /** Called when the user disconnects; not on internal reconnects. */
    protected open fun onDisconnected() {}

    open fun connect(token: String) {
        teardownSocket()
        currentToken = token
        shouldReconnect = true
        reconnectAttempts = 0
        doConnect()
    }

    fun disconnect(sendEvent: Boolean = true) {
        Log.d(tag, "Disconnecting WebSocket")
        teardownSocket()
        currentToken = null
        onDisconnected()
        if (sendEvent) emitEvent(disconnectedEvent())
    }

    /**
     * Manual reconnect from the UI. Works even after [disconnect] cleared the
     * token: falls back to refreshing whatever credentials are stored.
     */
    fun reconnect() {
        Log.d(tag, "Manual reconnect requested")
        scope.launch {
            val token = currentToken ?: tokenAuthenticator.getFreshToken()
            if (token == null) {
                Log.w(tag, "Cannot reconnect, no token available")
                return@launch
            }
            teardownSocket()
            currentToken = token
            shouldReconnect = true
            reconnectAttempts = 0
            doConnect()
        }
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    protected fun isConnectedWith(token: String): Boolean = currentToken == token

    protected fun emitEvent(event: E) {
        eventQueue.trySend(event) // UNLIMITED capacity: always accepted
    }

    /** Send one JSON frame on the live socket; false when not connected. */
    protected fun sendJson(payload: Any): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) return false
        return try {
            webSocket?.send(gson.toJson(payload)) ?: false
        } catch (e: Exception) {
            Log.e(tag, "Error sending frame", e)
            false
        }
    }

    private fun isConnectable(): Boolean = currentToken != null && isReadyToConnect()

    private fun doConnect() {
        if (!isConnectable()) return

        if (_connectionState.value != ConnectionState.RECONNECTING) {
            _connectionState.value = ConnectionState.CONNECTING
        }

        scope.launch {
            if (!shouldReconnect) return@launch
            // WS auth happens only at handshake; refresh a near-expiry token first
            tokenAuthenticator.getFreshToken()?.let { currentToken = it }
            openWebSocket()
        }
    }

    private fun openWebSocket() {
        val token = currentToken ?: return
        if (!isConnectable()) return

        generation.incrementAndGet()
        val myGeneration = generation.get()
        webSocket?.cancel()
        webSocket = null

        // Never log the URL: it carries the JWT and release builds keep Log.d.
        Log.d(tag, "Connecting to WebSocket")

        webSocket = client.newWebSocket(
            Request.Builder().url(buildUrl(token)).build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (stale(myGeneration)) {
                        ws.cancel()
                        return
                    }
                    Log.d(tag, "WebSocket connected")
                    _connectionState.value = ConnectionState.CONNECTED
                    reconnectAttempts = 0
                    emitEvent(connectedEvent())
                    startHeartbeat()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (stale(myGeneration)) return
                    handleFrame(text)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    if (stale(myGeneration)) return
                    Log.e(tag, "WebSocket failure: ${t.message}", t)
                    lastDisconnectReason = t.message ?: t.toString()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    stopHeartbeat()
                    emitEvent(errorEvent(t.message ?: "WebSocket error"))
                    emitEvent(disconnectedEvent())
                    scheduleReconnect()
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (stale(myGeneration)) return
                    Log.d(tag, "WebSocket closed: code=$code, reason=$reason")
                    lastDisconnectReason = "closed code=$code $reason"
                    _connectionState.value = ConnectionState.DISCONNECTED
                    stopHeartbeat()
                    emitEvent(disconnectedEvent())
                    // A close we initiated (code 1000) is not a failure.
                    if (code != 1000) scheduleReconnect()
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    if (stale(myGeneration)) return
                }
            }
        )
    }

    private fun stale(myGeneration: Long): Boolean = generation.get() != myGeneration

    private fun handleFrame(text: String) {
        val json = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse message: $text", e)
            onParseError(e)
            return
        }

        if (json.get("type")?.asString == "pong" && json.get("data")?.asString == "cute") {
            handlePong()
            return
        }

        val event = try {
            handleMessage(json)
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle message: $text", e)
            onParseError(e)
            return
        }
        if (event != null) emitEvent(event)
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
            val sent = webSocket?.send(gson.toJson(mapOf("type" to "ping", "data" to "tribios"))) ?: false
            if (sent) {
                Log.v(tag, "Sent ping")
                waitingForPong = true
                heartbeatTimeoutJob?.cancel()
                heartbeatTimeoutJob = scope.launch {
                    delay(HEARTBEAT_TIMEOUT_MS)
                    if (waitingForPong) {
                        Log.w(tag, "Heartbeat timeout, reconnecting")
                        lastDisconnectReason = "heartbeat timeout"
                        teardownForReconnect()
                    }
                }
            } else {
                Log.w(tag, "Failed to send ping, connection may be lost")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error sending ping", e)
        }
    }

    // A heartbeat timeout means the socket is dead (silent network loss never
    // reaches onFailure). Tear it down WITHOUT clearing shouldReconnect or the
    // token — clearing them here used to cancel the reconnect below and strand
    // the socket until the process restarted.
    private fun teardownForReconnect() {
        stopHeartbeat()
        generation.incrementAndGet()
        webSocket?.cancel()
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        emitEvent(disconnectedEvent())
        scheduleReconnect()
    }

    // Kill the live socket and scheduled work without touching auth state or
    // subclass hooks — shared by user disconnect and re-connect.
    private fun teardownSocket() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()
        generation.incrementAndGet()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) {
            Log.d(tag, "Reconnect disabled, not scheduling")
            return
        }

        if (reconnectAttempts >= ReconnectBackoff.MAX_ATTEMPTS) {
            Log.w(tag, "Max reconnect attempts reached (${ReconnectBackoff.MAX_ATTEMPTS})")
            shouldReconnect = false
            telemetryReporter.report(
                "ws_reconnect_exhausted", wsName,
                meta = mapOf("ws" to wsName, "attempts" to reconnectAttempts, "reason" to lastDisconnectReason)
            )
            emitEvent(errorEvent("Max reconnect attempts reached"))
            return
        }

        // Report the start of each outage, not every retry of it.
        if (reconnectAttempts == 0) {
            telemetryReporter.report(
                "ws_reconnect", wsName,
                meta = mapOf("ws" to wsName, "reason" to lastDisconnectReason)
            )
        }

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = ReconnectBackoff.delayMs(reconnectAttempts)
            Log.d(tag, "Scheduling reconnect in ${delayMs}ms (attempt ${reconnectAttempts + 1}/${ReconnectBackoff.MAX_ATTEMPTS})")

            _connectionState.value = ConnectionState.RECONNECTING
            delay(delayMs)

            if (shouldReconnect && isActive) {
                reconnectAttempts++
                Log.d(tag, "Attempting reconnect #$reconnectAttempts")
                doConnect()
            }
        }
    }

    private fun onNetworkRestored() {
        val state = _connectionState.value
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) return
        if (currentToken == null) return // never connected, or deliberately disconnected

        Log.d(tag, "Network restored, reconnecting immediately")
        if (!shouldReconnect) shouldReconnect = true // revive after attempts exhausted
        reconnectAttempts = 0
        reconnectJob?.cancel()
        doConnect()
    }
}
