package cn.net.rms.chatroom.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import cn.net.rms.chatroom.ui.theme.InkLight
import cn.net.rms.chatroom.ui.theme.PaperDarkSubtle
import cn.net.rms.chatroom.ui.theme.PaperLightSubtle
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.sin

// Bar geometry traced from res/drawable/ic_splash_logo.xml (432x432 viewport)
// so the first frame of the animation is pixel-identical to the system splash.
private const val VIEWPORT = 432f
private const val BAR_WIDTH = 18f
private const val BAR_RADIUS = 9f
private val BAR_LEFT_X = listOf(135f, 163f, 192f, 220f, 249f, 277f)
private val BAR_TOP_Y = listOf(201f, 165f, 135f, 177f, 153f, 193f)
private val BAR_HEIGHT = listOf(30f, 100f, 160f, 76f, 124f, 44f)
private val BAR_CENTER_Y = BAR_TOP_Y.mapIndexed { i, y -> y + BAR_HEIGHT[i] / 2f }

// Fixed per-bar equalizer constants (no runtime randomness) so the bounce
// reads as a sound spectrum and stays stable across recompositions.
private val BAR_AMPLITUDE = listOf(0.32f, 0.26f, 0.36f, 0.24f, 0.30f, 0.27f)
private val BAR_PERIOD = listOf(1.05f, 0.82f, 1.28f, 0.94f, 1.16f, 0.78f)
private val BAR_PHASE = listOf(0.0f, 1.1f, 2.2f, 3.3f, 4.4f, 5.5f)
private const val HARMONIC = 0.06f
private const val TWO_PI = 6.2831855f

private const val RHYTHM_RAMP_SECONDS = 0.6f
private const val MIN_RHYTHM_SECONDS = 1.2f
private const val SETTLE_STAGGER_MS = 45f
private const val SETTLE_BAR_MS = 550f
private const val HOLD_MS = 500L
private const val COLLAPSE_STAGGER_MS = 40f
private const val COLLAPSE_BAR_MS = 300f
private const val FADE_MS = 350f

/**
 * Animated splash: the soundwave logo plays an equalizer-style rhythm while
 * auth restores, springs back into the exact static logo, holds briefly,
 * collapses bar by bar, then fades out to reveal the screen composed beneath.
 *
 * The paper and ink follow [darkTheme]: dark paper with white bars at night,
 * light paper with ink bars by day, matching the day-night window/splash
 * backgrounds so cold start, system splash and this overlay read as one
 * continuous surface.
 *
 * [settled] flips true once the startup auth state has resolved; [onExitFinished]
 * is invoked after the fade completes so the caller can drop this overlay.
 */
@Composable
fun SplashContent(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = true,
    settled: Boolean = false,
    onExitFinished: (() -> Unit)? = null,
) {
    val scales = remember { mutableStateListOf(1f, 1f, 1f, 1f, 1f, 1f) }
    val layerAlpha = remember { mutableStateOf(1f) }
    val settledState by rememberUpdatedState(settled)
    val exitCallback by rememberUpdatedState(onExitFinished)

    LaunchedEffect(Unit) {
        val rhythmStart = withFrameNanos { it }

        // Rhythm: per-bar sinusoid bounce; the amplitude envelope keeps the
        // first frame at the static logo, then ramps the motion in.
        var rhythmDone = false
        while (!rhythmDone) {
            withFrameNanos { now ->
                val t = (now - rhythmStart) / 1_000_000_000f
                val envelope = smoothstep(0f, RHYTHM_RAMP_SECONDS, t)
                for (i in BAR_LEFT_X.indices) {
                    val phase = TWO_PI / BAR_PERIOD[i] * t + BAR_PHASE[i]
                    scales[i] = 1f + envelope *
                        (BAR_AMPLITUDE[i] * sin(phase) + HARMONIC * sin(2f * phase))
                }
                rhythmDone = settledState && t >= MIN_RHYTHM_SECONDS
            }
        }

        // Settle: bars ease back with a slight overshoot to the canonical logo
        // heights, left to right.
        val settleFrom = scales.toList()
        val settleStart = withFrameNanos { it }
        var settleDone = false
        while (!settleDone) {
            withFrameNanos { now ->
                val tMs = (now - settleStart) / 1_000_000f
                settleDone = true
                for (i in BAR_LEFT_X.indices) {
                    val p = ((tMs - i * SETTLE_STAGGER_MS) / SETTLE_BAR_MS).coerceIn(0f, 1f)
                    if (p < 1f) settleDone = false
                    val from = settleFrom[i]
                    scales[i] = from + (1f - from) * easeOutBack(p)
                }
            }
        }

        delay(HOLD_MS)

        // Collapse: every bar shrinks to nothing around its own center.
        val collapseStart = withFrameNanos { it }
        var collapseDone = false
        while (!collapseDone) {
            withFrameNanos { now ->
                val tMs = (now - collapseStart) / 1_000_000f
                collapseDone = true
                for (i in BAR_LEFT_X.indices) {
                    val p = ((tMs - i * COLLAPSE_STAGGER_MS) / COLLAPSE_BAR_MS).coerceIn(0f, 1f)
                    if (p < 1f) collapseDone = false
                    scales[i] = 1f - FastOutSlowInEasing.transform(p)
                }
            }
        }

        // Fade the whole splash layer out to reveal the screen beneath.
        val fadeStart = withFrameNanos { it }
        var fadeDone = false
        while (!fadeDone) {
            withFrameNanos { now ->
                val p = ((now - fadeStart) / 1_000_000f / FADE_MS).coerceIn(0f, 1f)
                fadeDone = p >= 1f
                layerAlpha.value = 1f - p
            }
        }

        exitCallback?.invoke()
    }

    val splashInk = if (darkTheme) Color.White else InkLight

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = layerAlpha.value }
            .background(if (darkTheme) PaperDarkSubtle else PaperLightSubtle)
    ) {
        Canvas(
            modifier = Modifier
                .align(Alignment.Center)
                .size(288.dp)
        ) {
            val s = size.width / VIEWPORT
            for (i in BAR_LEFT_X.indices) {
                val heightPx = BAR_HEIGHT[i] * scales[i].coerceAtLeast(0f) * s
                if (heightPx < 0.5f) continue
                drawRoundRect(
                    color = splashInk,
                    topLeft = Offset(
                        BAR_LEFT_X[i] * s,
                        BAR_CENTER_Y[i] * s - heightPx / 2f
                    ),
                    size = Size(BAR_WIDTH * s, heightPx),
                    cornerRadius = CornerRadius(min(BAR_RADIUS * s, heightPx / 2f))
                )
            }
        }
    }
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val x = t - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}
