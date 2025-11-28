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
}

data class SendMessageBody(val content: String)
data class GuestJoinBody(val username: String)
