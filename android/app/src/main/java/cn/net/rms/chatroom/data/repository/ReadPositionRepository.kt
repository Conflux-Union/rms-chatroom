package cn.net.rms.chatroom.data.repository

import android.util.Log
import cn.net.rms.chatroom.data.api.ApiService
import cn.net.rms.chatroom.data.api.ReadPositionItem
import cn.net.rms.chatroom.data.local.SettingsPreferences
import cn.net.rms.chatroom.data.manager.MentionNotificationManager
import cn.net.rms.chatroom.data.websocket.GlobalWebSocket
import cn.net.rms.chatroom.data.websocket.GlobalWebSocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing read positions with cross-device sync.
 * 
 * - Fetches read positions from server on login
 * - Syncs local changes to server via WebSocket
 * - Listens for sync updates from other devices
 * - Uses local storage as cache, server state takes precedence
 */
@Singleton
class ReadPositionRepository @Inject constructor(
    private val api: ApiService,
    private val authRepository: AuthRepository,
    private val globalWebSocket: GlobalWebSocket,
    private val settingsPreferences: SettingsPreferences,
    private val mentionManager: MentionNotificationManager
) {
    companion object {
        private const val TAG = "ReadPositionRepository"
        private const val SYNC_DEBOUNCE_MS = 500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track pending sync operations (debounced)
    private val pendingSyncs = mutableMapOf<Long, Job>()

    // In-memory cache of read positions
    private val _readPositions = MutableStateFlow<Map<Long, ReadPositionItem>>(emptyMap())
    val readPositions: StateFlow<Map<Long, ReadPositionItem>> = _readPositions.asStateFlow()

    private var initialized = false

    init {
        observeGlobalWebSocketEvents()
    }

    private fun observeGlobalWebSocketEvents() {
        scope.launch {
            globalWebSocket.events.collect { event ->
                when (event) {
                    is GlobalWebSocketEvent.ReadPositionSync -> {
                        handleReadPositionSync(event)
                    }
                    is GlobalWebSocketEvent.Connected -> {
                        // Fetch server positions when connected
                        if (initialized) {
                            fetchServerPositions()
                        }
                    }
                    else -> { /* ignore other events */ }
                }
            }
        }
    }

    private suspend fun handleReadPositionSync(event: GlobalWebSocketEvent.ReadPositionSync) {
        Log.d(TAG, "Received read position sync: channel=${event.channelId}, lastRead=${event.lastReadMessageId}")

        // Update local cache
        val current = _readPositions.value[event.channelId]
        if (current == null || event.lastReadMessageId > current.lastReadMessageId) {
            val newPosition = ReadPositionItem(
                channelId = event.channelId,
                lastReadMessageId = event.lastReadMessageId,
                hasMention = event.hasMention,
                lastMentionMessageId = event.lastMentionMessageId
            )
            _readPositions.value = _readPositions.value + (event.channelId to newPosition)

            // Update local storage
            settingsPreferences.setLastReadMessageId(event.channelId, event.lastReadMessageId)

            // Update mention state
            if (event.hasMention && event.lastMentionMessageId != null) {
                mentionManager.markChannelAsMentioned(event.channelId, event.lastMentionMessageId)
            } else {
                mentionManager.clearChannelMention(event.channelId)
            }
        }
    }

    /**
     * Initialize repository and fetch server positions.
     * Should be called after login.
     */
    suspend fun initialize() {
        if (initialized) return
        initialized = true
        fetchServerPositions()
    }

    /**
     * Fetch all read positions from server and merge with local storage.
     */
    suspend fun fetchServerPositions(): Result<Unit> {
        return try {
            val token = authRepository.getToken() ?: return Result.failure(Exception("Not logged in"))
            val response = api.getReadPositions(authRepository.getAuthHeader(token))

            val serverPositions = response.positions.associateBy { it.channelId }
            val mergedPositions = mutableMapOf<Long, ReadPositionItem>()

            // Merge server positions with local cache
            for (pos in response.positions) {
                val local = _readPositions.value[pos.channelId]
                // Server wins if local doesn't exist or server has higher message ID
                if (local == null || pos.lastReadMessageId > local.lastReadMessageId) {
                    mergedPositions[pos.channelId] = pos
                    // Update local storage
                    settingsPreferences.setLastReadMessageId(pos.channelId, pos.lastReadMessageId)
                    // Update mention state
                    if (pos.hasMention && pos.lastMentionMessageId != null) {
                        mentionManager.markChannelAsMentioned(pos.channelId, pos.lastMentionMessageId)
                    } else {
                        mentionManager.clearChannelMention(pos.channelId)
                    }
                } else {
                    mergedPositions[pos.channelId] = local
                }
            }

            // Keep local positions that aren't on server
            for ((channelId, local) in _readPositions.value) {
                if (!mergedPositions.containsKey(channelId)) {
                    mergedPositions[channelId] = local
                }
            }

            _readPositions.value = mergedPositions
            Log.d(TAG, "Fetched ${response.positions.size} read positions from server")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch server positions", e)
            Result.failure(e)
        }
    }

    /**
     * Get last read message ID for a channel.
     */
    suspend fun getLastReadMessageId(channelId: Long): Long? {
        // First check in-memory cache
        val cached = _readPositions.value[channelId]
        if (cached != null) {
            return cached.lastReadMessageId
        }
        // Fall back to local storage
        return settingsPreferences.getLastReadMessageId(channelId)
    }

    /**
     * Save read position locally and sync to server.
     * Debounced to avoid excessive server calls.
     */
    fun saveReadPosition(
        channelId: Long,
        messageId: Long,
        hasMention: Boolean = false,
        lastMentionMessageId: Long? = null
    ) {
        val current = _readPositions.value[channelId]

        // Only update if new position is greater
        if (current != null && messageId <= current.lastReadMessageId) {
            return
        }

        // Update in-memory cache
        val newPosition = ReadPositionItem(
            channelId = channelId,
            lastReadMessageId = messageId,
            hasMention = hasMention,
            lastMentionMessageId = lastMentionMessageId
        )
        _readPositions.value = _readPositions.value + (channelId to newPosition)

        // Update local storage
        scope.launch {
            settingsPreferences.setLastReadMessageId(channelId, messageId)
        }

        // Debounce server sync
        pendingSyncs[channelId]?.cancel()
        pendingSyncs[channelId] = scope.launch {
            delay(SYNC_DEBOUNCE_MS)
            syncToServer(channelId, messageId, hasMention, lastMentionMessageId)
            pendingSyncs.remove(channelId)
        }
    }

    /**
     * Mark channel as read and sync to server.
     */
    fun markChannelAsRead(channelId: Long, messageId: Long) {
        saveReadPosition(channelId, messageId, hasMention = false, lastMentionMessageId = null)

        // Clear mention state
        scope.launch {
            mentionManager.clearChannelMention(channelId)
        }
    }

    /**
     * Mark channel as having a mention and sync to server.
     */
    fun markChannelAsMentioned(channelId: Long, messageId: Long, lastMentionMessageId: Long) {
        val current = _readPositions.value[channelId]
        val lastReadId = current?.lastReadMessageId ?: messageId

        saveReadPosition(channelId, lastReadId, hasMention = true, lastMentionMessageId = lastMentionMessageId)

        scope.launch {
            mentionManager.markChannelAsMentioned(channelId, lastMentionMessageId)
        }
    }

    private fun syncToServer(
        channelId: Long,
        messageId: Long,
        hasMention: Boolean,
        lastMentionMessageId: Long?
    ) {
        globalWebSocket.sendReadPositionUpdate(
            channelId = channelId,
            lastReadMessageId = messageId,
            hasMention = hasMention,
            lastMentionMessageId = lastMentionMessageId
        )
    }

    /**
     * Check if channel has unread messages.
     */
    suspend fun hasUnreadMessages(channelId: Long, latestMessageId: Long): Boolean {
        val lastRead = getLastReadMessageId(channelId) ?: return true
        return latestMessageId > lastRead
    }

    /**
     * Get read position for a channel.
     */
    fun getReadPosition(channelId: Long): ReadPositionItem? {
        return _readPositions.value[channelId]
    }
}
