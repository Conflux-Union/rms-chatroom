package cn.net.rms.chatroom.data.repository

import android.util.Log
import android.content.Context
import android.net.Uri
import cn.net.rms.chatroom.data.api.AddReactionRequest
import cn.net.rms.chatroom.data.api.ApiService
import cn.net.rms.chatroom.data.api.ChannelMember
import cn.net.rms.chatroom.data.api.CreateChannelRequest
import cn.net.rms.chatroom.data.api.CreateServerRequest
import cn.net.rms.chatroom.data.api.SendMessageBody
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import cn.net.rms.chatroom.data.local.MessageDao
import cn.net.rms.chatroom.data.local.MessageEntity
import cn.net.rms.chatroom.data.local.SettingsPreferences
import cn.net.rms.chatroom.data.model.Channel
import cn.net.rms.chatroom.data.model.ChannelType
import cn.net.rms.chatroom.data.model.Message
import cn.net.rms.chatroom.data.model.ReactionGroup
import cn.net.rms.chatroom.data.model.ReactionUser
import cn.net.rms.chatroom.data.model.Server
import cn.net.rms.chatroom.data.model.VoiceUser
import cn.net.rms.chatroom.data.model.AttachmentResponse
import cn.net.rms.chatroom.data.websocket.ChatWebSocket
import cn.net.rms.chatroom.data.websocket.ConnectionState
import cn.net.rms.chatroom.data.websocket.WebSocketEvent
import cn.net.rms.chatroom.notification.NotificationHelper
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
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val authRepository: AuthRepository,
    private val webSocket: ChatWebSocket,
    private val messageDao: MessageDao,
    private val notificationHelper: NotificationHelper,
    private val settingsPreferences: SettingsPreferences
) {
    companion object {
        private const val TAG = "ChatRepository"
        private const val CACHE_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    }

    // Track if app is in foreground (set by Activity)
    var isAppInForeground: Boolean = true

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _servers = MutableStateFlow<List<Server>>(emptyList())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _currentServer = MutableStateFlow<Server?>(null)
    val currentServer: StateFlow<Server?> = _currentServer.asStateFlow()

    private val _currentChannel = MutableStateFlow<Channel?>(null)
    val currentChannel: StateFlow<Channel?> = _currentChannel.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // Voice channel users: Map<channelId, List<VoiceUser>>
    private val _voiceChannelUsers = MutableStateFlow<Map<Long, List<VoiceUser>>>(emptyMap())
    val voiceChannelUsers: StateFlow<Map<Long, List<VoiceUser>>> = _voiceChannelUsers.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = webSocket.connectionState
    val webSocketEvents = webSocket.events

    init {
        observeWebSocketEvents()
        cleanupOldCache()
    }

    private fun observeWebSocketEvents() {
        scope.launch {
            webSocket.events.collect { event ->
                when (event) {
                    is WebSocketEvent.NewMessage -> {
                        Log.d(TAG, "Received new message: ${event.message.id}")
                        addMessage(event.message)
                        cacheMessage(event.message)
                        // Show notification if app is in background
                        showMessageNotificationIfNeeded(event.message)
                    }
                    is WebSocketEvent.MessageDeleted -> {
                        Log.d(TAG, "Message deleted: ${event.messageId}")
                        updateMessageDeleted(event.messageId, event.deletedBy, event.deletedByUsername)
                    }
                    is WebSocketEvent.MessageEdited -> {
                        Log.d(TAG, "Message edited: ${event.messageId}")
                        updateMessageEdited(event.messageId, event.content, event.editedAt)
                    }
                    is WebSocketEvent.ReactionAdded -> {
                        Log.d(TAG, "Reaction added: ${event.emoji} to message ${event.messageId}")
                        updateMessageReactionAdded(event.messageId, event.emoji, event.userId, event.username)
                    }
                    is WebSocketEvent.ReactionRemoved -> {
                        Log.d(TAG, "Reaction removed: ${event.emoji} from message ${event.messageId}")
                        updateMessageReactionRemoved(event.messageId, event.emoji, event.userId)
                    }
                    is WebSocketEvent.Connected -> {
                        Log.d(TAG, "WebSocket connected to channel ${event.channelId}")
                    }
                    is WebSocketEvent.Disconnected -> {
                        Log.d(TAG, "WebSocket disconnected")
                    }
                    is WebSocketEvent.Error -> {
                        Log.e(TAG, "WebSocket error: ${event.error}, code: ${event.code}")
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

    private fun cleanupOldCache() {
        scope.launch {
            try {
                val expiryTime = System.currentTimeMillis() - CACHE_EXPIRY_MS
                messageDao.deleteOldMessages(expiryTime)
                Log.d(TAG, "Cleaned up old cached messages")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup old cache", e)
            }
        }
    }

    private fun cacheMessage(message: Message) {
        scope.launch {
            try {
                messageDao.insertMessage(MessageEntity.fromMessage(message))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache message", e)
            }
        }
    }

    private fun showMessageNotificationIfNeeded(message: Message) {
        Log.d(TAG, "showMessageNotificationIfNeeded: isAppInForeground=$isAppInForeground")
        
        // Only show notification when app is in background
        if (isAppInForeground) {
            Log.d(TAG, "App in foreground, skipping notification")
            return
        }

        val channel = _currentChannel.value
        val server = _currentServer.value
        
        val channelName = channel?.name ?: "未知频道"
        val serverName = server?.name ?: "RMS ChatRoom"

        Log.d(TAG, "Showing notification for message from ${message.username}")
        notificationHelper.showMessageNotification(
            message = message,
            channelName = channelName,
            serverName = serverName
        )
    }

    fun cancelNotifications() {
        notificationHelper.cancelAllMessageNotifications()
    }

    private fun cacheMessages(messages: List<Message>) {
        scope.launch {
            try {
                messageDao.insertMessages(messages.map { MessageEntity.fromMessage(it) })
                Log.d(TAG, "Cached ${messages.size} messages")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache messages", e)
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
        // First load from cache for instant display
        loadCachedMessages(channelId)

        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val messageList = api.getMessages(authRepository.getAuthHeader(token), channelId)
            _messages.value = messageList
            // Update cache
            cacheMessages(messageList)
            Result.success(messageList)
        } catch (e: Exception) {
            Log.e(TAG, "fetchMessages failed", e)
            // If network fails, cached messages are already displayed
            if (_messages.value.isEmpty()) {
                Result.failure(e.toAuthException())
            } else {
                Log.d(TAG, "Using cached messages due to network error")
                Result.success(_messages.value)
            }
        }
    }

    private suspend fun loadCachedMessages(channelId: Long) {
        try {
            val cached = messageDao.getMessagesByChannelOnce(channelId)
            if (cached.isNotEmpty()) {
                _messages.value = cached.map { it.toMessage() }
                Log.d(TAG, "Loaded ${cached.size} cached messages")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached messages", e)
        }
    }

    suspend fun sendMessage(channelId: Long, content: String, attachmentIds: List<Long> = emptyList(), replyToId: Long? = null): Result<Message> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val message = api.sendMessage(
                authRepository.getAuthHeader(token),
                channelId,
                SendMessageBody(content, attachmentIds, replyToId)
            )
            // Check for duplicate before adding (WebSocket may have already added this message)
            if (_messages.value.none { it.id == message.id }) {
                _messages.value = _messages.value + message
            }
            // Cache sent message
            cacheMessage(message)
            Result.success(message)
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun uploadFile(channelId: Long, uri: Uri): Result<AttachmentResponse> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))

            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val fileName = getFileName(uri) ?: "file"

            val inputStream = contentResolver.openInputStream(uri)
                ?: return Result.failure(Exception("无法读取文件"))

            val bytes = inputStream.use { it.readBytes() }
            val requestBody = bytes.toRequestBody(mimeType.toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", fileName, requestBody)

            val response = api.uploadFile(authRepository.getAuthHeader(token), channelId, part)
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "uploadFile failed", e)
            Result.failure(e.toAuthException())
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path?.substringAfterLast('/')
        }
        return name
    }

    fun addMessage(message: Message) {
        if (message.channelId == _currentChannel.value?.id) {
            if (_messages.value.none { it.id == message.id }) {
                _messages.value = _messages.value + message
            }
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

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

    suspend fun fetchVoiceChannelUsers(channelId: Long): Result<List<VoiceUser>> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val users = api.getVoiceUsers(authRepository.getAuthHeader(token), channelId)
            _voiceChannelUsers.value = _voiceChannelUsers.value.toMutableMap().apply {
                put(channelId, users)
            }
            Result.success(users)
        } catch (e: Exception) {
            Log.e(TAG, "fetchVoiceChannelUsers failed for channel $channelId", e)
            _voiceChannelUsers.value = _voiceChannelUsers.value.toMutableMap().apply {
                put(channelId, emptyList())
            }
            Result.failure(e.toAuthException())
        }
    }

    suspend fun fetchAllVoiceChannelUsers() {
        val server = _currentServer.value ?: return
        try {
            val token = authRepository.getToken() ?: return
            val response = api.getAllVoiceUsers(authRepository.getAuthHeader(token))
            val newMap = mutableMapOf<Long, List<VoiceUser>>()
            response.users.forEach { (channelId, users) ->
                newMap[channelId] = users
            }
            // Set empty list for voice channels not in response
            val voiceChannels = server.channels?.filter { it.type == ChannelType.VOICE } ?: emptyList()
            voiceChannels.forEach { channel ->
                if (!newMap.containsKey(channel.id)) {
                    newMap[channel.id] = emptyList()
                }
            }
            _voiceChannelUsers.value = newMap
        } catch (e: Exception) {
            Log.e(TAG, "fetchAllVoiceChannelUsers failed", e)
            // Fallback: clear all voice channel users
            val voiceChannels = server.channels?.filter { it.type == ChannelType.VOICE } ?: emptyList()
            _voiceChannelUsers.value = voiceChannels.associate { it.id to emptyList() }
        }
    }

    fun getVoiceChannelUsers(channelId: Long): List<VoiceUser> {
        return _voiceChannelUsers.value[channelId] ?: emptyList()
    }

    suspend fun createChannel(serverId: Long, name: String, type: String): Result<Channel> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val channel = api.createChannel(
                authRepository.getAuthHeader(token),
                serverId,
                CreateChannelRequest(name, type)
            )
            Result.success(channel)
        } catch (e: Exception) {
            Log.e(TAG, "createChannel failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun deleteChannel(serverId: Long, channelId: Long): Result<Unit> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            api.deleteChannel(authRepository.getAuthHeader(token), serverId, channelId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteChannel failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun createServer(name: String): Result<Server> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val server = api.createServer(
                authRepository.getAuthHeader(token),
                CreateServerRequest(name)
            )
            Result.success(server)
        } catch (e: Exception) {
            Log.e(TAG, "createServer failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun deleteServer(serverId: Long): Result<Unit> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            api.deleteServer(authRepository.getAuthHeader(token), serverId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteServer failed", e)
            Result.failure(e.toAuthException())
        }
    }

    // Message management methods
    suspend fun editMessage(channelId: Long, messageId: Long, content: String): Result<Message> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val message = api.editMessage(
                authRepository.getAuthHeader(token),
                channelId,
                messageId,
                cn.net.rms.chatroom.data.model.MessageEditRequest(content)
            )
            updateMessageEdited(messageId, content, message.editedAt ?: "")
            Result.success(message)
        } catch (e: Exception) {
            Log.e(TAG, "editMessage failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun deleteMessage(channelId: Long, messageId: Long): Result<Unit> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            api.deleteMessage(authRepository.getAuthHeader(token), channelId, messageId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteMessage failed", e)
            Result.failure(e.toAuthException())
        }
    }

    private fun updateMessageDeleted(messageId: Long, deletedBy: Long, deletedByUsername: String) {
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) {
                message.copy(
                    isDeleted = true,
                    deletedBy = deletedBy,
                    deletedByUsername = deletedByUsername
                )
            } else {
                message
            }
        }
    }

    private fun updateMessageEdited(messageId: Long, content: String, editedAt: String) {
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) {
                message.copy(
                    content = content,
                    editedAt = editedAt
                )
            } else {
                message
            }
        }
    }

    // Mute management methods
    suspend fun createMute(
        userId: Long,
        scope: String,
        mutedUntil: String?,
        serverId: Long?,
        channelId: Long?,
        reason: String?
    ): Result<cn.net.rms.chatroom.data.model.MuteResponse> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val response = api.createMute(
                authRepository.getAuthHeader(token),
                cn.net.rms.chatroom.data.model.MuteCreateRequest(
                    userId = userId,
                    scope = scope,
                    serverId = serverId,
                    channelId = channelId,
                    mutedUntil = mutedUntil,
                    reason = reason
                )
            )
            Result.success(response)
        } catch (e: Exception) {
            Log.e(TAG, "createMute failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun getUserMutes(userId: Long): Result<List<cn.net.rms.chatroom.data.model.MuteRecord>> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val mutes = api.getUserMutes(authRepository.getAuthHeader(token), userId)
            Result.success(mutes)
        } catch (e: Exception) {
            Log.e(TAG, "getUserMutes failed", e)
            Result.failure(e.toAuthException())
        }
    }

    // Read position tracking
    suspend fun getLastReadMessageId(channelId: Long): Long? {
        return settingsPreferences.getLastReadMessageId(channelId)
    }

    suspend fun setLastReadMessageId(channelId: Long, messageId: Long) {
        settingsPreferences.setLastReadMessageId(channelId, messageId)
    }

    // Reaction methods
    suspend fun addReaction(messageId: Long, emoji: String): Result<Unit> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            api.addReaction(authRepository.getAuthHeader(token), messageId, AddReactionRequest(emoji))
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "addReaction failed", e)
            Result.failure(e.toAuthException())
        }
    }

    suspend fun removeReaction(messageId: Long, emoji: String): Result<Unit> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            api.removeReaction(authRepository.getAuthHeader(token), messageId, emoji)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "removeReaction failed", e)
            Result.failure(e.toAuthException())
        }
    }

    private fun updateMessageReactionAdded(messageId: Long, emoji: String, userId: Long, username: String) {
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) {
                val currentReactions = message.reactions?.toMutableList() ?: mutableListOf()
                val existingGroup = currentReactions.find { it.emoji == emoji }
                
                if (existingGroup != null) {
                    // Check if user already reacted (prevent duplicates from WebSocket retries)
                    if (existingGroup.users.any { it.id == userId }) {
                        return@map message
                    }
                    // Add user to existing reaction group
                    val updatedUsers = existingGroup.users + ReactionUser(userId, username)
                    val updatedGroup = existingGroup.copy(
                        count = existingGroup.count + 1,
                        users = updatedUsers
                    )
                    currentReactions[currentReactions.indexOf(existingGroup)] = updatedGroup
                } else {
                    // Create new reaction group
                    currentReactions.add(ReactionGroup(
                        emoji = emoji,
                        count = 1,
                        users = listOf(ReactionUser(userId, username)),
                        reacted = false
                    ))
                }
                
                message.copy(reactions = currentReactions)
            } else {
                message
            }
        }
    }

    private fun updateMessageReactionRemoved(messageId: Long, emoji: String, userId: Long) {
        _messages.value = _messages.value.map { message ->
            if (message.id == messageId) {
                val currentReactions = message.reactions?.toMutableList() ?: mutableListOf()
                val existingGroup = currentReactions.find { it.emoji == emoji }
                
                if (existingGroup != null) {
                    val updatedUsers = existingGroup.users.filter { it.id != userId }
                    if (updatedUsers.isEmpty()) {
                        // Remove the reaction group entirely
                        currentReactions.remove(existingGroup)
                    } else {
                        // Update the reaction group
                        val updatedGroup = existingGroup.copy(
                            count = updatedUsers.size,
                            users = updatedUsers
                        )
                        currentReactions[currentReactions.indexOf(existingGroup)] = updatedGroup
                    }
                }
                
                message.copy(reactions = currentReactions.ifEmpty { null })
            } else {
                message
            }
        }
    }

    // Channel members for @mention autocomplete
    suspend fun getChannelMembers(channelId: Long): Result<List<ChannelMember>> {
        return try {
            val token = authRepository.getToken()
                ?: return Result.failure(AuthException("未登录，请先登录"))
            val members = api.getChannelMembers(authRepository.getAuthHeader(token), channelId)
            Result.success(members)
        } catch (e: Exception) {
            Log.e(TAG, "getChannelMembers failed", e)
            Result.failure(e.toAuthException())
        }
    }

    // Find message by ID (for reply scroll)
    fun findMessageIndex(messageId: Long): Int {
        return _messages.value.indexOfFirst { it.id == messageId }
    }
}
