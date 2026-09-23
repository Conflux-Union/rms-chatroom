package cn.net.rms.chatroom.data.websocket

import android.util.Log
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.data.auth.TokenAuthenticator
import cn.net.rms.chatroom.data.model.VoiceUser
import cn.net.rms.chatroom.data.monitor.NetworkMonitor
import cn.net.rms.chatroom.data.telemetry.TelemetryReporter
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

sealed class GlobalWebSocketEvent {
    data class VoiceUsersUpdate(val users: Map<Long, List<VoiceUser>>) : GlobalWebSocketEvent()
    data class ReadPositionSync(
        val channelId: Long,
        val lastReadMessageId: Long,
        val unreadCount: Int?,
        val hasMention: Boolean,
        val lastMentionMessageId: Long?
    ) : GlobalWebSocketEvent()
    /**
     * Server-derived unread state pushed with every new message. Counts are
     * absolute values computed from the user's read position.
     */
    data class UnreadUpdate(
        val channelId: Long,
        val unreadCount: Int,
        val hasMention: Boolean,
        val lastMentionMessageId: Long?
    ) : GlobalWebSocketEvent()
    object Connected : GlobalWebSocketEvent()
    object Disconnected : GlobalWebSocketEvent()
    data class Error(val error: String) : GlobalWebSocketEvent()
}

@Singleton
class GlobalWebSocket @Inject constructor(
    client: OkHttpClient,
    gson: Gson,
    tokenAuthenticator: TokenAuthenticator,
    telemetryReporter: TelemetryReporter,
    networkMonitor: NetworkMonitor
) : BaseWebSocket<GlobalWebSocketEvent>(
    client, gson, tokenAuthenticator, telemetryReporter, networkMonitor,
    tag = TAG, wsName = "global"
) {
    // Expose voice users as StateFlow for direct observation
    private val _voiceChannelUsers = MutableStateFlow<Map<Long, List<VoiceUser>>>(emptyMap())
    val voiceChannelUsers: StateFlow<Map<Long, List<VoiceUser>>> = _voiceChannelUsers.asStateFlow()

    override fun connect(token: String) {
        if (connectionState.value == ConnectionState.CONNECTED && isConnectedWith(token)) {
            Log.d(TAG, "Already connected with same token")
            return
        }
        super.connect(token)
    }

    override fun buildUrl(token: String): String {
        return "${BuildConfig.WS_BASE_URL}/ws/global?token=$token"
    }

    override fun connectedEvent(): GlobalWebSocketEvent = GlobalWebSocketEvent.Connected
    override fun disconnectedEvent(): GlobalWebSocketEvent = GlobalWebSocketEvent.Disconnected
    override fun errorEvent(message: String): GlobalWebSocketEvent = GlobalWebSocketEvent.Error(message)

    override fun handleMessage(json: JsonObject): GlobalWebSocketEvent? {
        return when (val type = json.get("type")?.asString) {
            "voice_users_update" -> {
                // Parse users: Map<channelId, List<VoiceUser>>
                val usersJson = json.getAsJsonObject("users")
                val usersMap = mutableMapOf<Long, List<VoiceUser>>()

                usersJson?.entrySet()?.forEach { (channelIdStr, usersArray) ->
                    val channelId = channelIdStr.toLongOrNull() ?: return@forEach
                    val userListType = object : TypeToken<List<VoiceUser>>() {}.type
                    val users: List<VoiceUser> = gson.fromJson(usersArray, userListType)
                    usersMap[channelId] = users
                }

                Log.d(TAG, "Voice users update: ${usersMap.size} channels")
                _voiceChannelUsers.value = usersMap
                GlobalWebSocketEvent.VoiceUsersUpdate(usersMap)
            }
            "read_position_sync" -> {
                val channelId = json.get("channel_id")?.asLong ?: return null
                val lastReadMessageId = json.get("last_read_message_id")?.asLong ?: return null
                val unreadCount = json.get("unread_count")?.takeIf { !it.isJsonNull }?.asInt
                val hasMention = json.get("has_mention")?.asBoolean ?: false
                val lastMentionMessageIdElement = json.get("last_mention_message_id")
                val lastMentionMessageId = if (lastMentionMessageIdElement != null && !lastMentionMessageIdElement.isJsonNull) {
                    lastMentionMessageIdElement.asLong
                } else {
                    null
                }

                Log.d(TAG, "Read position sync: channel=$channelId, lastRead=$lastReadMessageId, unread=$unreadCount, hasMention=$hasMention")
                GlobalWebSocketEvent.ReadPositionSync(
                    channelId = channelId,
                    lastReadMessageId = lastReadMessageId,
                    unreadCount = unreadCount,
                    hasMention = hasMention,
                    lastMentionMessageId = lastMentionMessageId
                )
            }
            "unread_update" -> {
                val channelId = json.get("channel_id")?.asLong ?: return null
                val unreadCount = json.get("unread_count")?.asInt ?: return null
                val hasMention = json.get("has_mention")?.asBoolean ?: false
                val lastMentionMessageIdElement = json.get("last_mention_message_id")
                val lastMentionMessageId = if (lastMentionMessageIdElement != null && !lastMentionMessageIdElement.isJsonNull) {
                    lastMentionMessageIdElement.asLong
                } else {
                    null
                }

                Log.d(TAG, "Unread update: channel=$channelId, unread=$unreadCount, hasMention=$hasMention")
                GlobalWebSocketEvent.UnreadUpdate(
                    channelId = channelId,
                    unreadCount = unreadCount,
                    hasMention = hasMention,
                    lastMentionMessageId = lastMentionMessageId
                )
            }
            "connected" -> {
                Log.d(TAG, "Global WebSocket server confirmed connection")
                null
            }
            else -> {
                Log.d(TAG, "Unknown global message type: $type")
                null
            }
        }
    }

    /**
     * Send read position update to server for cross-device sync. Unread counts
     * and mention flags are server-derived from the resulting position.
     */
    fun sendReadPositionUpdate(channelId: Long, lastReadMessageId: Long) {
        if (connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send read position update: not connected")
            return
        }

        try {
            val message = mapOf(
                "type" to "read_position_update",
                "channel_id" to channelId,
                "last_read_message_id" to lastReadMessageId
            )
            val sent = webSocket?.send(gson.toJson(message)) ?: false
            if (sent) {
                Log.d(TAG, "Sent read position update: channel=$channelId, lastRead=$lastReadMessageId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending read position update", e)
        }
    }

    fun updateVoiceChannelUsers(users: Map<Long, List<VoiceUser>>) {
        _voiceChannelUsers.value = users
    }

    companion object {
        private const val TAG = "GlobalWebSocket"
    }
}
