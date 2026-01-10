package cn.net.rms.chatroom.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import cn.net.rms.chatroom.data.model.Message

/**
 * Room entity for caching messages locally.
 * Note: Complex fields (reactions, mentions, reply_to) are not cached
 * as they require real-time updates from the server.
 */
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
    val cachedAt: Long = System.currentTimeMillis(),
    // Reply feature - only store the ID, full data comes from server
    @ColumnInfo(name = "reply_to_id")
    val replyToId: Long? = null,
    @ColumnInfo(name = "reply_to_username")
    val replyToUsername: String? = null,
    @ColumnInfo(name = "reply_to_content")
    val replyToContent: String? = null
) {
    fun toMessage(): Message = Message(
        id = id,
        channelId = channelId,
        userId = userId,
        username = username,
        content = content,
        createdAt = createdAt,
        replyToId = replyToId,
        replyTo = if (replyToId != null && replyToUsername != null) {
            cn.net.rms.chatroom.data.model.ReplyTo(
                id = replyToId,
                userId = 0,  // Not cached
                username = replyToUsername,
                content = replyToContent ?: ""
            )
        } else null
        // Note: reactions, mentions, attachments are not cached
    )

    companion object {
        fun fromMessage(message: Message): MessageEntity = MessageEntity(
            id = message.id,
            channelId = message.channelId,
            userId = message.userId,
            username = message.username,
            content = message.content,
            createdAt = message.createdAt,
            replyToId = message.replyToId,
            replyToUsername = message.replyTo?.username,
            replyToContent = message.replyTo?.content
        )
    }
}
