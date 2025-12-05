package cn.net.rms.chatroom.ui.main.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.net.rms.chatroom.data.model.Channel
import cn.net.rms.chatroom.data.model.ChannelType
import cn.net.rms.chatroom.data.model.Server
import cn.net.rms.chatroom.data.model.VoiceUser
import cn.net.rms.chatroom.ui.theme.*

@Composable
fun ChannelListColumn(
    server: Server?,
    currentChannelId: Long?,
    onChannelClick: (Channel) -> Unit,
    username: String,
    onLogout: () -> Unit,
    onSettings: () -> Unit = {},
    voiceChannelUsers: Map<Long, List<VoiceUser>> = emptyMap(),
    isAdmin: Boolean = false,
    onCreateChannel: (name: String, type: String) -> Unit = { _, _ -> },
    onDeleteChannel: (channelId: Long) -> Unit = {}
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var createChannelType by remember { mutableStateOf("text") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var channelToDelete by remember { mutableStateOf<Channel?>(null) }

    Column(
        modifier = Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(SurfaceDark)
    ) {
        // Server header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(SurfaceDark)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = server?.name ?: "选择服务器",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        HorizontalDivider(color = Color(0xFF1E1F22), thickness = 2.dp)

        // Channel list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Text channels section
            val textChannels = server?.channels?.filter { it.type == ChannelType.TEXT } ?: emptyList()
            item {
                ChannelSectionHeader(
                    title = "文字频道",
                    showAddButton = isAdmin,
                    onAddClick = {
                        createChannelType = "text"
                        showCreateDialog = true
                    }
                )
            }
            items(textChannels, key = { it.id }) { channel ->
                ChannelItem(
                    channel = channel,
                    isSelected = channel.id == currentChannelId,
                    onClick = { onChannelClick(channel) },
                    onLongClick = if (isAdmin) {
                        {
                            channelToDelete = channel
                            showDeleteDialog = true
                        }
                    } else null
                )
            }

            // Voice channels section
            val voiceChannels = server?.channels?.filter { it.type == ChannelType.VOICE } ?: emptyList()
            item {
                Spacer(modifier = Modifier.height(16.dp))
                ChannelSectionHeader(
                    title = "语音频道",
                    showAddButton = isAdmin,
                    onAddClick = {
                        createChannelType = "voice"
                        showCreateDialog = true
                    }
                )
            }
            voiceChannels.forEach { channel ->
                val users = voiceChannelUsers[channel.id] ?: emptyList()
                item(key = "voice_${channel.id}") {
                    VoiceChannelItem(
                        channel = channel,
                        isSelected = channel.id == currentChannelId,
                        onClick = { onChannelClick(channel) },
                        onLongClick = if (isAdmin) {
                            {
                                channelToDelete = channel
                                showDeleteDialog = true
                            }
                        } else null,
                        users = users
                    )
                }
            }
        }

        // User panel at bottom
        UserPanel(
            username = username,
            onLogout = onLogout,
            onSettings = onSettings
        )
    }

    // Create Channel Dialog
    if (showCreateDialog) {
        CreateChannelDialog(
            channelType = createChannelType,
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                onCreateChannel(name, createChannelType)
                showCreateDialog = false
            }
        )
    }

    // Delete Channel Dialog
    if (showDeleteDialog && channelToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                channelToDelete = null
            },
            title = { Text("删除频道") },
            text = { Text("确定要删除频道「${channelToDelete?.name}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        channelToDelete?.let { onDeleteChannel(it.id) }
                        showDeleteDialog = false
                        channelToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFED4245))
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    channelToDelete = null
                }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun CreateChannelDialog(
    channelType: String,
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit
) {
    var channelName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建${if (channelType == "text") "文字" else "语音"}频道") },
        text = {
            OutlinedTextField(
                value = channelName,
                onValueChange = { channelName = it },
                label = { Text("频道名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (channelName.isNotBlank()) onCreate(channelName.trim()) },
                enabled = channelName.isNotBlank()
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ChannelSectionHeader(
    title: String,
    showAddButton: Boolean = false,
    onAddClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextMuted,
            modifier = Modifier.weight(1f)
        )
        if (showAddButton) {
            IconButton(
                onClick = onAddClick,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加频道",
                    tint = TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelItem(
    channel: Channel,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) SurfaceLighter else Color.Transparent,
        animationSpec = tween(150),
        label = "channelBg"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) ChannelActive else ChannelDefault,
        animationSpec = tween(150),
        label = "channelText"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (channel.type == ChannelType.TEXT) Icons.Default.Tag else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = textColor
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = channel.name,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceChannelItem(
    channel: Channel,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    users: List<VoiceUser>
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) SurfaceLighter else Color.Transparent,
        animationSpec = tween(150),
        label = "channelBg"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) ChannelActive else ChannelDefault,
        animationSpec = tween(150),
        label = "channelText"
    )

    Column {
        // Channel row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(backgroundColor)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = textColor
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // User count badge
            if (users.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SurfaceLighter
                ) {
                    Text(
                        text = "${users.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // Voice users list
        AnimatedVisibility(
            visible = users.isNotEmpty(),
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(start = 28.dp)
            ) {
                users.forEach { user ->
                    VoiceUserItem(user = user)
                }
            }
        }
    }
}

@Composable
private fun VoiceUserItem(user: VoiceUser) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(TiColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = user.name.take(1).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontSize = 10.sp
            )
        }

        // Host badge
        if (user.isHost) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "主持人",
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(10.dp),
                tint = Color(0xFFF59E0B)
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Username
        Text(
            text = user.name,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Muted indicator
        if (user.isMuted) {
            Icon(
                imageVector = Icons.Default.MicOff,
                contentDescription = "已静音",
                modifier = Modifier.size(12.dp),
                tint = VoiceMuted
            )
        }
    }
}

@Composable
private fun UserPanel(
    username: String,
    onLogout: () -> Unit,
    onSettings: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDarker
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // User avatar placeholder
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TiColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = username.take(1).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Username
            Text(
                text = username,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Settings button
            IconButton(
                onClick = onSettings,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Logout button
            IconButton(
                onClick = onLogout,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = "退出登录",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
