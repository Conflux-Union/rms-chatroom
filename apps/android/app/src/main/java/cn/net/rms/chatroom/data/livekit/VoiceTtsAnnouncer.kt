package cn.net.rms.chatroom.data.livekit

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import cn.net.rms.chatroom.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks voice-channel join/leave announcements through the system TTS engine.
 *
 * The engine is created when a voice connection is established ([warmUp]) and
 * shut down on disconnect ([release]), so no TTS resources are held while the
 * user is out of voice. Utterances resolve through the application context,
 * which AppLocale keeps aligned with the in-app language. If the engine has no
 * voice for that locale, announcements are skipped instead of being read in
 * the wrong language.
 *
 * Audio uses USAGE_MEDIA + CONTENT_TYPE_SPEECH, matching the room's
 * MODE_NORMAL media output, so announcements play over the same route as the
 * call audio without fighting its focus.
 */
@Singleton
class VoiceTtsAnnouncer @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    companion object {
        private const val TAG = "VoiceTtsAnnouncer"
    }

    private val lock = Any()
    private var enabled = true
    private var tts: TextToSpeech? = null
    private var ready = false

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if (enabled == value) return
            enabled = value
            if (!value) releaseLocked()
        }
    }

    /**
     * Create the engine ahead of the first announcement; TTS initialization is
     * asynchronous and would otherwise drop the first utterance.
     */
    fun warmUp() {
        synchronized(lock) {
            if (!enabled || tts != null) return
            tts = TextToSpeech(appContext) { status ->
                synchronized(lock) {
                    if (status != TextToSpeech.SUCCESS) {
                        Log.w(TAG, "TTS engine unavailable (status=$status)")
                        releaseLocked()
                        return@synchronized
                    }
                    val engine = tts ?: return@synchronized
                    val locale = appContext.resources.configuration.locales[0]
                        ?: Locale.getDefault()
                    val langResult = engine.setLanguage(locale)
                    if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                        langResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        Log.w(TAG, "No TTS voice for $locale, announcements disabled")
                        releaseLocked()
                        return@synchronized
                    }
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    ready = true
                }
            }
        }
    }

    fun announce(name: String, joined: Boolean) {
        synchronized(lock) {
            val engine = tts ?: return
            if (!enabled || !ready) return
            val text = appContext.getString(
                if (joined) R.string.voice_tts_joined else R.string.voice_tts_left,
                name
            )
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "voice_presence")
        }
    }

    fun release() {
        synchronized(lock) {
            releaseLocked()
        }
    }

    private fun releaseLocked() {
        tts?.let { engine ->
            runCatching {
                engine.stop()
                engine.shutdown()
            }
        }
        tts = null
        ready = false
    }
}
