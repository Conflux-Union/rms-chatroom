package cn.net.rms.chatroom.data.livekit

import android.util.Log

/**
 * Thin JNI wrapper over xiph rnnoise (vendored under src/main/cpp/rnnoise).
 * Frames are mono floats at 48 kHz in rnnoise's native ±32768 scaling;
 * 480 samples per frame (10 ms).
 */
internal object RnNoiseNative {
    init {
        System.loadLibrary("rnnoise")
    }

    external fun create(): Long
    external fun destroy(ptr: Long)
    external fun frameSize(): Int
    external fun processFrame(ptr: Long, input: FloatArray, output: FloatArray): Float
}

class RnNoise private constructor(private val nativePtr: Long) : AutoCloseable {

    private var closed = false

    val frameSize: Int = RnNoiseNative.frameSize()

    /**
     * Denoise one frame. [input] holds ±32768-scaled samples; the denoised
     * frame is written to [output]. Returns the voice-activity probability.
     */
    fun processFrame(input: FloatArray, output: FloatArray): Float {
        check(!closed) { "RnNoise is closed" }
        return RnNoiseNative.processFrame(nativePtr, input, output)
    }

    override fun close() {
        if (!closed) {
            closed = true
            RnNoiseNative.destroy(nativePtr)
        }
    }

    companion object {
        private const val TAG = "RnNoise"

        /** Returns null when the native library cannot be loaded or initialized. */
        fun create(): RnNoise? {
            return try {
                val ptr = RnNoiseNative.create()
                if (ptr != 0L) RnNoise(ptr) else null
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to create RNNoise instance", e)
                null
            }
        }
    }
}
