package cn.net.rms.chatroom.ui.main.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.data.api.ReorderTopLevelItem
import cn.net.rms.chatroom.data.model.Channel
import cn.net.rms.chatroom.data.model.ChannelGroup
import cn.net.rms.chatroom.data.model.ChannelType
import cn.net.rms.chatroom.data.model.Server
import cn.net.rms.chatroom.data.model.VoiceUser
import cn.net.rms.chatroom.ui.theme.*
import coil.compose.AsyncImage
import coil.request.ImageRequest

// Sealed class for mixed list items (groups + ungrouped channels)
sealed class ChannelListItem {
    data class GroupItem(val group: ChannelGroup) : ChannelListItem()
    data class UngroupedChannel(val channel: Channel) : ChannelListItem()
}

@Composable
fun ChannelListColumn(
    server: Server?,
    channelGroups: List<ChannelGroup>,
    currentChannelId: Long?,
    onChannelClick: (Channel) -> Unit,
    username: String,
    avatarUrl: String? = null,
    onLogout: () -> Unit,
    onSettings: () -> Unit = {},
    voiceChannelUsers: Map<Long, List<VoiceUser>> = emptyMap(),
    isAdmin: Boolean = false,
    editMode: Boolean = false,
    onToggleEditMode: () -> Unit = {},
    onCreateChannel: (name: String, type: String, groupId: Long?) -> Unit = { _, _, _ -> },
    onDeleteChannel: (channelId: Long) -> Unit = {},
    onCreateChannelGroup: (name: String) -> Unit = {},
    onDeleteChannelGroup: (groupId: Long) -> Unit = {},
    onReorderTopLevel: (List<ReorderTopLevelItem>) -> Unit = {},
    onReorderGroupChannels: (groupId: Long, channelIds: List<Long>) -> Unit = { _, _ -> },
    // Voice status widget parameters
    isVoiceConnected: Boolean = false,
    voiceChannelName: String? = null,
    isVoiceMuted: Boolean = false,
    onToggleMute: () -> Unit = {},
    onDisconnectVoice: () -> Unit = {},
    // Mention notification parameters
    channelMentions: Set<Long> = emptySet(),
    unreadCounts: Map<Long, Int> = emptyMap()
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var createChannelType by remember { mutableStateOf("text") }
    var createChannelGroupId by remember { mutableStateOf<Long?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var channelToDelete by remember { mutableStateOf<Channel?>(null) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showDeleteGroupDialog by remember { mutableStateOf(false) }
    var groupToDelete by remember { mutableStateOf<ChannelGroup?>(null) }
    
    // Collapsed groups state
    val collapsedGroups = remember { mutableStateMapOf<Long, Boolean>() }
    
    // Build mixed list: groups + ungrouped channels, sorted by position
    val mixedList = remember(server, channelGroups) {
        val items = mutableListOf<ChannelListItem>()
        
        // Add groups
        channelGroups.forEach { group ->
            items.add(ChannelListItem.GroupItem(group))
        }
        
        // Add ungrouped channels
        server?.channels?.filter { it.groupId == null }?.forEach { channel ->
            items.add(ChannelListItem.UngroupedChannel(channel))
        }
        
        // Sort by position (groups use position, ungrouped channels use topPosition)
        items.sortedBy { item ->
            when (item) {
                is ChannelListItem.GroupItem -> item.group.position
                is ChannelListItem.UngroupedChannel -> item.channel.topPosition
            }
        }
    }
    
    // Draggable list state for top-level reordering
    var draggableList by remember(mixedList) { mutableStateOf(mixedList) }
    
    // Update draggableList when mixedList changes (but not during drag)
    LaunchedEffect(mixedList) {
        draggableList = mixedList
    }

    Column(
        modifier = Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(Zhimo.paperSubtle)
    ) {
        // Server header with edit button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Zhimo.paperSubtle)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = server?.name ?: stringResource(R.string.channel_list_select_server),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Zhimo.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Edit mode toggle button (admin only)
            if (isAdmin) {
                IconButton(
                    onClick = onToggleEditMode,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (editMode) Icons.Default.Check else Icons.Default.Edit,
                        contentDescription = if (editMode) stringResource(R.string.action_done_editing) else stringResource(R.string.action_edit_channels),
                        tint = if (editMode) Zhimo.seal else Zhimo.inkFaint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = Zhimo.paperSubtle, thickness = 2.dp)

        // Channel list with groups
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Add group button in edit mode
            if (editMode && isAdmin) {
                item {
                    AddGroupButton(onClick = { showCreateGroupDialog = true })
                }
            }
            
            // Render mixed list (groups + ungrouped channels)
            draggableList.forEachIndexed { index, item ->
                when (item) {
                    is ChannelListItem.GroupItem -> {
                        val group = item.group
                        val isCollapsed = collapsedGroups[group.id] ?: false
                        val groupChannels = server?.channels
                            ?.filter { it.groupId == group.id }
                            ?.sortedBy { it.position }
                            ?: emptyList()
                        
                        item(key = "group_${group.id}") {
                            ChannelGroupHeader(
                                group = group,
                                isCollapsed = isCollapsed,
                                onToggleCollapse = { 
                                    collapsedGroups[group.id] = !isCollapsed 
                                },
                                editMode = editMode,
                                isAdmin = isAdmin,
                                onAddChannel = {
                                    createChannelGroupId = group.id
                                    createChannelType = "text"
                                    showCreateDialog = true
                                },
                                onDeleteGroup = {
                                    groupToDelete = group
                                    showDeleteGroupDialog = true
                                },
                                onMoveUp = if (index > 0) {
                                    {
                                        val newList = draggableList.toMutableList()
                                        val temp = newList[index]
                                        newList[index] = newList[index - 1]
                                        newList[index - 1] = temp
                                        draggableList = newList
                                        submitTopLevelReorder(newList, onReorderTopLevel)
                                    }
                                } else null,
                                onMoveDown = if (index < draggableList.size - 1) {
                                    {
                                        val newList = draggableList.toMutableList()
                                        val temp = newList[index]
                                        newList[index] = newList[index + 1]
                                        newList[index + 1] = temp
                                        draggableList = newList
                                        submitTopLevelReorder(newList, onReorderTopLevel)
                                    }
                                } else null
                            )
                        }
                        
                        // Group channels (collapsible)
                        if (!isCollapsed) {
                            groupChannels.forEachIndexed { channelIndex, channel ->
                                item(key = "channel_${channel.id}") {
                                    GroupedChannelItem(
                                        channel = channel,
                                        isSelected = channel.id == currentChannelId,
                                        onClick = { onChannelClick(channel) },
                                        onLongClick = if (isAdmin) {
                                            {
                                                channelToDelete = channel
                                                showDeleteDialog = true
                                            }
                                        } else null,
                                        voiceUsers = voiceChannelUsers[channel.id] ?: emptyList(),
                                        editMode = editMode,
                                        onMoveUp = if (editMode && channelIndex > 0) {
                                            {
                                                val newOrder = groupChannels.toMutableList()
                                                val temp = newOrder[channelIndex]
                                                newOrder[channelIndex] = newOrder[channelIndex - 1]
                                                newOrder[channelIndex - 1] = temp
                                                onReorderGroupChannels(group.id, newOrder.map { it.id })
                                            }
                                        } else null,
                                        onMoveDown = if (editMode && channelIndex < groupChannels.size - 1) {
                                            {
                                                val newOrder = groupChannels.toMutableList()
                                                val temp = newOrder[channelIndex]
                                                newOrder[channelIndex] = newOrder[channelIndex + 1]
                                                newOrder[channelIndex + 1] = temp
                                                onReorderGroupChannels(group.id, newOrder.map { it.id })
                                            }
                                        } else null,
                                        hasMention = channelMentions.contains(channel.id),
                                        unreadCount = unreadCounts[channel.id] ?: 0
                                    )
                                }
                            }
                        }
                    }
                    
                    is ChannelListItem.UngroupedChannel -> {
                        val channel = item.channel
                        item(key = "ungrouped_${channel.id}") {
                            UngroupedChannelItem(
                                channel = channel,
                                isSelected = channel.id == currentChannelId,
                                onClick = { onChannelClick(channel) },
                                onLongClick = if (isAdmin) {
                                    {
                                        channelToDelete = channel
                                        showDeleteDialog = true
                                    }
                                } else null,
                                voiceUsers = voiceChannelUsers[channel.id] ?: emptyList(),
                                editMode = editMode,
                                onMoveUp = if (editMode && index > 0) {
                                    {
                                        val newList = draggableList.toMutableList()
                                        val temp = newList[index]
                                        newList[index] = newList[index - 1]
                                        newList[index - 1] = temp
                                        draggableList = newList
                                        submitTopLevelReorder(newList, onReorderTopLevel)
                                    }
                                } else null,
                                onMoveDown = if (editMode && index < draggableList.size - 1) {
                                    {
                                        val newList = draggableList.toMutableList()
                                        val temp = newList[index]
                                        newList[index] = newList[index + 1]
                                        newList[index + 1] = temp
                                        draggableList = newList
                                        submitTopLevelReorder(newList, onReorderTopLevel)
                                    }
                                } else null,
                                hasMention = channelMentions.contains(channel.id),
                                unreadCount = unreadCounts[channel.id] ?: 0
                            )
                        }
                    }
                }
            }
            
            // Add channel button at the end (for ungrouped channels)
            if (editMode && isAdmin) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    AddChannelButton(
                        label = stringResource(R.string.action_add_channel),
                        onClick = {
                            createChannelGroupId = null
                            createChannelType = "text"
                            showCreateDialog = true
                        }
                    )
                }
            }
        }

        // Voice status widget (shown when connected to voice)
        VoiceStatusWidget(
            isConnected = isVoiceConnected,
            channelName = voiceChannelName,
            isMuted = isVoiceMuted,
            onToggleMute = onToggleMute,
            onDisconnect = onDisconnectVoice
        )

        // User panel at bottom
        UserPanel(
            username = username,
            avatarUrl = avatarUrl,
            onLogout = onLogout,
            onSettings = onSettings
        )
    }

    // Create Channel Dialog
    if (showCreateDialog) {
        CreateChannelDialog(
            channelType = createChannelType,
            groupId = createChannelGroupId,
            onDismiss = { 
                showCreateDialog = false
                createChannelGroupId = null
            },
            onCreate = { name, type ->
                onCreateChannel(name, type, createChannelGroupId)
                showCreateDialog = false
                createChannelGroupId = null
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
            title = { Text(stringResource(R.string.channel_delete_title)) },
            text = { Text(stringResource(R.string.channel_delete_confirm, channelToDelete?.name ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        channelToDelete?.let { onDeleteChannel(it.id) }
                        showDeleteDialog = false
                        channelToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Zhimo.danger)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    channelToDelete = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Create Channel Group Dialog
    if (showCreateGroupDialog) {
        CreateChannelGroupDialog(
            onDismiss = { showCreateGroupDialog = false },
            onCreate = { name ->
                onCreateChannelGroup(name)
                showCreateGroupDialog = false
            }
        )
    }
    
    // Delete Channel Group Dialog
    if (showDeleteGroupDialog && groupToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteGroupDialog = false
                groupToDelete = null
            },
            title = { Text(stringResource(R.string.group_delete_title)) },
            text = { Text(stringResource(R.string.group_delete_confirm, groupToDelete?.name ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        groupToDelete?.let { onDeleteChannelGroup(it.id) }
                        showDeleteGroupDialog = false
                        groupToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Zhimo.danger)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteGroupDialog = false
                    groupToDelete = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

// Helper function to submit top-level reorder
private fun submitTopLevelReorder(
    list: List<ChannelListItem>,
    onReorder: (List<ReorderTopLevelItem>) -> Unit
) {
    val items = list.map { item ->
        when (item) {
            is ChannelListItem.GroupItem -> ReorderTopLevelItem("group", item.group.id)
            is ChannelListItem.UngroupedChannel -> ReorderTopLevelItem("channel", item.channel.id)
        }
    }
    onReorder(items)
}

@Composable
private fun CreateChannelDialog(
    channelType: String,
    groupId: Long?,
    onDismiss: () -> Unit,
    onCreate: (name: String, type: String) -> Unit
) {
    var channelName by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(channelType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_create_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = channelName,
                    onValueChange = { channelName = it },
                    label = { Text(stringResource(R.string.channel_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.channel_type_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = Zhimo.inkFaint
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedType == "text",
                        onClick = { selectedType = "text" },
                        label = { Text(stringResource(R.string.channel_type_text)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Tag,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                    FilterChip(
                        selected = selectedType == "voice",
                        onClick = { selectedType = "voice" },
                        label = { Text(stringResource(R.string.channel_type_voice)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                    FilterChip(
                        selected = selectedType == "forward",
                        onClick = { selectedType = "forward" },
                        label = { Text(stringResource(R.string.channel_type_forward)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Radio,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (channelName.isNotBlank()) onCreate(channelName.trim(), selectedType) },
                enabled = channelName.isNotBlank()
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun CreateChannelGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit
) {
    var groupName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_create_title)) },
        text = {
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text(stringResource(R.string.group_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (groupName.isNotBlank()) onCreate(groupName.trim()) },
                enabled = groupName.isNotBlank()
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// Channel Group Header with collapse/expand
@Composable
private fun ChannelGroupHeader(
    group: ChannelGroup,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    editMode: Boolean,
    isAdmin: Boolean,
    onAddChannel: () -> Unit,
    onDeleteGroup: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleCollapse() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Collapse/expand icon
        Icon(
            imageVector = if (isCollapsed) Icons.Default.ChevronRight else Icons.Default.ExpandMore,
            contentDescription = if (isCollapsed) stringResource(R.string.action_expand_group) else stringResource(R.string.action_collapse_group),
            modifier = Modifier.size(16.dp),
            tint = Zhimo.inkFaint
        )
        
        Spacer(modifier = Modifier.width(4.dp))
        
        Text(
            text = group.name.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Zhimo.inkFaint,
            modifier = Modifier.weight(1f)
        )
        
        if (editMode && isAdmin) {
            // Move up button
            if (onMoveUp != null) {
                IconButton(
                    onClick = onMoveUp,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.action_move_up),
                        tint = Zhimo.inkFaint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            
            // Move down button
            if (onMoveDown != null) {
                IconButton(
                    onClick = onMoveDown,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.action_move_down),
                        tint = Zhimo.inkFaint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            
            // Add channel button
            IconButton(
                onClick = onAddChannel,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.action_add_channel),
                    tint = Zhimo.inkFaint,
                    modifier = Modifier.size(16.dp)
                )
            }
            
            // Delete group button
            IconButton(
                onClick = onDeleteGroup,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.group_delete_title),
                    tint = Zhimo.danger,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else if (isAdmin) {
            // Just show add button when not in edit mode
            IconButton(
                onClick = onAddChannel,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.action_add_channel),
                    tint = Zhimo.inkFaint,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// Add Group Button
@Composable
private fun AddGroupButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CreateNewFolder,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Zhimo.seal
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.action_add_group),
            style = MaterialTheme.typography.bodyMedium,
            color = Zhimo.seal
        )
    }
}

// Add Channel Button
@Composable
private fun AddChannelButton(
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Zhimo.seal
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Zhimo.seal
        )
    }
}

// Grouped Channel Item (with indent)
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupedChannelItem(
    channel: Channel,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    voiceUsers: List<VoiceUser>,
    editMode: Boolean,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    hasMention: Boolean = false,
    unreadCount: Int = 0
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) Zhimo.paperRaised else Color.Transparent,
        animationSpec = tween(150),
        label = "channelBg"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) Zhimo.ink else Zhimo.inkMuted,
        animationSpec = tween(150),
        label = "channelText"
    )

    Column(
        modifier = Modifier.padding(start = 12.dp)  // Indent for grouped channels
    ) {
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
                imageVector = when (channel.type) {
                    ChannelType.FORWARD -> Icons.Default.Radio
                    ChannelType.VOICE -> Icons.AutoMirrored.Filled.VolumeUp
                    else -> Icons.Default.Tag
                },
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

            // Mention badge (@)
            if (hasMention && channel.type == ChannelType.TEXT) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Zhimo.danger
                ) {
                    Text(
                        text = "@",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Zhimo.paper,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            // Unread count badge
            if (unreadCount > 0 && channel.type == ChannelType.TEXT) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Zhimo.inkFaint
                ) {
                    Text(
                        text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Zhimo.paper,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            // Voice user count badge
            if (channel.type == ChannelType.VOICE && voiceUsers.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Zhimo.paperRaised
                ) {
                    Text(
                        text = "${voiceUsers.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Zhimo.inkFaint,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Edit mode controls
            if (editMode) {
                if (onMoveUp != null) {
                    IconButton(
                        onClick = onMoveUp,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.action_move_up),
                            tint = Zhimo.inkFaint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (onMoveDown != null) {
                    IconButton(
                        onClick = onMoveDown,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.action_move_down),
                            tint = Zhimo.inkFaint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        
        // Voice users list for voice channels
        if (channel.type == ChannelType.VOICE) {
            AnimatedVisibility(
                visible = voiceUsers.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(start = 28.dp)
                ) {
                    voiceUsers.forEach { user ->
                        VoiceUserItem(user = user)
                    }
                }
            }
        }
    }
}

// Ungrouped Channel Item (no indent)
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UngroupedChannelItem(
    channel: Channel,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    voiceUsers: List<VoiceUser>,
    editMode: Boolean,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    hasMention: Boolean = false,
    unreadCount: Int = 0
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) Zhimo.paperRaised else Color.Transparent,
        animationSpec = tween(150),
        label = "channelBg"
    )

    val textColor by animateColorAsState(
        targetValue = if (isSelected) Zhimo.ink else Zhimo.inkMuted,
        animationSpec = tween(150),
        label = "channelText"
    )

    Column {
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
                imageVector = when (channel.type) {
                    ChannelType.FORWARD -> Icons.Default.Radio
                    ChannelType.VOICE -> Icons.AutoMirrored.Filled.VolumeUp
                    else -> Icons.Default.Tag
                },
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

            // Mention badge (@)
            if (hasMention && channel.type == ChannelType.TEXT) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Zhimo.danger
                ) {
                    Text(
                        text = "@",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Zhimo.paper,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }

            // Unread count badge
            if (unreadCount > 0 && channel.type == ChannelType.TEXT) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Zhimo.inkFaint
                ) {
                    Text(
                        text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Zhimo.paper,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }

            // Voice user count badge
            if (channel.type == ChannelType.VOICE && voiceUsers.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Zhimo.paperRaised
                ) {
                    Text(
                        text = "${voiceUsers.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Zhimo.inkFaint,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Edit mode controls
            if (editMode) {
                if (onMoveUp != null) {
                    IconButton(
                        onClick = onMoveUp,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.action_move_up),
                            tint = Zhimo.inkFaint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (onMoveDown != null) {
                    IconButton(
                        onClick = onMoveDown,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.action_move_down),
                            tint = Zhimo.inkFaint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        
        // Voice users list for voice channels
        if (channel.type == ChannelType.VOICE) {
            AnimatedVisibility(
                visible = voiceUsers.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(start = 28.dp)
                ) {
                    voiceUsers.forEach { user ->
                        VoiceUserItem(user = user)
                    }
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
                .background(Zhimo.seal),
            contentAlignment = Alignment.Center
        ) {
            if (!user.avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(user.avatarUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = user.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = user.name.take(1).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Zhimo.paper,
                    fontSize = 10.sp
                )
            }
        }

        // Host badge
        if (user.isHost) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = stringResource(R.string.host_badge_desc),
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(10.dp),
                tint = Zhimo.warning
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Username
        Text(
            text = user.name,
            style = MaterialTheme.typography.bodySmall,
            color = Zhimo.inkFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Muted indicator
        if (user.isMuted) {
            Icon(
                imageVector = Icons.Default.MicOff,
                contentDescription = stringResource(R.string.voice_muted_label),
                modifier = Modifier.size(12.dp),
                tint = Zhimo.danger
            )
        }
    }
}

@Composable
private fun UserPanel(
    username: String,
    avatarUrl: String?,
    onLogout: () -> Unit,
    onSettings: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Zhimo.paper
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // User avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Zhimo.seal),
                contentAlignment = Alignment.Center
            ) {
                if (!avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(avatarUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = username,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = username.take(1).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        color = Zhimo.paper
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Username
            Text(
                text = username,
                style = MaterialTheme.typography.bodyMedium,
                color = Zhimo.ink,
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
                    contentDescription = stringResource(R.string.settings_title),
                    tint = Zhimo.inkFaint,
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
                    contentDescription = stringResource(R.string.logout),
                    tint = Zhimo.inkFaint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun VoiceStatusWidget(
    isConnected: Boolean,
    channelName: String?,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onDisconnect: () -> Unit
) {
    AnimatedVisibility(
        visible = isConnected,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = Zhimo.success.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                // Status info
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Zhimo.success
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.voice_status_calling),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Zhimo.success
                        )
                        Text(
                            text = channelName ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Zhimo.inkFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Control buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute button
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (isMuted) Zhimo.danger else Zhimo.paperRaised)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (isMuted) stringResource(R.string.unmute) else stringResource(R.string.mute),
                            modifier = Modifier.size(20.dp),
                            tint = if (isMuted) Zhimo.paper else Zhimo.ink
                        )
                    }

                    // Disconnect button
                    IconButton(
                        onClick = onDisconnect,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Zhimo.danger)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = stringResource(R.string.leave_voice),
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(135f),
                            tint = Zhimo.paper
                        )
                    }
                }
            }
        }
    }
}
