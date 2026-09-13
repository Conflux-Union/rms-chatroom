package cn.net.rms.chatroom.data.websocket

import android.util.Log
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.data.auth.TokenAuthenticator
import cn.net.rms.chatroom.data.model.Attachment
import cn.net.rms.chatroom.data.model.Message
import cn.net.rms.chatroom.data.model.Mention
import cn.net.rms.chatroom.data.model.ReplyTo
import cn.net.rms.chatroom.data.model.ReactionGroup
import cn.net.rms.chatroom.data.model.VoiceUser
import cn.net.rms.chatroom.data.monitor.NetworkMonitor
import cn.net.rms.chatroom.data.telemetry.TelemetryReporter
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

sealed class WebSocketEvent {
    data class NewMessage(val message: Message) : WebSocketEvent()
    data class UserJoined(val user: VoiceUser) : WebSocketEvent()
    data class UserLeft(val userId: Long) : WebSocketEvent()
    data class Connected(val channelId: Long) : WebSocketEvent()
    object Disconnected : WebSocketEvent()
    data class Error(val error: String, val code: String? = null) : WebSocketEvent()
    data class MessageDeleted(val messageId: Long, val deletedBy: Long, val deletedByUsername: String) : WebSocketEvent()
    data class MessageEdited(val messageId: Long, val content: String, val editedAt: String) : WebSocketEvent()
    // Reaction events
    data class ReactionAdded(val messageId: Long, val emoji: String, val userId: Long, val username: String) : WebSocketEvent()
    data class ReactionRemoved(val messageId: Long, val emoji: String, val userId: Long) : WebSocketEvent()
}

@Singleton
class ChatWebSocket @Inject constructor(
    client: OkHttpClient,
    gson: Gson,
    tokenAuthenticator: TokenAuthenticator,
    telemetryReporter: TelemetryReporter,
    networkMonitor: NetworkMonitor
) : BaseWebSocket<WebSocketEvent>(
    client, gson, tokenAuthenticator, telemetryReporter, networkMonitor,
    tag = TAG, wsName = "chat"
) {
    override fun buildUrl(token: String): String {
        return "${BuildConfig.WS_BASE_URL}/ws/chat?token=$token"
    }

    override fun connectedEvent(): WebSocketEvent = WebSocketEvent.Connected(0) // global connection, no channel
    override fun disconnectedEvent(): WebSocketEvent = WebSocketEvent.Disconnected
    override fun errorEvent(message: String): WebSocketEvent = WebSocketEvent.Error(message)

    override fun onParseError(e: Exception) {
        emitEvent(errorEvent("Failed to parse message: ${e.message}"))
    }

    override fun handleMessage(json: JsonObject): WebSocketEvent? {
        return when (val type = json.get("type")?.asString) {
            "message" -> {
                // Parse attachments if present
                val attachments = if (json.has("attachments") && !json.get("attachments").isJsonNull) {
                    val attachmentsType = object : TypeToken<List<Attachment>>() {}.type
                    gson.fromJson<List<Attachment>>(json.get("attachments"), attachmentsType)
                } else {
                    null
                }

                // Parse reply_to if present
                val replyTo = if (json.has("reply_to") && !json.get("reply_to").isJsonNull) {
                    val replyToJson = json.getAsJsonObject("reply_to")
                    ReplyTo(
                        id = replyToJson.get("id").asLong,
                        userId = replyToJson.get("user_id")?.asLong ?: 0L,
                        username = replyToJson.get("username")?.asString ?: "",
                        content = replyToJson.get("content")?.asString ?: ""
                    )
                } else {
                    null
                }

                // Parse mentions if present
                val mentions = if (json.has("mentions") && !json.get("mentions").isJsonNull) {
                    val mentionsType = object : TypeToken<List<Mention>>() {}.type
                    gson.fromJson<List<Mention>>(json.get("mentions"), mentionsType)
                } else {
                    null
                }

                // Parse reactions if present
                val reactions = if (json.has("reactions") && !json.get("reactions").isJsonNull) {
                    val reactionsType = object : TypeToken<List<ReactionGroup>>() {}.type
                    gson.fromJson<List<ReactionGroup>>(json.get("reactions"), reactionsType)
                } else {
                    null
                }

                // Parse avatar_url if present
                val avatarUrl = if (json.has("avatar_url") && !json.get("avatar_url").isJsonNull) {
                    json.get("avatar_url").asString
                } else {
                    null
                }

                val message = Message(
                    id = json.get("id").asLong,
                    channelId = json.get("channel_id")?.asLong ?: 0L,
                    userId = json.get("user_id").asLong,
                    username = json.get("username").asString,
                    avatarUrl = avatarUrl,
                    content = json.get("content")?.asString ?: "",
                    createdAt = json.get("created_at").asString,
                    attachments = attachments,
                    replyToId = if (json.has("reply_to_id") && !json.get("reply_to_id").isJsonNull) json.get("reply_to_id").asLong else null,
                    replyTo = replyTo,
                    mentions = mentions,
                    reactions = reactions
                )
                Log.d(TAG, "Received message: ${message.id} from ${message.username}, attachments: ${attachments?.size ?: 0}, replyTo: ${replyTo?.username}, mentions: ${mentions?.size ?: 0}")
                WebSocketEvent.NewMessage(message)
            }
            "message_deleted" -> {
                val messageId = json.get("message_id").asLong
                val deletedBy = json.get("deleted_by").asLong
                val deletedByUsername = json.get("deleted_by_username").asString
                Log.d(TAG, "Message deleted: $messageId by $deletedByUsername")
                WebSocketEvent.MessageDeleted(messageId, deletedBy, deletedByUsername)
            }
            "message_edited" -> {
                val messageId = json.get("message_id").asLong
                val content = json.get("content").asString
                val editedAt = json.get("edited_at").asString
                Log.d(TAG, "Message edited: $messageId")
                WebSocketEvent.MessageEdited(messageId, content, editedAt)
            }
            "reaction_added" -> {
                val messageId = json.get("message_id").asLong
                val emoji = json.get("emoji").asString
                val userId = json.get("user_id").asLong
                val username = json.get("username").asString
                Log.d(TAG, "Reaction added: $emoji to message $messageId by $username")
                WebSocketEvent.ReactionAdded(messageId, emoji, userId, username)
            }
            "reaction_removed" -> {
                val messageId = json.get("message_id").asLong
                val emoji = json.get("emoji").asString
                val userId = json.get("user_id").asLong
                Log.d(TAG, "Reaction removed: $emoji from message $messageId")
                WebSocketEvent.ReactionRemoved(messageId, emoji, userId)
            }
            "error" -> {
                val errorCode = json.get("code")?.asString
                val errorMessage = json.get("message")?.asString ?: "Unknown error"
                Log.w(TAG, "Received error: code=$errorCode, message=$errorMessage")
                WebSocketEvent.Error(errorMessage, errorCode)
            }
            "user_joined" -> {
                val user = gson.fromJson(json.getAsJsonObject("user"), VoiceUser::class.java)
                WebSocketEvent.UserJoined(user)
            }
            "user_left" -> {
                val userId = json.get("user_id").asLong
                WebSocketEvent.UserLeft(userId)
            }
            "connected" -> {
                Log.v(TAG, "Received connected")
                null
            }
            else -> {
                Log.d(TAG, "Unknown message type: $type")
                null
            }
        }
    }

    fun sendMessage(channelId: Long, content: String, attachmentIds: List<Long> = emptyList(), replyToId: Long? = null): Boolean {
        if (connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send message, not connected")
            return false
        }

        return try {
            val payload = mutableMapOf<String, Any>(
                "type" to "message",
                "channel_id" to channelId,
                "content" to content
            )
            if (attachmentIds.isNotEmpty()) {
                payload["attachment_ids"] = attachmentIds
            }
            if (replyToId != null) {
                payload["reply_to_id"] = replyToId
            }
            webSocket?.send(gson.toJson(payload)) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            false
        }
    }

    companion object {
        private const val TAG = "ChatWebSocket"
    }
}
