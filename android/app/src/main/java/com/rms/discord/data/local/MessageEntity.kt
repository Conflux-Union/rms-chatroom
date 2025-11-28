package com.rms.discord.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rms.discord.data.model.Message

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["channel_id"]),
        Index(value = ["channel_id", "created_at"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: Long,
    @ColumnInfo(name = "channel_id")
    val channelId: Long,
    @ColumnInfo(name = "user_id")
    val userId: Long,
    val username: String,
    val content: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "cached_at")
    val cachedAt: Long = System.currentTimeMillis()
) {
    fun toMessage(): Message = Message(
        id = id,
        channelId = channelId,
        userId = userId,
        username = username,
        content = content,
        createdAt = createdAt
    )

    companion object {
        fun fromMessage(message: Message): MessageEntity = MessageEntity(
            id = message.id,
            channelId = message.channelId,
            userId = message.userId,
            username = message.username,
            content = message.content,
            createdAt = message.createdAt
        )
    }
}
