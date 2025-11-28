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
    val id: String,
    val name: String,
    @SerializedName("is_muted")
    val isMuted: Boolean,
    @SerializedName("is_host")
    val isHost: Boolean = false,
    // Local state for speaking indicator
    var isSpeaking: Boolean = false
) {
    // Backward compatibility
    val username: String get() = name
    val muted: Boolean get() = isMuted
    val deafened: Boolean get() = false
}

// API Response wrappers
data class TokenVerifyResponse(
    val valid: Boolean,
    val user: User?
)

data class AuthMeResponse(
    val success: Boolean,
    val user: User?
)

data class VoiceTokenResponse(
    val token: String,
    val url: String,
    @SerializedName("room_name")
    val roomName: String = "",
    @SerializedName("channel_name")
    val channelName: String? = null
)

data class VoiceInviteInfo(
    val valid: Boolean,
    @SerializedName("channel_name")
    val channelName: String? = null,
    @SerializedName("server_name")
    val serverName: String? = null
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
