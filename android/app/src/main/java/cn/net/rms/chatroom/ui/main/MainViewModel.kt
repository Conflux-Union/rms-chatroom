package cn.net.rms.chatroom.ui.main

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.net.rms.chatroom.data.api.ChannelMember
import cn.net.rms.chatroom.data.api.ReorderTopLevelItem
import cn.net.rms.chatroom.data.model.Channel
import cn.net.rms.chatroom.data.model.ChannelGroup
import cn.net.rms.chatroom.data.model.ChannelType
import cn.net.rms.chatroom.data.model.Server
import cn.net.rms.chatroom.data.model.VoiceUser
import cn.net.rms.chatroom.data.api.AppUpdateResponse
import cn.net.rms.chatroom.data.model.AttachmentResponse
import cn.net.rms.chatroom.data.repository.BugReportRepository
import cn.net.rms.chatroom.data.repository.ChatRepository
import cn.net.rms.chatroom.data.repository.UpdateRepository
import cn.net.rms.chatroom.data.repository.VoiceRepository
import cn.net.rms.chatroom.data.websocket.ConnectionState
import cn.net.rms.chatroom.data.websocket.WebSocketEvent
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
    val channelGroups: List<ChannelGroup> = emptyList(),
    val editMode: Boolean = false,
    val error: String? = null,
    val bugReportSubmitting: Boolean = false,
    val bugReportId: String? = null,
    val updateInfo: AppUpdateResponse? = null,
    val isDownloading: Boolean = false,
    val downloadComplete: Boolean = false,
    val lastReadMessageId: Long? = null,
    val showContinueReading: Boolean = false,
    val channelMembers: List<ChannelMember> = emptyList()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val bugReportRepository: BugReportRepository,
    private val updateRepository: UpdateRepository,
    private val voiceRepository: VoiceRepository
) : ViewModel() {
    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    val messages = chatRepository.messages
    val connectionState: StateFlow<ConnectionState> = chatRepository.connectionState
    val voiceChannelUsers: StateFlow<Map<Long, List<VoiceUser>>> = chatRepository.voiceChannelUsers

    // Voice status for sidebar widget
    val isVoiceConnected: StateFlow<Boolean> = voiceRepository.isConnected
    val voiceChannelName: StateFlow<String?> = voiceRepository.currentChannelName
    val isVoiceMuted: StateFlow<Boolean> = voiceRepository.isMuted

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
                    // Fetch channel groups
                    fetchChannelGroups(serverId)
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

    private fun fetchChannelGroups(serverId: Long) {
        viewModelScope.launch {
            chatRepository.fetchChannelGroups(serverId)
                .onSuccess { groups ->
                    _state.value = _state.value.copy(channelGroups = groups)
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to fetch channel groups: ${e.message}")
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
        _state.value = _state.value.copy(
            currentChannel = channel,
            lastReadMessageId = null,
            showContinueReading = false
        )

        // Load messages for text channels
        if (channel.type == ChannelType.TEXT) {
            loadMessages(channel.id)
            // Connect WebSocket for real-time messages
            chatRepository.connectToChannel(channel.id)
            // Load last read position
            loadLastReadPosition(channel.id)
        }
    }

    private fun loadLastReadPosition(channelId: Long) {
        viewModelScope.launch {
            val lastReadId = chatRepository.getLastReadMessageId(channelId)
            if (lastReadId != null) {
                _state.value = _state.value.copy(
                    lastReadMessageId = lastReadId,
                    showContinueReading = true
                )
            }
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

    fun sendMessage(content: String, attachmentIds: List<Long> = emptyList(), replyToId: Long? = null) {
        val channelId = _state.value.currentChannel?.id ?: return
        viewModelScope.launch {
            chatRepository.sendMessage(channelId, content, attachmentIds, replyToId)
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "发送失败: ${e.message}")
                }
        }
    }

    suspend fun uploadFile(uri: Uri): Result<AttachmentResponse> {
        val channelId = _state.value.currentChannel?.id
            ?: return Result.failure(Exception("未选择频道"))
        return chatRepository.uploadFile(channelId, uri)
    }

    fun reconnectWebSocket() {
        chatRepository.reconnectWebSocket()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun saveReadPosition(messageId: Long) {
        val channelId = _state.value.currentChannel?.id ?: return
        viewModelScope.launch {
            chatRepository.setLastReadMessageId(channelId, messageId)
        }
    }

    fun dismissContinueReading() {
        _state.value = _state.value.copy(showContinueReading = false)
    }

    fun getMessageIndexById(messageId: Long): Int {
        return messages.value.indexOfFirst { it.id == messageId }
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

    // Channel edit mode and reordering
    fun toggleEditMode() {
        _state.value = _state.value.copy(editMode = !_state.value.editMode)
    }

    fun setEditMode(enabled: Boolean) {
        _state.value = _state.value.copy(editMode = enabled)
    }

    fun reorderTopLevel(items: List<ReorderTopLevelItem>) {
        val serverId = _state.value.currentServer?.id ?: return
        viewModelScope.launch {
            chatRepository.reorderTopLevel(serverId, items)
                .onSuccess {
                    // Refresh server to get updated positions
                    selectServer(serverId)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "排序失败: ${e.message}")
                }
        }
    }

    fun reorderGroupChannels(groupId: Long, channelIds: List<Long>) {
        val serverId = _state.value.currentServer?.id ?: return
        viewModelScope.launch {
            chatRepository.reorderGroupChannels(serverId, groupId, channelIds)
                .onSuccess {
                    // Refresh server to get updated positions
                    selectServer(serverId)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "排序失败: ${e.message}")
                }
        }
    }

    fun createChannelGroup(name: String) {
        val serverId = _state.value.currentServer?.id ?: return
        viewModelScope.launch {
            chatRepository.createChannelGroup(serverId, name)
                .onSuccess {
                    // Refresh server to update channel groups
                    selectServer(serverId)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "创建分组失败: ${e.message}")
                }
        }
    }

    fun deleteChannelGroup(groupId: Long) {
        val serverId = _state.value.currentServer?.id ?: return
        viewModelScope.launch {
            chatRepository.deleteChannelGroup(serverId, groupId)
                .onSuccess {
                    // Refresh server to update channel groups
                    selectServer(serverId)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "删除分组失败: ${e.message}")
                }
        }
    }

    // Message management methods
    fun editMessage(messageId: Long, content: String) {
        val channelId = _state.value.currentChannel?.id ?: return
        viewModelScope.launch {
            chatRepository.editMessage(channelId, messageId, content)
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "编辑失败: ${e.message}")
                }
        }
    }

    fun deleteMessage(messageId: Long) {
        val channelId = _state.value.currentChannel?.id ?: return
        viewModelScope.launch {
            chatRepository.deleteMessage(channelId, messageId)
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "撤回失败: ${e.message}")
                }
        }
    }

    fun muteUser(
        userId: Long,
        scope: String,
        mutedUntil: String?,
        serverId: Long?,
        channelId: Long?,
        reason: String?
    ) {
        viewModelScope.launch {
            chatRepository.createMute(userId, scope, mutedUntil, serverId, channelId, reason)
                .onSuccess {
                    _state.value = _state.value.copy(error = "禁言成功")
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "禁言失败: ${e.message}")
                }
        }
    }

    // Voice control methods for sidebar widget
    fun toggleVoiceMute() {
        val newMuted = !voiceRepository.isMuted.value
        voiceRepository.setMuted(newMuted)
    }

    fun disconnectVoice() {
        voiceRepository.leaveVoice()
    }

    // Reaction methods
    fun addReaction(messageId: Long, emoji: String) {
        viewModelScope.launch {
            chatRepository.addReaction(messageId, emoji)
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "添加表情失败: ${e.message}")
                }
        }
    }

    fun removeReaction(messageId: Long, emoji: String) {
        viewModelScope.launch {
            chatRepository.removeReaction(messageId, emoji)
                .onFailure { e ->
                    _state.value = _state.value.copy(error = "移除表情失败: ${e.message}")
                }
        }
    }

    // Channel members for @mention autocomplete
    fun fetchChannelMembers() {
        val channelId = _state.value.currentChannel?.id ?: return
        viewModelScope.launch {
            chatRepository.getChannelMembers(channelId)
                .onSuccess { members ->
                    _state.value = _state.value.copy(channelMembers = members)
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to fetch channel members: ${e.message}")
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopVoiceUsersPolling()
        chatRepository.disconnectFromChannel()
    }
}
