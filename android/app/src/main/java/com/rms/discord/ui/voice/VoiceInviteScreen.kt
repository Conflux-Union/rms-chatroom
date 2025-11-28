package com.rms.discord.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.rms.discord.R
import com.rms.discord.data.livekit.ConnectionState
import com.rms.discord.data.livekit.ParticipantInfo
import com.rms.discord.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceInviteScreen(
    inviteToken: String,
    onNavigateBack: () -> Unit,
    viewModel: VoiceInviteViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var showPermissionDeniedDialog by remember { mutableStateOf(false) }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        if (granted) {
            viewModel.joinAsGuest()
        } else {
            showPermissionDeniedDialog = true
        }
    }

    // Permission denied dialog
    if (showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            title = { Text("需要麦克风权限") },
            text = { Text("加入语音通话需要麦克风权限。请在系统设置中授予权限。") },
            confirmButton = {
                TextButton(onClick = { showPermissionDeniedDialog = false }) {
                    Text("确定")
                }
            }
        )
    }

    LaunchedEffect(inviteToken) {
        viewModel.setInviteToken(inviteToken)
    }

    val onJoinWithPermission: () -> Unit = {
        if (hasAudioPermission) {
            viewModel.joinAsGuest()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            if (!state.isConnected) {
                TopAppBar(
                    title = { Text("语音邀请") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = SurfaceDark
                    )
                )
            }
        },
        containerColor = SurfaceDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                state.isLoadingInfo -> {
                    // Loading state
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = TiColor)
                    }
                }
                !state.isValidInvite && state.inviteInfo != null -> {
                    // Invalid invite
                    InvalidInviteContent(
                        onNavigateBack = onNavigateBack
                    )
                }
                state.isConnected -> {
                    // Connected - show voice room
                    VoiceRoomContent(
                        state = state,
                        onToggleMute = { viewModel.toggleMute() },
                        onToggleDeafen = { viewModel.toggleDeafen() },
                        onLeave = {
                            viewModel.leaveVoice()
                            onNavigateBack()
                        }
                    )
                }
                else -> {
                    // Join form
                    JoinFormContent(
                        state = state,
                        onUsernameChange = { viewModel.setUsername(it) },
                        onJoin = onJoinWithPermission,
                        onClearError = { viewModel.clearError() }
                    )
                }
            }
        }
    }
}

@Composable
private fun InvalidInviteContent(
    onNavigateBack: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.LinkOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = DiscordRed
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "邀请链接无效",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "该邀请链接可能已过期或已被使用",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onNavigateBack,
                colors = ButtonDefaults.buttonColors(containerColor = TiColor)
            ) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun JoinFormContent(
    state: VoiceInviteState,
    onUsernameChange: (String) -> Unit,
    onJoin: () -> Unit,
    onClearError: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Invite info card
        state.inviteInfo?.let { info ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SurfaceLight,
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = VoiceConnected
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "语音邀请",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    info.serverName?.let { serverName ->
                        Text(
                            text = serverName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    info.channelName?.let { channelName ->
                        Text(
                            text = "# $channelName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Error message
        state.error?.let { error ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DiscordRed.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = DiscordRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = DiscordRed,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClearError, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = DiscordRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Username input
        OutlinedTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = { Text("输入你的名字") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TiColor,
                unfocusedBorderColor = TextMuted,
                focusedLabelColor = TiColor,
                cursorColor = TiColor
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onJoin() })
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Join button
        Button(
            onClick = onJoin,
            enabled = !state.isJoining && state.username.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = VoiceConnected),
            shape = RoundedCornerShape(8.dp)
        ) {
            if (state.isJoining) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("加入语音")
            }
        }
    }
}

@Composable
private fun VoiceRoomContent(
    state: VoiceInviteState,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onLeave: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = state.inviteInfo?.channelName?.let { "# $it" } ?: "语音通话",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Participants grid
        if (state.participants.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                items(state.participants, key = { it.identity }) { participant ->
                    GuestVoiceUserItem(participant = participant)
                }
            }
        } else {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = TextMuted
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "等待其他人加入...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextMuted
                    )
                }
            }
        }

        // Controls
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
                // Mute button
                GuestVoiceControlButton(
                    icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (state.isMuted) stringResource(R.string.unmute) else stringResource(R.string.mute),
                    isActive = state.isMuted,
                    activeColor = VoiceMuted,
                    onClick = onToggleMute
                )

                // Deafen button
                GuestVoiceControlButton(
                    icon = if (state.isDeafened) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    label = if (state.isDeafened) stringResource(R.string.undeafen) else stringResource(R.string.deafen),
                    isActive = state.isDeafened,
                    activeColor = VoiceMuted,
                    onClick = onToggleDeafen
                )

                // Leave button
                GuestVoiceControlButton(
                    icon = Icons.Default.CallEnd,
                    label = stringResource(R.string.leave_voice),
                    isActive = true,
                    activeColor = DiscordRed,
                    onClick = onLeave
                )
            }
        }
    }
}

@Composable
private fun GuestVoiceUserItem(participant: ParticipantInfo) {
    val infiniteTransition = rememberInfiniteTransition(label = "speaking")
    val speakingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(300),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speakingScale"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            participant.isSpeaking -> VoiceSpeaking
            participant.isMuted -> TextMuted
            else -> VoiceConnected
        },
        animationSpec = tween(200),
        label = "borderColor"
    )

    val avatarScale = if (participant.isSpeaking) speakingScale else 1f

    Column(
        modifier = Modifier
            .width(100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceLight)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .scale(avatarScale)
                .clip(CircleShape)
                .background(borderColor.copy(alpha = 0.3f))
                .then(
                    if (participant.isSpeaking) {
                        Modifier.border(2.dp, VoiceSpeaking, CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(TiColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = participant.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = participant.name,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            maxLines = 1,
            textAlign = TextAlign.Center
        )

        if (participant.isMuted) {
            Spacer(modifier = Modifier.height(4.dp))
            Icon(
                imageVector = Icons.Default.MicOff,
                contentDescription = "静音",
                modifier = Modifier.size(14.dp),
                tint = VoiceMuted
            )
        }
    }
}

@Composable
private fun GuestVoiceControlButton(
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

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
