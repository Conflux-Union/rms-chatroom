package cn.net.rms.chatroom.data.manager

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import cn.net.rms.chatroom.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays the @mention notification sound. Unread counts and mention badge
 * flags are server-derived (see ReadPositionRepository); this class no longer
 * tracks any state.
 */
class MentionNotificationManager(private val context: Context) {

    companion object {
        private const val TAG = "MentionManager"
    }

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
     * Release resources
     */
    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
