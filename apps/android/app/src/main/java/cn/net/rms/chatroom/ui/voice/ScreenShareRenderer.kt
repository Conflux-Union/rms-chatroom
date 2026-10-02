package cn.net.rms.chatroom.ui.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.livekit.android.renderer.TextureViewRenderer

/**
 * [TextureViewRenderer] that reports the rotated frame dimensions so the
 * share container can be sized to the video's real aspect ratio instead of a
 * hardcoded 16:9 (portrait phone shares previously letterboxed into a strip).
 *
 * Must still be initialized through
 * [io.livekit.android.room.Room.initVideoRenderer] to join the room's shared
 * EGL context — hardware-decoded frames are GPU textures a private EglBase
 * cannot sample.
 */
class ScreenShareRenderer(context: Context) : TextureViewRenderer(context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Rotated frame dimensions, delivered on the main thread. */
    var onVideoDimensionsChanged: ((width: Int, height: Int) -> Unit)? = null

    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
        super.onFrameResolutionChanged(videoWidth, videoHeight, rotation)
        val upright = rotation % 180 == 0
        val width = if (upright) videoWidth else videoHeight
        val height = if (upright) videoHeight else videoWidth
        if (width > 0 && height > 0) {
            // The EglRenderer invokes this on its render thread.
            mainHandler.post { onVideoDimensionsChanged?.invoke(width, height) }
        }
    }
}
