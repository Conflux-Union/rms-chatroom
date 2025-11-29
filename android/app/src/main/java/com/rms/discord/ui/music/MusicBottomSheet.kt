package com.rms.discord.ui.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.smarttoolfactory.slider.ColorfulSlider
import com.smarttoolfactory.slider.MaterialSliderDefaults
import com.smarttoolfactory.slider.SliderBrushColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.rms.discord.data.model.QueueItem
import com.rms.discord.data.model.Song
import com.rms.discord.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicBottomSheet(
    state: MusicState,
    voiceRoomName: String?,
    voiceConnected: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkip: () -> Unit,
    onSeek: (Long) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onShowSearch: () -> Unit,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onStopBot: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDark)
    ) {
        // Header
        MusicHeader(
            isLoggedIn = state.isLoggedIn,
            botConnected = state.botConnected,
            onLoginClick = onLoginClick,
            onLogoutClick = onLogoutClick,
            onStopBot = onStopBot
        )

        // Content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Now Playing or Empty State
            if (state.currentSong != null) {
                NowPlayingSection(
                    song = state.currentSong,
                    isPlaying = state.isPlaying,
                    playbackState = state.playbackState,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    voiceConnected = voiceConnected,
                    onTogglePlayPause = onTogglePlayPause,
                    onSkip = onSkip,
                    onSeek = onSeek
                )
            } else {
                EmptyPlayingState(onShowSearch = onShowSearch)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Queue Section
            QueueSection(
                queue = state.queue,
                currentIndex = state.currentIndex,
                onRemove = onRemoveFromQueue,
                onClear = onClearQueue,
                onShowSearch = onShowSearch
            )
        }
    }
}

@Composable
private fun MusicHeader(
    isLoggedIn: Boolean,
    botConnected: Boolean,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onStopBot: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDarker
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "音乐播放器",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.weight(1f))

            // Bot status badge
            if (botConnected) {
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onStopBot() },
                    color = TiColor.copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = TiColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "机器人",
                            style = MaterialTheme.typography.labelSmall,
                            color = TiColor
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Login status badge
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { if (isLoggedIn) onLogoutClick() else onLoginClick() },
                color = if (isLoggedIn) VoiceConnected.copy(alpha = 0.2f) 
                        else SurfaceLight
            ) {
                Text(
                    text = if (isLoggedIn) "QQ VIP" else "登录",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLoggedIn) VoiceConnected else TextMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun NowPlayingSection(
    song: Song,
    isPlaying: Boolean,
    playbackState: String,
    positionMs: Long,
    durationMs: Long,
    voiceConnected: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkip: () -> Unit,
    onSeek: (Long) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceLight,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album cover
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(song.cover)
                    .crossfade(true)
                    .build(),
                contentDescription = "Album cover",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Song info and progress
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Progress bar
                ProgressBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeek = onSeek
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Playback controls
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Play/Pause button
                IconButton(
                    onClick = onTogglePlayPause,
                    enabled = voiceConnected || playbackState == "paused",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(TiColor)
                ) {
                    when (playbackState) {
                        "loading" -> CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        else -> Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Skip button
                IconButton(
                    onClick = onSkip,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SurfaceDark)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val progress = if (durationMs > 0) {
        if (isDragging) sliderPosition else positionMs.toFloat() / durationMs
    } else 0f

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(if (isDragging) (sliderPosition * durationMs).toLong() else positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }

        ColorfulSlider(
            value = progress,
            onValueChange = { 
                isDragging = true
                sliderPosition = it 
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek((sliderPosition * durationMs).toLong())
            },
            modifier = Modifier.fillMaxWidth(),
            thumbRadius = 8.dp,
            trackHeight = 6.dp,
            coerceThumbInTrack = true,
            colors = MaterialSliderDefaults.materialColors(
                thumbColor = SliderBrushColor(color = TiColor),
                activeTrackColor = SliderBrushColor(color = TiColor),
                inactiveTrackColor = SliderBrushColor(color = SurfaceDark)
            )
        )
    }
}

@Composable
private fun EmptyPlayingState(onShowSearch: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "暂无播放",
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onShowSearch,
            colors = ButtonDefaults.buttonColors(
                containerColor = TiColor
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("添加歌曲")
        }
    }
}

@Composable
private fun QueueSection(
    queue: List<QueueItem>,
    currentIndex: Int,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onShowSearch: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceLight,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "播放队列 (${queue.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = onShowSearch,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (queue.isNotEmpty()) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "清空",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = SurfaceDark)

            // Queue list
            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "队列为空",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    itemsIndexed(queue) { index, item ->
                        QueueItemRow(
                            item = item,
                            isCurrent = index == currentIndex,
                            onRemove = { onRemove(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemRow(
    item: QueueItem,
    isCurrent: Boolean,
    onRemove: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isCurrent) TiColor.copy(alpha = 0.15f) else Color.Transparent,
        animationSpec = tween(200),
        label = "queueItemBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cover
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.song.cover)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Song info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.song.name,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Duration
        Text(
            text = formatDuration(item.song.duration),
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )

        // Remove button
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "删除",
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// Utility functions
private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000).toInt()
    val mins = seconds / 60
    val secs = seconds % 60
    return "$mins:${secs.toString().padStart(2, '0')}"
}

private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "$mins:${secs.toString().padStart(2, '0')}"
}
