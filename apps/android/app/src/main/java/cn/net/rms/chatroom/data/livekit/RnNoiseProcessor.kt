package cn.net.rms.chatroom.data.livekit

import android.util.Log
import io.livekit.android.audio.AudioProcessorInterface
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RNNoise neural noise suppression on the captured microphone audio, plugged
 * into LiveKit's capture post-processing chain.
 *
 * The WebRTC audio device hands over 10 ms frames of 16-bit little-endian
 * interleaved PCM; at 48 kHz mono that is exactly RNNoise's 480-sample frame.
 * Other rates or channel layouts pass through untouched (Android voice
 * capture is 48 kHz mono in practice).
 *
 * Runs on the native audio thread: no allocations after initialization and
 * no blocking calls.
 */
class RnNoiseProcessor(
    private val enabled: StateFlow<Boolean>,
) : AudioProcessorInterface {

    private var denoiser: RnNoise? = null
    private var input: FloatArray = EMPTY_FRAME
    private var output: FloatArray = EMPTY_FRAME
    private var sampleRate = 0
    private var channels = 0

    override fun isEnabled(): Boolean = enabled.value

    override fun getName(): String = "rnnoise"

    override fun initializeAudioProcessing(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        reset()
    }

    override fun resetAudioProcessing(newSampleRate: Int) {
        sampleRate = newSampleRate
        reset()
    }

    private fun reset() {
        releaseDenoiser()
        if (sampleRate == TARGET_SAMPLE_RATE && channels == 1) {
            val rnn = RnNoise.create()
            if (rnn != null) {
                denoiser = rnn
                input = FloatArray(rnn.frameSize)
                output = FloatArray(rnn.frameSize)
            }
        } else if (sampleRate != 0) {
            Log.w(TAG, "RNNoise inactive: ${sampleRate}Hz/${channels}ch capture is not 48kHz mono")
        }
    }

    override fun processAudio(sampleRate: Int, channels: Int, audioBuffer: ByteBuffer) {
        val rnn = denoiser ?: return
        if (!isEnabled()) return
        if (sampleRate != TARGET_SAMPLE_RATE || channels != 1) return

        val frameSize = rnn.frameSize.coerceAtMost(input.size)
        val samples = audioBuffer.remaining() / BYTES_PER_SAMPLE
        if (samples < frameSize) return

        val pcm = audioBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var offset = 0
        // rnnoise works on ±32768-scaled floats: shorts convert losslessly.
        while (samples - offset >= frameSize) {
            for (i in 0 until frameSize) {
                input[i] = pcm.get(offset + i).toFloat()
            }
            rnn.processFrame(input, output)
            for (i in 0 until frameSize) {
                pcm.put(offset + i, output[i].toInt().coerceIn(SHORT_MIN, SHORT_MAX).toShort())
            }
            offset += frameSize
        }
    }

    /** Called when the owning room is torn down; frees the native state. */
    fun release() {
        releaseDenoiser()
        input = EMPTY_FRAME
        output = EMPTY_FRAME
    }

    private fun releaseDenoiser() {
        try {
            denoiser?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to close RNNoise instance", e)
        }
        denoiser = null
    }

    companion object {
        private const val TAG = "RnNoiseProcessor"
        private const val TARGET_SAMPLE_RATE = 48000
        private const val BYTES_PER_SAMPLE = 2
        private const val SHORT_MIN = -32768
        private const val SHORT_MAX = 32767
        private val EMPTY_FRAME = FloatArray(0)
    }
}
