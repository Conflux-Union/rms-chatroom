package cn.net.rms.chatroom.data.model

import com.google.gson.annotations.SerializedName

data class User(
    val id: Long,
    val username: String,
    val nickname: String,
    val email: String,
    @SerializedName("permission_level")
    val permissionLevel: Int,
    @SerializedName("group_level")
    val groupLevel: Int = 0,
    @SerializedName("avatar_url")
    val avatarUrl: String? = null
)

data class Server(
    val id: Long,
    val name: String,
    val icon: String?,
    @SerializedName("owner_id")
    val ownerId: Long,
    val channels: List<Channel>? = null,
    @SerializedName("min_level")
    val minLevel: Int = 0,
    @SerializedName("perm_min_level")
    val permMinLevel: Int = 0,
    @SerializedName("logic_operator")
    val logicOperator: String = "AND"
)

data class Channel(
    val id: Long,
    @SerializedName("server_id")
    val serverId: Long,
    val name: String,
    val type: ChannelType,
    val position: Int,
    @SerializedName("top_position")
    val topPosition: Int = 0,
    @SerializedName("group_id")
    val groupId: Long? = null,
    @SerializedName("min_level")
    val minLevel: Int = 0,
    @SerializedName("perm_min_level")
    val permMinLevel: Int = 0,
    @SerializedName("logic_operator")
    val logicOperator: String = "AND",
    @SerializedName("speak_min_level")
    val speakMinLevel: Int = 0,
    @SerializedName("speak_perm_min_level")
    val speakPermMinLevel: Int = 0,
    @SerializedName("speak_logic_operator")
    val speakLogicOperator: String = "AND"
)

data class ChannelGroup(
    val id: Long,
    @SerializedName("server_id")
    val serverId: Long,
    val name: String,
    val position: Int,
    @SerializedName("min_level")
    val minLevel: Int = 0,
    @SerializedName("perm_min_level")
    val permMinLevel: Int = 0,
    @SerializedName("logic_operator")
    val logicOperator: String = "AND"
)

enum class ChannelType {
    @SerializedName("TEXT")
    TEXT,
    @SerializedName("VOICE")
    VOICE
}

data class Attachment(
    val id: Long,
    val filename: String,
    @SerializedName("content_type")
    val contentType: String,
    val size: Long,
    val url: String
)

// Reply feature models
data class ReplyTo(
    val id: Long,
    @SerializedName("user_id")
    val userId: Long,
    val username: String,
    val content: String  // Truncated preview
)

// Mention feature models
data class Mention(
    val id: Long,
    val username: String
)

// Reaction feature models
data class ReactionUser(
    val id: Long,
    val username: String
)

data class ReactionGroup(
    val emoji: String,
    val count: Int,
    val users: List<ReactionUser>,
    val reacted: Boolean = false  // Whether current user has reacted
)

data class Message(
    val id: Long,
    @SerializedName("channel_id")
    val channelId: Long,
    @SerializedName("user_id")
    val userId: Long,
    val username: String,
    @SerializedName("avatar_url")
    val avatarUrl: String? = null,
    val content: String,
    @SerializedName("created_at")
    val createdAt: String,
    val attachments: List<Attachment>? = null,
    // Message management fields
    @SerializedName("is_deleted")
    val isDeleted: Boolean = false,
    @SerializedName("deleted_by")
    val deletedBy: Long? = null,
    @SerializedName("deleted_by_username")
    val deletedByUsername: String? = null,
    @SerializedName("edited_at")
    val editedAt: String? = null,
    // Reply feature
    @SerializedName("reply_to_id")
    val replyToId: Long? = null,
    @SerializedName("reply_to")
    val replyTo: ReplyTo? = null,
    // Mention feature
    val mentions: List<Mention>? = null,
    // Reaction feature
    val reactions: List<ReactionGroup>? = null
)

data class VoiceUser(
    val id: String,
    val name: String,
    @SerializedName("avatar_url")
    val avatarUrl: String? = null,
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

data class KickParticipantResponse(
    val success: Boolean
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

data class AllVoiceUsersResponse(
    val users: Map<Long, List<VoiceUser>>
)

// Screen share lock models
data class ScreenShareStatusResponse(
    val locked: Boolean,
    @SerializedName("sharer_id")
    val sharerId: String?,
    @SerializedName("sharer_name")
    val sharerName: String?
)

data class ScreenShareLockResponse(
    val success: Boolean,
    @SerializedName("sharer_id")
    val sharerId: String?,
    @SerializedName("sharer_name")
    val sharerName: String?
)

// File upload response
data class AttachmentResponse(
    val id: Long,
    val filename: String,
    @SerializedName("content_type")
    val contentType: String,
    val size: Long,
    val url: String
)

// Message moderation models
enum class MuteScope {
    @SerializedName("global")
    GLOBAL,
    @SerializedName("server")
    SERVER,
    @SerializedName("channel")
    CHANNEL
}

data class MuteRecord(
    val id: Long,
    @SerializedName("user_id")
    val userId: Long,
    val scope: MuteScope,
    @SerializedName("server_id")
    val serverId: Long? = null,
    @SerializedName("channel_id")
    val channelId: Long? = null,
    @SerializedName("muted_until")
    val mutedUntil: String? = null,
    @SerializedName("muted_by")
    val mutedBy: Long,
    val reason: String? = null,
    @SerializedName("created_at")
    val createdAt: String
)

data class MuteCreateRequest(
    @SerializedName("user_id")
    val userId: Long,
    val scope: String,  // "global", "server", or "channel"
    @SerializedName("server_id")
    val serverId: Long? = null,
    @SerializedName("channel_id")
    val channelId: Long? = null,
    @SerializedName("duration_minutes")
    val durationMinutes: Int? = null,
    val reason: String? = null
)

data class MuteResponse(
    val id: Long,
    @SerializedName("user_id")
    val userId: Long,
    val scope: String,
    @SerializedName("muted_until")
    val mutedUntil: String?,
    val reason: String?
)

data class MessageEditRequest(
    val content: String
)
