package com.rms.discord.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rms.discord.data.model.Channel
import com.rms.discord.data.model.Server
import com.rms.discord.data.repository.ChatRepository
import com.rms.discord.data.websocket.ChatWebSocket
import com.rms.discord.data.websocket.WebSocketEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainState(
    val isLoading: Boolean = true,
    val servers: List<Server> = emptyList(),
    val currentServer: Server? = null,
    val currentChannel: Channel? = null,
    val error: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val chatWebSocket: ChatWebSocket
) : ViewModel() {

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    val messages = chatRepository.messages

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
                    server.channels?.firstOrNull { it.type == com.rms.discord.data.model.ChannelType.TEXT }
                        ?.let { selectChannel(it) }
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = e.message)
                }
        }
    }

    fun selectChannel(channel: Channel) {
        chatRepository.setCurrentChannel(channel)
        _state.value = _state.value.copy(currentChannel = channel)

        // Load messages for text channels
        if (channel.type == com.rms.discord.data.model.ChannelType.TEXT) {
            viewModelScope.launch {
                chatRepository.fetchMessages(channel.id)
            }
        }
    }

    private fun observeWebSocket() {
        viewModelScope.launch {
            chatWebSocket.events.collect { event ->
                when (event) {
                    is WebSocketEvent.NewMessage -> {
                        chatRepository.addMessage(event.message)
                    }
                    is WebSocketEvent.Error -> {
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
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        chatWebSocket.disconnect()
    }
}
