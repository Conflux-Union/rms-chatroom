package cn.net.rms.chatroom.data.livekit

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.data.tts.SherpaTtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks voice-channel join/leave announcements.
 *
 * Engine priority: the downloaded on-device model (kokoro via sherpa-onnx)
 * when its pack is installed, falling back to the system TTS engine, and
 * silently dropping the utterance when neither can speak the app's locale.
 * Installing the pack is an explicit opt-in, so it wins over whatever system
 * voice happens to be present; deleting it falls back to system TTS.
 *
 * The system engine is created when a voice connection is established
 * ([warmUp]) and shut down on disconnect ([release]), so no TTS resources
 * are held while the user is out of voice; the local engine follows the same
 * lifetime through [SherpaTtsEngine.setDesired].
 *
 * Audio uses USAGE_MEDIA + CONTENT_TYPE_SPEECH, matching the room's
 * MODE_NORMAL media output, so announcements play over the same route as the
 * call audio without fighting its focus.
 */
@Singleton
class VoiceTtsAnnouncer @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sherpaEngine: SherpaTtsEngine
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
     * Create the engines ahead of the first announcement; TTS initialization
     * is asynchronous and would otherwise drop the first utterance. The system
     * engine is warmed even when the local pack is installed: it is the
     * fallback for the window while the local model is still loading.
     */
    fun warmUp() {
        synchronized(lock) {
            if (!enabled) return
            sherpaEngine.setDesired(true)
            if (tts != null) return
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
        val text = appContext.getString(
            if (joined) R.string.voice_tts_joined else R.string.voice_tts_left,
            name
        )
        // The local pack takes priority once it is ready; while it is still
        // loading, a working system engine keeps the first announcement from
        // being dropped.
        if (sherpaEngine.isReady()) {
            sherpaEngine.speak(text, appLanguage())
            return
        }
        synchronized(lock) {
            val engine = tts ?: return
            if (!enabled || !ready) return
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "voice_presence")
        }
    }

    fun release() {
        synchronized(lock) {
            releaseLocked()
        }
        sherpaEngine.setDesired(false)
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

    private fun appLanguage(): String {
        val locale = appContext.resources.configuration.locales[0] ?: Locale.getDefault()
        return locale.language
    }
}
