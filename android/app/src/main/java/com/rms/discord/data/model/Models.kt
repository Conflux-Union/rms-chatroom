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

// Music models
data class Song(
    val mid: String,
    val name: String,
    val artist: String,
    val album: String,
    val duration: Int,
    val cover: String,
    val platform: String = "qq"  // "qq" or "netease"
)

data class QueueItem(
    val song: Song,
    @SerializedName("requested_by")
    val requestedBy: String
)

data class MusicQueueResponse(
    @SerializedName("is_playing")
    val isPlaying: Boolean,
    @SerializedName("current_song")
    val currentSong: Song?,
    @SerializedName("current_index")
    val currentIndex: Int,
    val queue: List<QueueItem>
)

data class MusicSearchResponse(
    val songs: List<Song>
)

data class MusicBotStatusResponse(
    val connected: Boolean,
    val room: String?,
    @SerializedName("is_playing")
    val isPlaying: Boolean
)

data class MusicProgressResponse(
    @SerializedName("position_ms")
    val positionMs: Long,
    @SerializedName("duration_ms")
    val durationMs: Long,
    val state: String,
    @SerializedName("current_song")
    val currentSong: Song?
)

data class MusicLoginCheckResponse(
    @SerializedName("logged_in")
    val loggedIn: Boolean,
    val platform: String = "qq"
)

data class PlatformLoginItem(
    @SerializedName("logged_in")
    val loggedIn: Boolean
)

data class AllPlatformLoginStatus(
    val qq: PlatformLoginItem,
    val netease: PlatformLoginItem
)

data class MusicQRCodeResponse(
    val qrcode: String,
    val platform: String = "qq"
)

data class MusicLoginStatusResponse(
    val status: String,
    @SerializedName("logged_in")
    val loggedIn: Boolean = false,
    val platform: String = "qq"
)

data class MusicSongUrlResponse(
    val url: String,
    val mid: String
)

data class MusicSearchRequest(
    val keyword: String,
    val num: Int = 20,
    val platform: String = "all"  // "all", "qq", or "netease"
)

data class MusicBotStartRequest(
    @SerializedName("room_name")
    val roomName: String
)

data class MusicRoomRequest(
    @SerializedName("room_name")
    val roomName: String
)

data class MusicQueueAddRequest(
    @SerializedName("room_name")
    val roomName: String,
    val song: Song
)

data class MusicSeekRequest(
    @SerializedName("room_name")
    val roomName: String,
    @SerializedName("position_ms")
    val positionMs: Long
)

data class MusicSuccessResponse(
    val success: Boolean,
    val position: Int? = null,
    @SerializedName("current_index")
    val currentIndex: Int? = null,
    val playing: String? = null,
    val message: String? = null
)

// Voice Admin models
data class MuteParticipantRequest(
    val muted: Boolean = true
)

data class MuteParticipantResponse(
    val success: Boolean,
    val muted: Boolean
)

data class HostModeRequest(
    val enabled: Boolean
)

data class HostModeResponse(
    val enabled: Boolean,
    @SerializedName("host_id")
    val hostId: String?,
    @SerializedName("host_name")
    val hostName: String?
)

data class InviteCreateResponse(
    @SerializedName("invite_url")
    val inviteUrl: String,
    val token: String
)
