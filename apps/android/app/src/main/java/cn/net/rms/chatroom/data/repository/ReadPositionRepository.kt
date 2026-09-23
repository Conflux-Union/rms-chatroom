package cn.net.rms.chatroom.data.repository

import android.util.Log
import cn.net.rms.chatroom.data.api.ApiService
import cn.net.rms.chatroom.data.api.ReadPositionItem
import cn.net.rms.chatroom.data.local.SettingsPreferences
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
 * Repository for read positions with cross-device sync.
 *
 * - Fetches read positions from the server on login and reconnect
 * - Syncs local viewport advancement to the server via WebSocket
 * - Applies server-derived unread counts / mention flags pushed live
 *
 * Unread state is never computed locally: the server derives it from the read
 * position and pushes absolute values with every new message.
 */
@Singleton
class ReadPositionRepository @Inject constructor(
    private val api: ApiService,
    private val authRepository: AuthRepository,
    private val globalWebSocket: GlobalWebSocket,
    private val settingsPreferences: SettingsPreferences
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

    // Server-derived unread badge counts per channel
    private val _unreadCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<Long, Int>> = _unreadCounts.asStateFlow()

    // Server-derived @ badges: channels with an unpassed mention, and the
    // message id of that mention (for optimistic clearing on read-through).
    private val _mentionChannels = MutableStateFlow<Set<Long>>(emptySet())
    val mentionChannels: StateFlow<Set<Long>> = _mentionChannels.asStateFlow()
    private val lastMentionIds = mutableMapOf<Long, Long>()

    private var initialized = false

    init {
        observeGlobalWebSocketEvents()
    }

    private fun observeGlobalWebSocketEvents() {
        scope.launch {
            globalWebSocket.events.collect { event ->
                when (event) {
                    is GlobalWebSocketEvent.ReadPositionSync -> handleReadPositionSync(event)
                    is GlobalWebSocketEvent.UnreadUpdate -> {
                        applyServerUnread(event.channelId, event.unreadCount, event.hasMention, event.lastMentionMessageId)
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

    /**
     * Single writer for server-derived badge state: unread count plus mention
     * flag, both computed by the server from the read position.
     */
    private fun applyServerUnread(channelId: Long, unreadCount: Int, hasMention: Boolean, lastMentionMessageId: Long?) {
        _unreadCounts.value = _unreadCounts.value + (channelId to unreadCount)

        if (hasMention && lastMentionMessageId != null) {
            lastMentionIds[channelId] = lastMentionMessageId
            _mentionChannels.value = _mentionChannels.value + channelId
        } else {
            lastMentionIds.remove(channelId)
            _mentionChannels.value = _mentionChannels.value - channelId
        }
    }

    private suspend fun handleReadPositionSync(event: GlobalWebSocketEvent.ReadPositionSync) {
        Log.d(TAG, "Received read position sync: channel=${event.channelId}, lastRead=${event.lastReadMessageId}")

        event.unreadCount?.let {
            applyServerUnread(event.channelId, it, event.hasMention, event.lastMentionMessageId)
        }

        // Update read position if server has newer data
        val current = _readPositions.value[event.channelId]
        if (current == null || event.lastReadMessageId > current.lastReadMessageId) {
            val newPosition = ReadPositionItem(
                channelId = event.channelId,
                lastReadMessageId = event.lastReadMessageId,
                unreadCount = event.unreadCount ?: 0,
                hasMention = event.hasMention,
                lastMentionMessageId = event.lastMentionMessageId
            )
            _readPositions.value = _readPositions.value + (event.channelId to newPosition)
            settingsPreferences.setLastReadMessageId(event.channelId, event.lastReadMessageId)
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
     * Fetch all read positions from the server and merge with local storage.
     */
    suspend fun fetchServerPositions(): Result<Unit> {
        return try {
            val token = authRepository.getToken() ?: return Result.failure(Exception("Not logged in"))
            val response = api.getReadPositions(authRepository.getAuthHeader(token))

            // Badge state is server-authoritative: apply what the fetch saw.
            for (pos in response.positions) {
                applyServerUnread(pos.channelId, pos.unreadCount, pos.hasMention, pos.lastMentionMessageId)
            }

            val mergedPositions = mutableMapOf<Long, ReadPositionItem>()

            // Merge server positions with local cache; server wins for read
            // position if local doesn't exist or server has higher message ID
            for (pos in response.positions) {
                val local = _readPositions.value[pos.channelId]
                if (local == null || pos.lastReadMessageId > local.lastReadMessageId) {
                    mergedPositions[pos.channelId] = pos
                    settingsPreferences.setLastReadMessageId(pos.channelId, pos.lastReadMessageId)
                } else {
                    // Keep local read position but use server's badge state
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
            Log.e(TAG, "Failed to fetch read positions", e)
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
     * Record that the viewport has reached a message, and sync to the server
     * (debounced). The server re-derives the unread count and mention flag
     * from the new position; the local @ badge clears optimistically once the
     * viewport passes the mention message.
     */
    fun saveReadPosition(channelId: Long, messageId: Long) {
        val current = _readPositions.value[channelId]

        // Only update if new position is greater
        if (current != null && messageId <= current.lastReadMessageId) {
            return
        }

        // Update in-memory cache
        val newPosition = ReadPositionItem(
            channelId = channelId,
            lastReadMessageId = messageId,
            unreadCount = current?.unreadCount ?: 0,
            hasMention = current?.hasMention ?: false,
            lastMentionMessageId = current?.lastMentionMessageId
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
            globalWebSocket.sendReadPositionUpdate(channelId, messageId)

            // Optimistic: the viewport passed the mention, so the @ badge has
            // been seen. The server's own derivation arrives with the sync.
            val mentionId = lastMentionIds[channelId]
            if (mentionId != null && messageId >= mentionId) {
                lastMentionIds.remove(channelId)
                _mentionChannels.value = _mentionChannels.value - channelId
            }
            pendingSyncs.remove(channelId)
        }
    }

    /**
     * Get read position for a channel.
     */
    fun getReadPosition(channelId: Long): ReadPositionItem? {
        return _readPositions.value[channelId]
    }
}
