package com.rms.discord.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rms.discord.data.model.Channel
import com.rms.discord.data.model.ChannelType
import com.rms.discord.data.model.Server
import com.rms.discord.data.model.VoiceUser
import com.rms.discord.data.api.AppUpdateResponse
import com.rms.discord.data.repository.BugReportRepository
import com.rms.discord.data.repository.ChatRepository
import com.rms.discord.data.repository.UpdateRepository
import com.rms.discord.data.websocket.ConnectionState
import com.rms.discord.data.websocket.WebSocketEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainState(
    val isLoading: Boolean = true,
    val isMessagesLoading: Boolean = false,
    val servers: List<Server> = emptyList(),
    val currentServer: Server? = null,
    val currentChannel: Channel? = null,
    val error: String? = null,
    val bugReportSubmitting: Boolean = false,
    val bugReportId: String? = null,
    val updateInfo: AppUpdateResponse? = null,
    val isDownloading: Boolean = false,
    val downloadComplete: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val bugReportRepository: BugReportRepository,
    private val updateRepository: UpdateRepository
) : ViewModel() {
    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    val messages = chatRepository.messages
    val connectionState: StateFlow<ConnectionState> = chatRepository.connectionState
    val voiceChannelUsers: StateFlow<Map<Long, List<VoiceUser>>> = chatRepository.voiceChannelUsers

    private var voiceUsersPollingJob: Job? = null

    init {
        loadServers()
        observeWebSocket()
        checkForUpdate()
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
                    // Start polling voice channel users
                    startVoiceUsersPolling()
                    // Auto-select first text channel
                    server.channels?.firstOrNull { it.type == ChannelType.TEXT }
                        ?.let { selectChannel(it) }
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = e.message)
                }
        }
    }

    private fun startVoiceUsersPolling() {
        voiceUsersPollingJob?.cancel()
        voiceUsersPollingJob = viewModelScope.launch {
            while (isActive) {
                chatRepository.fetchAllVoiceChannelUsers()
                delay(5000) // Poll every 5 seconds
            }
        }
    }

    private fun stopVoiceUsersPolling() {
        voiceUsersPollingJob?.cancel()
        voiceUsersPollingJob = null
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

    fun submitBugReport() {
        viewModelScope.launch {
            _state.value = _state.value.copy(bugReportSubmitting = true)
            bugReportRepository.submitBugReport()
                .onSuccess { reportId ->
                    _state.value = _state.value.copy(
                        bugReportSubmitting = false,
                        bugReportId = reportId
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        bugReportSubmitting = false,
                        error = "上报失败: ${e.message}"
                    )
                }
        }
    }

    fun clearBugReportId() {
        _state.value = _state.value.copy(bugReportId = null)
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            updateRepository.checkUpdate()
                .onSuccess { updateInfo ->
                    _state.value = _state.value.copy(updateInfo = updateInfo)
                }
        }
    }

    fun dismissUpdate() {
        _state.value = _state.value.copy(updateInfo = null)
    }

    fun downloadUpdate() {
        val downloadUrl = _state.value.updateInfo?.downloadUrl ?: return
        _state.value = _state.value.copy(isDownloading = true)
        updateRepository.downloadUpdate(downloadUrl)
    }

    fun onDownloadComplete(success: Boolean) {
        _state.value = _state.value.copy(
            isDownloading = false,
            downloadComplete = success
        )
        if (success) {
            updateRepository.installApk()
        }
    }

    fun getUpdateRepository(): UpdateRepository = updateRepository

    fun createChannel(name: String, type: String) {
        val serverId = _state.value.currentServer?.id ?: return
        viewModelScope.launch {
            chatRepository.createChannel(serverId, name, type)
                .onSuccess {
                    // Refresh server to update channel list
                    selectServer(serverId)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "创建频道失败: ${e.message}")
                }
        }
    }

    fun deleteChannel(channelId: Long) {
        val serverId = _state.value.currentServer?.id ?: return
        val currentChannelId = _state.value.currentChannel?.id
        viewModelScope.launch {
            chatRepository.deleteChannel(serverId, channelId)
                .onSuccess {
                    // If deleted current channel, clear selection
                    if (currentChannelId == channelId) {
                        chatRepository.disconnectFromChannel()
                        _state.value = _state.value.copy(currentChannel = null)
                    }
                    // Refresh server to update channel list
                    selectServer(serverId)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "删除频道失败: ${e.message}")
                }
        }
    }

    fun createServer(name: String) {
        viewModelScope.launch {
            chatRepository.createServer(name)
                .onSuccess { server ->
                    // Refresh servers and select the new one
                    loadServers()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "创建服务器失败: ${e.message}")
                }
        }
    }

    fun deleteServer(serverId: Long) {
        val currentServerId = _state.value.currentServer?.id
        viewModelScope.launch {
            chatRepository.deleteServer(serverId)
                .onSuccess {
                    // If deleted current server, clear selection
                    if (currentServerId == serverId) {
                        chatRepository.disconnectFromChannel()
                        _state.value = _state.value.copy(
                            currentServer = null,
                            currentChannel = null
                        )
                    }
                    // Refresh servers list
                    loadServers()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "删除服务器失败: ${e.message}")
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopVoiceUsersPolling()
        chatRepository.disconnectFromChannel()
    }
}
