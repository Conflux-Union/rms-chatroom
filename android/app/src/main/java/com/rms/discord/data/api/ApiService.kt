package com.rms.discord.data.api

import com.google.gson.annotations.SerializedName
import com.rms.discord.data.model.*
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

    // Music - Login
    @GET("api/music/login/check")
    suspend fun checkMusicLogin(
        @Header("Authorization") token: String
    ): MusicLoginCheckResponse

    @GET("api/music/login/qrcode")
    suspend fun getMusicQRCode(): MusicQRCodeResponse

    @GET("api/music/login/status")
    suspend fun checkMusicLoginStatus(): MusicLoginStatusResponse

    @POST("api/music/login/logout")
    suspend fun musicLogout(
        @Header("Authorization") token: String
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
        @Path("mid") mid: String
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
}

data class SendMessageBody(val content: String)
data class GuestJoinBody(val username: String)
