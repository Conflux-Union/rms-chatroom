package com.rms.discord.data.model

import com.google.gson.annotations.SerializedName

data class User(
    val id: Long,
    val username: String,
    val nickname: String,
    val email: String,
    @SerializedName("permission_level")
    val permissionLevel: Int,
    @SerializedName("is_active")
    val isActive: Boolean
)

data class Server(
    val id: Long,
    val name: String,
    val icon: String?,
    @SerializedName("owner_id")
    val ownerId: Long,
    val channels: List<Channel>? = null
)

data class Channel(
    val id: Long,
    @SerializedName("server_id")
    val serverId: Long,
    val name: String,
    val type: ChannelType,
    val position: Int
)

enum class ChannelType {
    @SerializedName("text")
    TEXT,
    @SerializedName("voice")
    VOICE
}

data class Message(
    val id: Long,
    @SerializedName("channel_id")
    val channelId: Long,
    @SerializedName("user_id")
    val userId: Long,
    val username: String,
    val content: String,
    @SerializedName("created_at")
    val createdAt: String
)

data class VoiceUser(
    val id: Long,
    val username: String,
    val muted: Boolean,
    val deafened: Boolean
)

// API Response wrappers
data class TokenVerifyResponse(
    val valid: Boolean,
    val user: User?
)

data class VoiceTokenResponse(
    val token: String,
    val url: String
)

// WebSocket message types
sealed class WsMessage {
    data class ChatMessage(
        val type: String = "message",
        val data: Message
    ) : WsMessage()

    data class UserJoined(
        val type: String = "user_joined",
        val user: VoiceUser
    ) : WsMessage()

    data class UserLeft(
        val type: String = "user_left",
        val userId: Long
    ) : WsMessage()
}
