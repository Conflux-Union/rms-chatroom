package cn.net.rms.chatroom.ui.music

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.data.manager.MusicState
import cn.net.rms.chatroom.ui.theme.Zhimo
import cn.net.rms.chatroom.util.LrcParser
import kotlin.math.roundToInt

private const val DRAG_PAUSE_MS = 4000L
private const val PREFS_NAME = "music_prefs"

/**
 * Floating, draggable lyrics window overlaid on the main content. Mirrors the
 * web MusicLyricsWindow: drag by the header, active line highlighted and kept
 * centered (manual scrolling pauses auto-follow), translation toggle. Window
 * visibility is manager state; position persists in the music prefs.
 */
@Composable
fun MusicLyricsWindow(
    state: MusicState,
    onClose: () -> Unit,
    onToggleTranslation: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.lyricsWindowVisible) return

    // Transparent host: without a background or pointer modifier it lets
    // touches pass through everywhere except the window surface itself.
    Box(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints {
            val context = LocalContext.current
            val density = LocalDensity.current
            val windowWidth = minOf(300.dp, maxWidth - 16.dp)
            val windowHeight = minOf(420.dp, maxHeight * 0.55f)
            val maxXPx = with(density) { (maxWidth - windowWidth).toPx() }.coerceAtLeast(0f)
            val maxYPx = with(density) { (maxHeight - windowHeight).toPx() }.coerceAtLeast(0f)

            val savedPos = remember {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val x = prefs.getFloat("lyrics_window_x", -1f)
                val y = prefs.getFloat("lyrics_window_y", -1f)
                // Default to the bottom-end corner on first launch.
                (if (x >= 0) x.coerceIn(0f, maxXPx) else maxXPx) to
                    (if (y >= 0) y.coerceIn(0f, maxYPx) else (maxYPx - with(density) { 16.dp.toPx() }).coerceAtLeast(0f))
            }
            var posX by remember { mutableFloatStateOf(savedPos.first) }
            var posY by remember { mutableFloatStateOf(savedPos.second) }

            val activeIndex = LrcParser.activeIndex(state.lyrics, state.positionMs)
            val hasTranslation = state.lyrics.any { !it.translation.isNullOrEmpty() }

            Surface(
                modifier = Modifier
                    .offset { IntOffset(posX.roundToInt(), posY.roundToInt()) }
                    .size(windowWidth, windowHeight),
                color = Zhimo.paper,
                contentColor = Zhimo.ink,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, Zhimo.borderStrong)
            ) {
                Column {
                    LyricsWindowHeader(
                        title = state.currentSong?.name
                            ?: stringResource(R.string.music_lyrics),
                        hasTranslation = hasTranslation,
                        translationVisible = state.lyricsTranslationVisible,
                        onToggleTranslation = onToggleTranslation,
                        onClose = onClose,
                        onDrag = { dx, dy ->
                            posX = (posX + dx).coerceIn(0f, maxXPx)
                            posY = (posY + dy).coerceIn(0f, maxYPx)
                        },
                        onDragEnd = {
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putFloat("lyrics_window_x", posX)
                                .putFloat("lyrics_window_y", posY)
                                .apply()
                        }
                    )

                    if (state.lyricsStatus == "ready") {
                        LyricsList(
                            state = state,
                            activeIndex = activeIndex,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        LyricsStatusMessage(
                            status = state.lyricsStatus,
                            hasSong = state.currentSong != null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsWindowHeader(
    title: String,
    hasTranslation: Boolean,
    translationVisible: Boolean,
    onToggleTranslation: (Boolean) -> Unit,
    onClose: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                )
            }
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Zhimo.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (hasTranslation) {
            IconButton(onClick = { onToggleTranslation(!translationVisible) }) {
                Icon(
                    imageVector = Icons.Outlined.Translate,
                    contentDescription = stringResource(R.string.music_lyrics_translation),
                    tint = if (translationVisible) Zhimo.seal else Zhimo.inkFaint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.action_close),
                tint = Zhimo.inkFaint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun LyricsList(
    state: MusicState,
    activeIndex: Int,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var lastUserScrollMs by remember { mutableLongStateOf(0L) }

    // Touch drags pause auto-follow briefly so the user can look around;
    // programmatic scrolls don't surface as list interactions.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                lastUserScrollMs = System.currentTimeMillis()
            }
        }
    }

    // New song (or window just opened): land directly on the active line.
    LaunchedEffect(state.lyrics) {
        lastUserScrollMs = 0L
        if (activeIndex >= 0) centerOn(listState, activeIndex, density.density, animate = false)
    }

    LaunchedEffect(activeIndex) {
        if (activeIndex < 0) return@LaunchedEffect
        val idle = System.currentTimeMillis() - lastUserScrollMs >= DRAG_PAUSE_MS
        if (idle && !listState.isScrollInProgress) {
            centerOn(listState, activeIndex, density.density, animate = true)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth()
    ) {
        itemsIndexed(state.lyrics) { index, line ->
            val active = index == activeIndex
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else null,
                    color = if (active) Zhimo.seal else Zhimo.inkMuted,
                    textAlign = TextAlign.Center
                )
                if (state.lyricsTranslationVisible && !line.translation.isNullOrEmpty()) {
                    Text(
                        text = line.translation,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) Zhimo.seal.copy(alpha = 0.8f) else Zhimo.inkFaint,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

// Centers the item by placing its (estimated) middle at the viewport middle;
// item heights vary with translation lines, so this stays an approximation.
private suspend fun centerOn(listState: LazyListState, index: Int, density: Float, animate: Boolean) {
    val viewportPx = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
    val itemPx = 48.dp.value * density
    val offset = -((viewportPx - itemPx) / 2).toInt()
    if (animate) {
        listState.animateScrollToItem(index, offset)
    } else {
        listState.scrollToItem(index, offset)
    }
}

@Composable
private fun LyricsStatusMessage(
    status: String,
    hasSong: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            "loading" -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Zhimo.seal,
                strokeWidth = 2.dp
            )
            "error" -> Text(
                text = stringResource(R.string.music_lyrics_load_failed),
                style = MaterialTheme.typography.bodySmall,
                color = Zhimo.inkFaint
            )
            else -> Text(
                text = if (hasSong) stringResource(R.string.music_lyrics_empty)
                else stringResource(R.string.music_lyrics_not_playing),
                style = MaterialTheme.typography.bodySmall,
                color = Zhimo.inkFaint
            )
        }
    }
}
