package com.rms.discord.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE channel_id = :channelId ORDER BY created_at ASC")
    fun getMessagesByChannel(channelId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE channel_id = :channelId ORDER BY created_at ASC")
    suspend fun getMessagesByChannelOnce(channelId: Long): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE channel_id = :channelId")
    suspend fun deleteMessagesByChannel(channelId: Long)

    @Query("DELETE FROM messages WHERE cached_at < :timestamp")
    suspend fun deleteOldMessages(timestamp: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE channel_id = :channelId")
    suspend fun getMessageCount(channelId: Long): Int

    @Query("SELECT * FROM messages WHERE channel_id = :channelId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestMessage(channelId: Long): MessageEntity?
}
