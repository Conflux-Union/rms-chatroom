package com.rms.discord.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rms.discord.data.model.Channel
import com.rms.discord.data.model.ChannelType
import com.rms.discord.data.model.Server
import com.rms.discord.data.repository.ChatRepository
import com.rms.discord.data.websocket.ConnectionState
import com.rms.discord.data.websocket.WebSocketEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainState(
    val isLoading: Boolean = true,
    val isMessagesLoading: Boolean = false,
    val servers: List<Server> = emptyList(),
    val currentServer: Server? = null,
    val currentChannel: Channel? = null,
    val error: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {
    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    val messages = chatRepository.messages
    val connectionState: StateFlow<ConnectionState> = chatRepository.connectionState

    init {
        loadServers()
        observeWebSocket()
    }

    private fun loadServers() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            chatRepository.fetchServers()
                .onSuccess { servers ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        servers = servers
                    )
                    // Auto-select first server
                    servers.firstOrNull()?.let { selectServer(it.id) }
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                }
        }
    }

    fun selectServer(serverId: Long) {
        viewModelScope.launch {
            chatRepository.fetchServer(serverId)
                .onSuccess { server ->
                    _state.value = _state.value.copy(currentServer = server)
                    // Auto-select first text channel
                    server.channels?.firstOrNull { it.type == ChannelType.TEXT }
                        ?.let { selectChannel(it) }
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = e.message)
                }
        }
    }

    fun selectChannel(channel: Channel) {
        // Disconnect from previous channel
        chatRepository.disconnectFromChannel()

        chatRepository.setCurrentChannel(channel)
        _state.value = _state.value.copy(currentChannel = channel)

        // Load messages for text channels
        if (channel.type == ChannelType.TEXT) {
            loadMessages(channel.id)
            // Connect WebSocket for real-time messages
            chatRepository.connectToChannel(channel.id)
        }
    }

    private fun loadMessages(channelId: Long) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isMessagesLoading = true)
            chatRepository.fetchMessages(channelId)
                .onSuccess {
                    _state.value = _state.value.copy(isMessagesLoading = false)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        isMessagesLoading = false,
                        error = e.message
                    )
                }
        }
    }

    fun refreshMessages() {
        val channelId = _state.value.currentChannel?.id ?: return
        loadMessages(channelId)
    }

    private fun observeWebSocket() {
        viewModelScope.launch {
            chatRepository.webSocketEvents.collect { event ->
                when (event) {
                    is WebSocketEvent.NewMessage -> {
                        Log.d(TAG, "New message received: ${event.message.id}")
                    }
                    is WebSocketEvent.Connected -> {
                        Log.d(TAG, "WebSocket connected to channel ${event.channelId}")
                    }
                    is WebSocketEvent.Disconnected -> {
                        Log.d(TAG, "WebSocket disconnected")
                    }
                    is WebSocketEvent.Error -> {
                        Log.e(TAG, "WebSocket error: ${event.error}")
                        _state.value = _state.value.copy(error = event.error)
                    }
                    else -> { /* Handle other events */ }
                }
            }
        }
    }

    fun sendMessage(content: String) {
        val channelId = _state.value.currentChannel?.id ?: return
        viewModelScope.launch {
            chatRepository.sendMessage(channelId, content)
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "发送失败: ${e.message}")
                }
        }
    }

    fun reconnectWebSocket() {
        chatRepository.reconnectWebSocket()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        chatRepository.disconnectFromChannel()
    }
}
