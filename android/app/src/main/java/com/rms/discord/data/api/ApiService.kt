package com.rms.discord.data.api

import com.google.gson.annotations.SerializedName
import com.rms.discord.data.model.*
import retrofit2.http.*

interface ApiService {

    // Auth
    @GET("api/auth/verify")
    suspend fun verifyToken(@Header("Authorization") token: String): TokenVerifyResponse

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
    @POST("api/voice/join")
    suspend fun joinVoice(
        @Header("Authorization") token: String,
        @Body body: JoinVoiceBody
    ): VoiceTokenResponse

    @POST("api/voice/leave")
    suspend fun leaveVoice(
        @Header("Authorization") token: String,
        @Body body: LeaveVoiceBody
    )

    @GET("api/voice/channel/{id}/users")
    suspend fun getVoiceUsers(
        @Header("Authorization") token: String,
        @Path("id") channelId: Long
    ): List<VoiceUser>
}

data class SendMessageBody(val content: String)
data class JoinVoiceBody(@SerializedName("channel_id") val channelId: Long)
data class LeaveVoiceBody(@SerializedName("channel_id") val channelId: Long)
