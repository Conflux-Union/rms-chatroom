package com.rms.discord.ui.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rms.discord.R
import com.rms.discord.data.model.VoiceUser
import com.rms.discord.ui.theme.*

@Composable
fun VoiceScreen(
    channelId: Long,
    viewModel: VoiceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(channelId) {
        viewModel.setChannelId(channelId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Voice users grid
        if (state.voiceUsers.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(state.voiceUsers, key = { it.id }) { user ->
                    VoiceUserItem(user = user)
                }
            }
        } else {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = TextMuted
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (state.isConnected) "等待其他人加入..." else "点击下方按钮加入语音",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Voice controls
        VoiceControls(
            isConnected = state.isConnected,
            isMuted = state.isMuted,
            isDeafened = state.isDeafened,
            isLoading = state.isLoading,
            onJoin = { viewModel.joinVoice() },
            onLeave = { viewModel.leaveVoice() },
            onToggleMute = { viewModel.toggleMute() },
            onToggleDeafen = { viewModel.toggleDeafen() }
        )
    }
}

@Composable
private fun VoiceUserItem(user: VoiceUser) {
    val borderColor by animateColorAsState(
        targetValue = when {
            user.deafened -> VoiceMuted
            user.muted -> TextMuted
            else -> VoiceConnected
        },
        animationSpec = tween(200),
        label = "borderColor"
    )

    Column(
        modifier = Modifier
            .width(100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceLight)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar with status border
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(borderColor.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(DiscordBlurple),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.username.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Username
        Text(
            text = user.username,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            maxLines = 1,
            textAlign = TextAlign.Center
        )

        // Status icons
        if (user.muted || user.deafened) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (user.muted) {
                    Icon(
                        imageVector = Icons.Default.MicOff,
                        contentDescription = "静音",
                        modifier = Modifier.size(14.dp),
                        tint = VoiceMuted
                    )
                }
                if (user.deafened) {
                    Icon(
                        imageVector = Icons.Default.VolumeOff,
                        contentDescription = "耳机关闭",
                        modifier = Modifier.size(14.dp),
                        tint = VoiceMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceControls(
    isConnected: Boolean,
    isMuted: Boolean,
    isDeafened: Boolean,
    isLoading: Boolean,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDarker,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isConnected) {
                // Mute button
                VoiceControlButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (isMuted) stringResource(R.string.unmute) else stringResource(R.string.mute),
                    isActive = isMuted,
                    activeColor = VoiceMuted,
                    onClick = onToggleMute
                )

                // Deafen button
                VoiceControlButton(
                    icon = if (isDeafened) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    label = if (isDeafened) stringResource(R.string.undeafen) else stringResource(R.string.deafen),
                    isActive = isDeafened,
                    activeColor = VoiceMuted,
                    onClick = onToggleDeafen
                )

                // Leave button
                VoiceControlButton(
                    icon = Icons.Default.CallEnd,
                    label = stringResource(R.string.leave_voice),
                    isActive = true,
                    activeColor = DiscordRed,
                    onClick = onLeave
                )
            } else {
                // Join button
                Button(
                    onClick = onJoin,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoiceConnected
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.join_voice))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isActive) activeColor else SurfaceLight,
        animationSpec = tween(200),
        label = "controlBg"
    )

    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.95f,
        animationSpec = tween(100),
        label = "controlScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(backgroundColor)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
}
