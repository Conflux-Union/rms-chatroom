package com.rms.discord.ui.voice

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.rms.discord.R
import com.rms.discord.data.livekit.ConnectionState
import com.rms.discord.data.livekit.ParticipantInfo
import com.rms.discord.ui.music.MusicBottomSheet
import com.rms.discord.ui.music.MusicLoginDialog
import com.rms.discord.ui.music.MusicSearchDialog
import com.rms.discord.ui.music.MusicViewModel
import com.rms.discord.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    channelId: Long,
    channelName: String = "",
    viewModel: VoiceViewModel = hiltViewModel(),
    musicViewModel: MusicViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val musicState by musicViewModel.state.collectAsState()
    val context = LocalContext.current
    
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }
    var showMusicPanel by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showLoginDialog by remember { mutableStateOf(false) }
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
            viewModel.joinVoice()
        } else {
            showPermissionDeniedDialog = true
        }
    }

    val voiceRoomName = remember(channelId) { "voice_$channelId" }
    
    // Set current room for music queue
    LaunchedEffect(voiceRoomName) {
        musicViewModel.setCurrentRoom(voiceRoomName)
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

    // Music search dialog
    if (showSearchDialog) {
        MusicSearchDialog(
            searchResults = musicState.searchResults,
            isSearching = musicState.isSearching,
            onSearch = { musicViewModel.search(it) },
            onAddSong = { song ->
                musicViewModel.addToQueue(song)
                musicViewModel.clearSearchResults()
            },
            onDismiss = { 
                showSearchDialog = false
                musicViewModel.clearSearchResults()
            }
        )
    }

    // Music login dialog
    if (showLoginDialog || musicState.qrCodeUrl != null) {
        MusicLoginDialog(
            qrCodeUrl = musicState.qrCodeUrl,
            loginStatus = musicState.loginStatus,
            onRefreshQRCode = { musicViewModel.getQRCode() },
            onDismiss = {
                showLoginDialog = false
                musicViewModel.dismissQRCode()
            }
        )
    }

    LaunchedEffect(channelId) {
        viewModel.setChannelId(channelId, channelName)
    }
    
    // Request permission on join
    val onJoinWithPermission: () -> Unit = {
        if (hasAudioPermission) {
            viewModel.joinVoice()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Music bottom sheet
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    if (showMusicPanel) {
        ModalBottomSheet(
            onDismissRequest = { showMusicPanel = false },
            sheetState = sheetState,
            containerColor = SurfaceDark,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            MusicBottomSheet(
                state = musicState,
                voiceRoomName = voiceRoomName,
                voiceConnected = state.isConnected,
                onTogglePlayPause = { musicViewModel.togglePlayPause(voiceRoomName) },
                onSkip = { musicViewModel.botSkip() },
                onSeek = { musicViewModel.botSeek(it) },
                onRemoveFromQueue = { musicViewModel.removeFromQueue(it) },
                onClearQueue = { musicViewModel.clearQueue() },
                onShowSearch = { showSearchDialog = true },
                onLoginClick = { 
                    showLoginDialog = true
                    musicViewModel.getQRCode()
                },
                onLogoutClick = { musicViewModel.logout() },
                onStopBot = { musicViewModel.stopBot() }
            )
        }
    }

    Scaffold(
        floatingActionButton = {
            // Music FAB - only show when connected to voice
            if (state.isConnected) {
                FloatingActionButton(
                    onClick = { showMusicPanel = true },
                    containerColor = TiColor
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "音乐",
                        tint = Color.White
                    )
                }
            }
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Connection status banner
            ConnectionStatusBanner(
                connectionState = state.connectionState,
                error = state.error,
                onDismissError = { viewModel.clearError() }
            )

            // Voice users grid
            if (state.participants.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(state.participants, key = { it.identity }) { participant ->
                        VoiceUserItem(participant = participant)
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
                isSpeakerOn = state.isSpeakerOn,
                isLoading = state.isLoading,
                onJoin = onJoinWithPermission,
                onLeave = { viewModel.leaveVoice() },
                onToggleMute = { viewModel.toggleMute() },
                onToggleDeafen = { viewModel.toggleDeafen() },
                onToggleSpeaker = { viewModel.toggleSpeaker() }
            )
        }
    }
}

@Composable
private fun ConnectionStatusBanner(
    connectionState: ConnectionState,
    error: String?,
    onDismissError: () -> Unit
) {
    // Error banner
    error?.let {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
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
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = DiscordRed,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = DiscordRed,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    // Reconnecting banner
    if (connectionState == ConnectionState.RECONNECTING) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            color = DiscordYellow.copy(alpha = 0.2f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = DiscordYellow,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "正在重新连接...",
                    style = MaterialTheme.typography.bodySmall,
                    color = DiscordYellow
                )
            }
        }
    }
}

@Composable
private fun VoiceUserItem(participant: ParticipantInfo) {
    // Speaking animation
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
        // Avatar with status border and speaking indicator
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

        // Username
        Text(
            text = participant.name,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            maxLines = 1,
            textAlign = TextAlign.Center
        )

        // Status icons
        if (participant.isMuted) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    imageVector = Icons.Default.MicOff,
                    contentDescription = "静音",
                    modifier = Modifier.size(14.dp),
                    tint = VoiceMuted
                )
            }
        }
    }
}

@Composable
private fun VoiceControls(
    isConnected: Boolean,
    isMuted: Boolean,
    isDeafened: Boolean,
    isSpeakerOn: Boolean,
    isLoading: Boolean,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onToggleSpeaker: () -> Unit
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
                    icon = if (isDeafened) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    label = if (isDeafened) stringResource(R.string.undeafen) else stringResource(R.string.deafen),
                    isActive = isDeafened,
                    activeColor = VoiceMuted,
                    onClick = onToggleDeafen
                )

                // Speaker toggle button
                VoiceControlButton(
                    icon = if (isSpeakerOn) Icons.Default.Speaker else Icons.Default.PhoneAndroid,
                    label = if (isSpeakerOn) "扬声器" else "听筒",
                    isActive = isSpeakerOn,
                    activeColor = VoiceConnected,
                    onClick = onToggleSpeaker
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
