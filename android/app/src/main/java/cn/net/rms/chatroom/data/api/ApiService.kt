package cn.net.rms.chatroom.data.api

import com.google.gson.annotations.SerializedName
import cn.net.rms.chatroom.data.model.*
import okhttp3.MultipartBody
import retrofit2.http.*

interface ApiService {

    // Auth
    @GET("api/auth/me")
    suspend fun verifyToken(@Header("Authorization") token: String): AuthMeResponse

    // Servers
    @GET("api/servers")
    suspend fun getServers(@Header("Authorization") token: String): List<Server>

    @GET("api/servers/{id}")
    suspend fun getServer(
        @Header("Authorization") token: String,
        @Path("id") serverId: Long
    ): Server

    // Server Management (admin only)
    @POST("api/servers")
    suspend fun createServer(
        @Header("Authorization") token: String,
        @Body body: CreateServerRequest
    ): Server

    @DELETE("api/servers/{serverId}")
    suspend fun deleteServer(
        @Header("Authorization") token: String,
        @Path("serverId") serverId: Long
    )

    // Channel Management (admin only)
    @POST("api/servers/{serverId}/channels")
    suspend fun createChannel(
        @Header("Authorization") token: String,
        @Path("serverId") serverId: Long,
        @Body body: CreateChannelRequest
    ): Channel

    @DELETE("api/servers/{serverId}/channels/{channelId}")
    suspend fun deleteChannel(
        @Header("Authorization") token: String,
        @Path("serverId") serverId: Long,
        @Path("channelId") channelId: Long
    )

    // Channels
    @GET("api/channels/{id}/messages")
    suspend fun getMessages(
        @Header("Authorization") token: String,
        @Path("id") channelId: Long,
        @Query("limit") limit: Int = 50
    ): List<Message>

    @POST("api/channels/{id}/messages")
    suspend fun sendMessage(
        @Header("Authorization") token: String,
        @Path("id") channelId: Long,
        @Body body: SendMessageBody
    ): Message

    // Voice
    @GET("api/voice/{channelId}/token")
    suspend fun getVoiceToken(
        @Header("Authorization") token: String,
        @Path("channelId") channelId: Long
    ): VoiceTokenResponse

    @GET("api/voice/{channelId}/users")
    suspend fun getVoiceUsers(
        @Header("Authorization") token: String,
        @Path("channelId") channelId: Long
    ): List<VoiceUser>

    @GET("api/voice/user/all")
    suspend fun getAllVoiceUsers(
        @Header("Authorization") token: String
    ): AllVoiceUsersResponse

    // Voice Invite (guest access)
    @GET("api/voice/invite/{token}")
    suspend fun getVoiceInviteInfo(
        @Path("token") token: String
    ): VoiceInviteInfo

    @POST("api/voice/invite/{token}/join")
    suspend fun joinVoiceAsGuest(
        @Path("token") token: String,
        @Body body: GuestJoinBody
    ): VoiceTokenResponse

    // Voice Admin (requires admin permission)
    @POST("api/voice/{channelId}/mute/{userId}")
    suspend fun muteParticipant(
        @Header("Authorization") token: String,
        @Path("channelId") channelId: Long,
        @Path("userId") userId: String,
        @Body body: MuteParticipantRequest
    ): MuteParticipantResponse

    @POST("api/voice/{channelId}/kick/{userId}")
    suspend fun kickParticipant(
        @Header("Authorization") token: String,
        @Path("channelId") channelId: Long,
        @Path("userId") userId: String
    ): KickParticipantResponse

    @GET("api/voice/{channelId}/host-mode")
    suspend fun getHostMode(
        @Header("Authorization") token: String,
        @Path("channelId") channelId: Long
    ): HostModeResponse

    @POST("api/voice/{channelId}/host-mode")
    suspend fun setHostMode(
        @Header("Authorization") token: String,
        @Path("channelId") channelId: Long,
        @Body body: HostModeRequest
    ): HostModeResponse

    @POST("api/voice/{channelId}/invite")
    suspend fun createVoiceInvite(
        @Header("Authorization") token: String,
        @Path("channelId") channelId: Long
    ): InviteCreateResponse

    // Screen share lock APIs
    @GET("api/voice/{channelId}/screen-share-status")
    suspend fun getScreenShareStatus(
        @Header("Authorization") token: String,
        @Path("channelId") channelId: Long
    ): ScreenShareStatusResponse

    @POST("api/voice/{channelId}/screen-share/lock")
    suspend fun lockScreenShare(
        @Header("Authorization") token: String,
        @Path("channelId") channelId: Long
    ): ScreenShareLockResponse

    @POST("api/voice/{channelId}/screen-share/unlock")
    suspend fun unlockScreenShare(
        @Header("Authorization") token: String,
        @Path("channelId") channelId: Long
    ): ScreenShareLockResponse

    // Music - Login
    @GET("api/music/login/check")
    suspend fun checkMusicLogin(
        @Header("Authorization") token: String,
        @Query("platform") platform: String = "qq"
    ): MusicLoginCheckResponse

    @GET("api/music/login/check/all")
    suspend fun checkAllMusicLogin(
        @Header("Authorization") token: String
    ): AllPlatformLoginStatus

    @GET("api/music/login/qrcode")
    suspend fun getMusicQRCode(
        @Query("platform") platform: String = "qq"
    ): MusicQRCodeResponse

    @GET("api/music/login/status")
    suspend fun checkMusicLoginStatus(
        @Query("platform") platform: String = "qq"
    ): MusicLoginStatusResponse

    @POST("api/music/login/logout")
    suspend fun musicLogout(
        @Header("Authorization") token: String,
        @Query("platform") platform: String = "qq"
    ): MusicSuccessResponse

    // Music - Search
    @POST("api/music/search")
    suspend fun searchMusic(
        @Header("Authorization") token: String,
        @Body request: MusicSearchRequest
    ): MusicSearchResponse

    @GET("api/music/song/{mid}/url")
    suspend fun getSongUrl(
        @Header("Authorization") token: String,
        @Path("mid") mid: String,
        @Query("platform") platform: String = "qq"
    ): MusicSongUrlResponse

    // Music - Queue (per room)
    @GET("api/music/queue/{roomName}")
    suspend fun getMusicQueue(
        @Header("Authorization") token: String,
        @Path("roomName") roomName: String
    ): MusicQueueResponse

    @POST("api/music/queue/add")
    suspend fun addToMusicQueue(
        @Header("Authorization") token: String,
        @Body request: MusicQueueAddRequest
    ): MusicSuccessResponse

    @DELETE("api/music/queue/{roomName}/{index}")
    suspend fun removeFromMusicQueue(
        @Header("Authorization") token: String,
        @Path("roomName") roomName: String,
        @Path("index") index: Int
    ): MusicSuccessResponse

    @POST("api/music/queue/clear")
    suspend fun clearMusicQueue(
        @Header("Authorization") token: String,
        @Body request: MusicRoomRequest
    ): MusicSuccessResponse

    // Music - Bot Control (per room)
    @GET("api/music/bot/status/{roomName}")
    suspend fun getMusicBotStatus(
        @Header("Authorization") token: String,
        @Path("roomName") roomName: String
    ): MusicBotStatusResponse

    @POST("api/music/bot/start")
    suspend fun startMusicBot(
        @Header("Authorization") token: String,
        @Body request: MusicBotStartRequest
    ): MusicSuccessResponse

    @POST("api/music/bot/stop")
    suspend fun stopMusicBot(
        @Header("Authorization") token: String,
        @Body request: MusicRoomRequest
    ): MusicSuccessResponse

    @POST("api/music/bot/play")
    suspend fun musicBotPlay(
        @Header("Authorization") token: String,
        @Body request: MusicBotStartRequest
    ): MusicSuccessResponse

    @POST("api/music/bot/pause")
    suspend fun musicBotPause(
        @Header("Authorization") token: String,
        @Body request: MusicRoomRequest
    ): MusicSuccessResponse

    @POST("api/music/bot/resume")
    suspend fun musicBotResume(
        @Header("Authorization") token: String,
        @Body request: MusicRoomRequest
    ): MusicSuccessResponse

    @POST("api/music/bot/skip")
    suspend fun musicBotSkip(
        @Header("Authorization") token: String,
        @Body request: MusicRoomRequest
    ): MusicSuccessResponse

    @POST("api/music/bot/previous")
    suspend fun musicBotPrevious(
        @Header("Authorization") token: String,
        @Body request: MusicRoomRequest
    ): MusicSuccessResponse

    @POST("api/music/bot/seek")
    suspend fun musicBotSeek(
        @Header("Authorization") token: String,
        @Body request: MusicSeekRequest
    ): MusicSuccessResponse

    @GET("api/music/bot/progress/{roomName}")
    suspend fun getMusicProgress(
        @Header("Authorization") token: String,
        @Path("roomName") roomName: String
    ): MusicProgressResponse

    // File Upload
    @Multipart
    @POST("api/channels/{channelId}/upload")
    suspend fun uploadFile(
        @Header("Authorization") token: String,
        @Path("channelId") channelId: Long,
        @Part file: MultipartBody.Part
    ): AttachmentResponse

    // Bug Report
    @Multipart
    @POST("api/bug/report")
    suspend fun submitBugReport(
        @Part file: MultipartBody.Part
    ): BugReportResponse

    // App Update - GitHub Release
    @GET
    suspend fun checkGitHubRelease(@Url url: String): GitHubReleaseResponse
}

data class SendMessageBody(
    val content: String = "",
    @SerializedName("attachment_ids")
    val attachmentIds: List<Long> = emptyList()
)
data class GuestJoinBody(val username: String)
data class CreateChannelRequest(val name: String, val type: String = "text")
data class CreateServerRequest(val name: String, val icon: String? = null)

// Bug Report
data class BugReportResponse(
    @SerializedName("report_id")
    val reportId: String
)

// GitHub Release Response
data class GitHubReleaseResponse(
    @SerializedName("tag_name")
    val tagName: String,  // e.g., "v1.0.7-fix-2(33)"
    val name: String,
    val body: String,  // Release notes / changelog
    @SerializedName("html_url")
    val htmlUrl: String,
    val assets: List<GitHubAsset>,
    val prerelease: Boolean
)

data class GitHubAsset(
    val name: String,  // e.g., "rms-chatroom-1.0.7-fix-2.apk"
    @SerializedName("browser_download_url")
    val browserDownloadUrl: String,
    val size: Long
)

// App Update (legacy, kept for compatibility)
data class AppUpdateResponse(
    @SerializedName("version_code")
    val versionCode: Int,
    @SerializedName("version_name")
    val versionName: String,
    val changelog: String,
    @SerializedName("force_update")
    val forceUpdate: Boolean,
    @SerializedName("download_url")
    val downloadUrl: String
)
