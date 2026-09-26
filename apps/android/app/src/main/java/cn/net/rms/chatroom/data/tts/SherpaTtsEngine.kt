package cn.net.rms.chatroom.data.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device neural TTS over the downloaded sherpa-onnx pack. The runtime .so
 * files are not in the APK: libonnxruntime is System.load()-ed from the
 * installed pack first so the linker can resolve libsherpa-onnx-jni's
 * DT_NEEDED entry against the already-loaded soname.
 *
 * Lifetime is driven by two inputs: the announcer's desire (voice connected)
 * and the pack install state. The engine loads when both hold and frees the
 * model when either drops, so deleting the pack from the storage screen tears
 * the engine down even if no voice screen is alive to notice.
 *
 * Utterances serialize through [speakMutex]: one generate + play at a time,
 * mirroring the system engine's QUEUE_ADD behavior.
 */
@Singleton
class SherpaTtsEngine @Inject constructor(
    private val modelManager: TtsModelManager
) {
    companion object {
        private const val TAG = "SherpaTtsEngine"

        // kokoro-int8-multi-lang-v1_1 voices.bin layout: 0 af_maple,
        // 1 af_sol, 2 bf_vale, then the zf_* (Chinese female) speakers.
        private const val EN_SID = 0
        private const val ZH_SID = 5
        // Kokoro benefits from more threads (upstream default for it is 4).
        private const val NUM_THREADS = 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Set by the announcer: true while a voice connection wants announcements. */
    private val desired = MutableStateFlow(false)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val lock = Any()
    private var runtimeLoaded = false
    private var tts: OfflineTts? = null
    private var activeTrack: AudioTrack? = null

    private val speakMutex = Mutex()

    init {
        scope.launch {
            combine(modelManager.state, desired) { state, want -> state to want }
                .collect { (state, want) ->
                    if (want && state is TtsModelState.Ready) {
                        ensureLoaded()
                    } else {
                        unload()
                    }
                }
        }
    }

    fun setDesired(value: Boolean) {
        desired.value = value
    }

    /** True when the model is loaded and an utterance can start right away. */
    fun isReady(): Boolean = _ready.value

    private fun ensureLoaded() {
        synchronized(lock) {
            if (_ready.value) return
            val engine = try {
                val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return
                val runtimeDir = File(modelManager.runtimeDir, abi)
                if (!runtimeLoaded) {
                    System.load(File(runtimeDir, "libonnxruntime.so").absolutePath)
                    System.load(File(runtimeDir, "libsherpa-onnx-jni.so").absolutePath)
                    runtimeLoaded = true
                }
                OfflineTts(config = buildConfig())
            } catch (e: Throwable) {
                Log.e(TAG, "sherpa-onnx engine load failed", e)
                null
            }
            tts = engine
            _ready.value = engine != null
            if (engine != null) {
                Log.i(TAG, "sherpa-onnx ready: ${engine.numSpeakers()} speakers, ${engine.sampleRate()} Hz")
            }
        }
    }

    private fun buildConfig(): OfflineTtsConfig {
        val model = modelManager.modelDir.absolutePath
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$model/model.int8.onnx",
                    voices = "$model/voices.bin",
                    tokens = "$model/tokens.txt",
                    dataDir = "$model/espeak-ng-data",
                    lexicon = "$model/lexicon-us-en.txt,$model/lexicon-zh.txt"
                ),
                numThreads = NUM_THREADS,
                provider = "cpu"
            ),
            // Mandarin text normalization: digits, dates, phone numbers.
            ruleFsts = "$model/phone-zh.fst,$model/date-zh.fst,$model/number-zh.fst"
        )
    }

    private fun unload() {
        synchronized(lock) {
            runCatching { activeTrack?.let { it.stop(); it.release() } }
            activeTrack = null
            tts?.let { runCatching { it.release() } }
            tts = null
            _ready.value = false
        }
    }

    /**
     * Synchronous generation behind [speak]; also the instrumented-test seam
     * for verifying the loaded engine end to end. Blocking and CPU-bound —
     * never call on the main thread.
     */
    internal fun generateSamples(text: String, language: String): com.k2fsa.sherpa.onnx.GeneratedAudio? {
        val engine = synchronized(lock) { tts } ?: return null
        val sid = if (language.startsWith("zh")) ZH_SID else EN_SID
        return engine.generate(text, sid = sid, speed = 1.0f)
    }

    /**
     * Queues one utterance and returns immediately. Audio routes with the
     * same attributes as the system announcer (USAGE_MEDIA + speech), so it
     * shares the call's output route without requesting extra focus.
     */
    fun speak(text: String, language: String) {
        if (text.isBlank()) return
        scope.launch {
            speakMutex.withLock {
                try {
                    val startedAt = System.currentTimeMillis()
                    val audio = withContext(Dispatchers.Default) {
                        generateSamples(text, language)
                    } ?: return@withLock
                    val generatedIn = System.currentTimeMillis() - startedAt
                    play(audio.samples, audio.sampleRate)
                    val durationSec = audio.samples.size.toFloat() / audio.sampleRate
                    Log.d(
                        TAG,
                        "spoke ${audio.samples.size} samples @${audio.sampleRate}Hz " +
                            "(${generatedIn}ms gen / ${"%.1f".format(durationSec)}s audio)"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "generate/play failed", e)
                }
            }
        }
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(samples.size * Float.SIZE_BYTES)
            .build()
        synchronized(lock) { activeTrack = track }
        try {
            track.play()
            // Chunked so an unload() between chunks still stops playback
            // promptly; a stopped track makes write() return early.
            val chunk = sampleRate / 2
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + chunk, samples.size)
                val written = track.write(samples, offset, end - offset, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
                if (!_ready.value) break
                offset = end
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            synchronized(lock) {
                if (activeTrack === track) activeTrack = null
            }
        }
    }
}
