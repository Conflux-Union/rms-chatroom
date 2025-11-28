package com.rms.discord.data.repository

import android.util.Log
import com.rms.discord.data.api.ApiService
import com.rms.discord.data.api.SendMessageBody
import com.rms.discord.data.model.Channel
import com.rms.discord.data.model.Message
import com.rms.discord.data.model.Server
import com.rms.discord.data.websocket.ChatWebSocket
import com.rms.discord.data.websocket.ConnectionState
import com.rms.discord.data.websocket.WebSocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val api: ApiService,
    private val authRepository: AuthRepository,
    private val webSocket: ChatWebSocket
) {
    companion object {
        private const val TAG = "ChatRepository"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _currentServer = MutableStateFlow<Server?>(null)
    val currentServer: StateFlow<Server?> = _currentServer.asStateFlow()

    private val _currentChannel = MutableStateFlow<Channel?>(null)
    val currentChannel: StateFlow<Channel?> = _currentChannel.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // Expose WebSocket state
    val connectionState: StateFlow<ConnectionState> = webSocket.connectionState
    val webSocketEvents = webSocket.events

    init {
        observeWebSocketEvents()
    }

    private fun observeWebSocketEvents() {
        scope.launch {
            webSocket.events.collect { event ->
                when (event) {
                    is WebSocketEvent.NewMessage -> {
                        Log.d(TAG, "Received new message: ${event.message.id}")
                        addMessage(event.message)
                    }
                    is WebSocketEvent.Connected -> {
                        Log.d(TAG, "WebSocket connected to channel ${event.channelId}")
                    }
                    is WebSocketEvent.Disconnected -> {
                        Log.d(TAG, "WebSocket disconnected")
                    }
                    is WebSocketEvent.Error -> {
                        Log.e(TAG, "WebSocket error: ${event.error}")
                    }
                    is WebSocketEvent.UserJoined -> {
                        Log.d(TAG, "User joined: ${event.user.username}")
                    }
                    is WebSocketEvent.UserLeft -> {
                        Log.d(TAG, "User left: ${event.userId}")
                    }
                }
            }
        }
    }

    suspend fun fetchServers(): Result<List<Server>> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val serverList = api.getServers(authRepository.getAuthHeader(token))
            _servers.value = serverList
            Result.success(serverList)
        } catch (e: Exception) {
            Log.e(TAG, "fetchServers failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun fetchServer(serverId: Long): Result<Server> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val server = api.getServer(authRepository.getAuthHeader(token), serverId)
            _currentServer.value = server
            Result.success(server)
        } catch (e: Exception) {
            Log.e(TAG, "fetchServer failed", e)
            Result.failure(e.toAuthException())
        }
    }

    fun setCurrentChannel(channel: Channel) {
        _currentChannel.value = channel
    }

    suspend fun fetchMessages(channelId: Long): Result<List<Message>> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val messageList = api.getMessages(authRepository.getAuthHeader(token), channelId)
            _messages.value = messageList
            Result.success(messageList)
        } catch (e: Exception) {
            Log.e(TAG, "fetchMessages failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun sendMessage(channelId: Long, content: String): Result<Message> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val message = api.sendMessage(
                authRepository.getAuthHeader(token),
                channelId,
                SendMessageBody(content)
            )
            _messages.value = _messages.value + message
            Result.success(message)
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
            Result.failure(e.toAuthException())
        }
    }

    fun addMessage(message: Message) {
        if (message.channelId == _currentChannel.value?.id) {
            // Avoid duplicate messages
            if (_messages.value.none { it.id == message.id }) {
                _messages.value = _messages.value + message
            }
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    // WebSocket connection management
    fun connectToChannel(channelId: Long) {
        val token = authRepository.getTokenBlocking() ?: run {
            Log.e(TAG, "Cannot connect to WebSocket: no token")
            return
        }
        Log.d(TAG, "Connecting to channel $channelId")
        webSocket.connect(token, channelId)
    }

    fun disconnectFromChannel() {
        Log.d(TAG, "Disconnecting from channel")
        webSocket.disconnect()
    }

    fun reconnectWebSocket() {
        Log.d(TAG, "Reconnecting WebSocket")
        webSocket.reconnect()
    }

    fun isWebSocketConnected(): Boolean = webSocket.isConnected()
}
