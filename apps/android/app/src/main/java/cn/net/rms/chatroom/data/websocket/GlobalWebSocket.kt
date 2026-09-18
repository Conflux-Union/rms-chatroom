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
        val hasMention: Boolean,
        val lastMentionMessageId: Long?
    ) : GlobalWebSocketEvent()
    data class ChannelAck(val channelId: Long) : GlobalWebSocketEvent()
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
                val hasMention = json.get("has_mention")?.asBoolean ?: false
                val lastMentionMessageIdElement = json.get("last_mention_message_id")
                val lastMentionMessageId = if (lastMentionMessageIdElement != null && !lastMentionMessageIdElement.isJsonNull) {
                    lastMentionMessageIdElement.asLong
                } else {
                    null
                }

                Log.d(TAG, "Read position sync: channel=$channelId, lastRead=$lastReadMessageId, hasMention=$hasMention")
                GlobalWebSocketEvent.ReadPositionSync(
                    channelId = channelId,
                    lastReadMessageId = lastReadMessageId,
                    hasMention = hasMention,
                    lastMentionMessageId = lastMentionMessageId
                )
            }
            "channel_ack" -> {
                val channelId = json.get("channel_id")?.asLong ?: return null
                Log.d(TAG, "Channel ack from another device: channel=$channelId")
                GlobalWebSocketEvent.ChannelAck(channelId)
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
     * Send read position update to server for cross-device sync.
     */
    fun sendReadPositionUpdate(
        channelId: Long,
        lastReadMessageId: Long,
        hasMention: Boolean = false,
        lastMentionMessageId: Long? = null
    ) {
        if (connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send read position update: not connected")
            return
        }

        try {
            val message = buildMap {
                put("type", "read_position_update")
                put("channel_id", channelId)
                put("last_read_message_id", lastReadMessageId)
                put("has_mention", hasMention)
                if (lastMentionMessageId != null) {
                    put("last_mention_message_id", lastMentionMessageId)
                }
            }
            val sent = webSocket?.send(gson.toJson(message)) ?: false
            if (sent) {
                Log.d(TAG, "Sent read position update: channel=$channelId, lastRead=$lastReadMessageId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending read position update", e)
        }
    }

    /**
     * Tell the user's other devices this channel was opened so they can clear
     * their unread badges. Ack state is per-device and never persisted.
     */
    fun sendChannelAck(channelId: Long) {
        if (connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send channel ack: not connected")
            return
        }

        try {
            webSocket?.send(gson.toJson(mapOf("type" to "channel_ack", "channel_id" to channelId)))
        } catch (e: Exception) {
            Log.e(TAG, "Error sending channel ack", e)
        }
    }

    fun updateVoiceChannelUsers(users: Map<Long, List<VoiceUser>>) {
        _voiceChannelUsers.value = users
    }

    companion object {
        private const val TAG = "GlobalWebSocket"
    }
}
