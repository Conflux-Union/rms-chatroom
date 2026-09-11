package cn.net.rms.chatroom.data.manager

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.net.rms.chatroom.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

private val Context.mentionDataStore: DataStore<Preferences> by preferencesDataStore(name = "mention_notifications")

/**
 * Manages mention notifications, including:
 * - Detecting @mentions in messages
 * - Playing notification sounds
 * - Tracking mention state per channel
 * - Persisting mention state in DataStore
 */
class MentionNotificationManager(private val context: Context) {

    companion object {
        private const val TAG = "MentionManager"
    }

    private val dataStore = context.mentionDataStore

    // Sound cooldown tracking
    private val playedSounds = ConcurrentHashMap.newKeySet<String>() // "channelId-messageId"
    private var lastSoundPlayTime = 0L
    private val soundCooldownMs = 10_000L // 10 seconds

    // MediaPlayer for mention sound
    private var mediaPlayer: MediaPlayer? = null

    /**
     * Play mention notification sound with cooldown
     */
    fun playMentionSound(channelId: Long, messageId: Long) {
        val now = System.currentTimeMillis()
        val soundKey = "$channelId-$messageId"

        // Each mention only plays once
        if (playedSounds.contains(soundKey)) {
            return
        }

        // Cooldown check
        if (now - lastSoundPlayTime < soundCooldownMs) {
            return
        }

        // Mark as played immediately to prevent concurrent plays
        playedSounds.add(soundKey)

        try {
            // Release previous player if exists
            mediaPlayer?.release()

            // Create and play new sound
            mediaPlayer = MediaPlayer.create(context, R.raw.mention_notification).apply {
                setVolume(0.5f, 0.5f)
                setOnCompletionListener { mp ->
                    mp.release()
                }
                start()
            }

            lastSoundPlayTime = now

            // Cleanup old entries to prevent memory leak
            if (playedSounds.size > 100) {
                val entries = playedSounds.toList()
                playedSounds.clear()
                entries.takeLast(50).forEach { playedSounds.add(it) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Mark a channel as having a mention
     */
    suspend fun markChannelAsMentioned(channelId: Long, messageId: Long) {
        Log.d(TAG, "markChannelAsMentioned: channelId=$channelId, messageId=$messageId")
        dataStore.edit { prefs ->
            prefs[getMentionKey(channelId)] = true
            prefs[getLastMentionMessageIdKey(channelId)] = messageId
            prefs[getMentionTimestampKey(channelId)] = System.currentTimeMillis()
        }
        Log.d(TAG, "DataStore updated for channel $channelId (mention=true)")
    }

    /**
     * Clear mention state for a channel
     */
    suspend fun clearChannelMention(channelId: Long) {
        Log.d(TAG, "clearChannelMention: channelId=$channelId")
        dataStore.edit { prefs ->
            prefs[getMentionKey(channelId)] = false
            prefs[getUnreadCountKey(channelId)] = 0
        }
        Log.d(TAG, "DataStore updated for channel $channelId (mention=false, unread=0)")
    }

    /**
     * Atomically bump the unread count for a channel.
     */
    suspend fun incrementUnreadCount(channelId: Long) {
        dataStore.edit { prefs ->
            val key = getUnreadCountKey(channelId)
            prefs[key] = (prefs[key] ?: 0) + 1
        }
    }

    /**
     * Check if a channel has unread mentions
     */
    suspend fun hasUnreadMention(channelId: Long): Boolean {
        return dataStore.data.first()[getMentionKey(channelId)] ?: false
    }

    /**
     * Get all channels with unread mentions
     */
    fun getChannelsWithMentions(): Flow<Set<Long>> {
        return dataStore.data.map { prefs ->
            val mentions = prefs.asMap().keys
                .filter { it.name.startsWith("mention_") }
                .mapNotNull { key ->
                    val channelId = key.name.removePrefix("mention_").toLongOrNull()
                    if (channelId != null && prefs[key] == true) channelId else null
                }
                .toSet()
            Log.d(TAG, "getChannelsWithMentions emitted: $mentions")
            mentions
        }
    }

    /**
     * Get all unread counts
     */
    fun getAllUnreadCounts(): Flow<Map<Long, Int>> {
        return dataStore.data.map { prefs ->
            val counts = prefs.asMap().keys
                .filter { it.name.startsWith("unread_count_") }
                .mapNotNull { key ->
                    val channelId = key.name.removePrefix("unread_count_").toLongOrNull()
                    val count = prefs[key] as? Int
                    if (channelId != null && count != null && count > 0) {
                        channelId to count
                    } else null
                }
                .toMap()
            Log.d(TAG, "getAllUnreadCounts emitted: $counts")
            counts
        }
    }

    /**
     * Release resources
     */
    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // DataStore key helpers
    private fun getMentionKey(channelId: Long) = booleanPreferencesKey("mention_$channelId")
    private fun getLastMentionMessageIdKey(channelId: Long) = longPreferencesKey("last_mention_msg_$channelId")
    private fun getMentionTimestampKey(channelId: Long) = longPreferencesKey("mention_timestamp_$channelId")
    private fun getUnreadCountKey(channelId: Long) = intPreferencesKey("unread_count_$channelId")
}
