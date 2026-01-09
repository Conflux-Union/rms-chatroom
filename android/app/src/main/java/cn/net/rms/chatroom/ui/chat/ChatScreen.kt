package cn.net.rms.chatroom.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.data.model.AttachmentResponse
import cn.net.rms.chatroom.data.model.Attachment
import cn.net.rms.chatroom.data.model.Message
import cn.net.rms.chatroom.data.websocket.ConnectionState
import cn.net.rms.chatroom.ui.theme.DiscordRed
import cn.net.rms.chatroom.ui.theme.DiscordYellow
import cn.net.rms.chatroom.ui.theme.SurfaceDarker
import cn.net.rms.chatroom.ui.theme.SurfaceLighter
import cn.net.rms.chatroom.ui.theme.TextMuted
import cn.net.rms.chatroom.ui.theme.TextPrimary
import cn.net.rms.chatroom.ui.theme.TextSecondary
import cn.net.rms.chatroom.ui.theme.TiColor
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

enum class SendingState {
    IDLE,
    SENDING,
    SENT,
    FAILED
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    messages: List<Message>,
    isLoading: Boolean = false,
    connectionState: ConnectionState = ConnectionState.CONNECTED,
    authToken: String? = null,
    currentUserId: Long? = null,
    currentUserPermission: Int? = null,
    lastReadMessageId: Long? = null,
    showContinueReading: Boolean = false,
    onSendMessage: (String, List<Long>) -> Unit,
    onUploadFile: suspend (Uri) -> Result<AttachmentResponse>,
    onRefresh: () -> Unit = {},
    onReconnect: () -> Unit = {},
    onEditMessage: (Long, String) -> Unit = { _, _ -> },
    onDeleteMessage: (Long) -> Unit = {},
    onMuteUser: (Long, String, String?, Long?, Long?, String?) -> Unit = { _, _, _, _, _, _ -> },
    onSaveReadPosition: (Long) -> Unit = {},
    onDismissContinueReading: () -> Unit = {},
    onGetMessageIndex: (Long) -> Int = { -1 }
) {
    val listState = rememberLazyListState()
    var messageText by remember { mutableStateOf("") }
    var sendingState by remember { mutableStateOf(SendingState.IDLE) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val pullRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var attachmentPreview by remember { mutableStateOf<AttachmentPreview?>(null) }
    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    var showMessageMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showMuteDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // File upload state
    var pendingFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var uploadedAttachments by remember { mutableStateOf<List<AttachmentResponse>>(emptyList()) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf<Map<Uri, Float>>(emptyMap()) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingFiles = pendingFiles + uris
        }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Auto-scroll to bottom when keyboard appears
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Reset sending state after success
    LaunchedEffect(sendingState) {
        if (sendingState == SendingState.SENT) {
            delay(500)
            sendingState = SendingState.IDLE
        }
    }

    // Save read position when scrolling stops
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && messages.isNotEmpty()) {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            if (lastVisibleIndex != null && lastVisibleIndex < messages.size) {
                val lastVisibleMessage = messages[lastVisibleIndex]
                onSaveReadPosition(lastVisibleMessage.id)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Connection status banner
            ConnectionBanner(
                connectionState = connectionState,
                onReconnect = onReconnect
            )

            // Messages list with pull-to-refresh
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    onRefresh()
                    isRefreshing = false
                },
                state = pullRefreshState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    isLoading && messages.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = TiColor)
                        }
                    }
                    messages.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无消息\n发送第一条消息吧！",
                                color = TextMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            state = listState,
                            contentPadding = PaddingValues(vertical = 16.dp)
                        ) {
                            items(messages.size, key = { messages[it].id }) { index ->
                                val message = messages[index]
                                val isGrouped = shouldGroupWithPrevious(messages, index)
                                MessageItem(
                                    message = message,
                                    isGrouped = isGrouped,
                                    authToken = authToken,
                                    currentUserId = currentUserId,
                                    currentUserPermission = currentUserPermission,
                                    onAttachmentClick = { attachment ->
                                        handleAttachmentClick(
                                            context = context,
                                            attachment = attachment,
                                            authToken = authToken,
                                            onPreview = { preview -> attachmentPreview = preview }
                                        )
                                    },
                                    onLongClick = { msg ->
                                        selectedMessage = msg
                                        showMessageMenu = true
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Pending files preview
            if (pendingFiles.isNotEmpty() || uploadedAttachments.isNotEmpty()) {
                PendingFilesPreview(
                    context = context,
                    pendingFiles = pendingFiles,
                    uploadedAttachments = uploadedAttachments,
                    uploadProgress = uploadProgress,
                    isUploading = isUploading,
                    onRemovePending = { uri ->
                        pendingFiles = pendingFiles.filter { it != uri }
                    },
                    onRemoveUploaded = { id ->
                        uploadedAttachments = uploadedAttachments.filter { it.id != id }
                    }
                )
            }

            // Message input
            MessageInput(
                value = messageText,
                onValueChange = { messageText = it },
                sendingState = sendingState,
                isConnected = connectionState == ConnectionState.CONNECTED,
                hasAttachments = pendingFiles.isNotEmpty() || uploadedAttachments.isNotEmpty(),
                isUploading = isUploading,
                onAttachClick = {
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                onSend = {
                    val hasContent = messageText.isNotBlank()
                    val hasAttachments = pendingFiles.isNotEmpty() || uploadedAttachments.isNotEmpty()
                    if ((hasContent || hasAttachments) && connectionState == ConnectionState.CONNECTED && !isUploading) {
                        scope.launch {
                            sendingState = SendingState.SENDING

                            // Upload pending files first
                            if (pendingFiles.isNotEmpty()) {
                                isUploading = true
                                for (uri in pendingFiles) {
                                    val result = onUploadFile(uri)
                                    result.onSuccess { attachment ->
                                        uploadedAttachments = uploadedAttachments + attachment
                                    }.onFailure { e ->
                                        Toast.makeText(context, "上传失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                pendingFiles = emptyList()
                                isUploading = false
                            }

                            // Send message with attachments
                            val attachmentIds = uploadedAttachments.map { it.id }
                            val content = messageText.trim()

                            if (content.isNotBlank() || attachmentIds.isNotEmpty()) {
                                keyboardController?.hide()
                                onSendMessage(content, attachmentIds)
                                messageText = ""
                                uploadedAttachments = emptyList()
                            }

                            sendingState = SendingState.SENT
                        }
                    }
                }
            )
        }

        AttachmentPreviewDialog(
            preview = attachmentPreview,
            authToken = authToken,
            onDismiss = { attachmentPreview = null }
        )

        // Continue reading button
        AnimatedVisibility(
            visible = showContinueReading && lastReadMessageId != null && messages.isNotEmpty(),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Surface(
                onClick = {
                    val index = onGetMessageIndex(lastReadMessageId ?: 0)
                    if (index >= 0) {
                        scope.launch {
                            listState.animateScrollToItem(index)
                        }
                    }
                    onDismissContinueReading()
                },
                shape = RoundedCornerShape(20.dp),
                color = TiColor,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "继续阅读",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                    IconButton(
                        onClick = { onDismissContinueReading() },
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }

        // Message context menu
        if (showMessageMenu && selectedMessage != null) {
            MessageContextMenu(
                message = selectedMessage!!,
                currentUserId = currentUserId,
                currentUserPermission = currentUserPermission,
                onDismiss = { showMessageMenu = false },
                onEdit = {
                    showMessageMenu = false
                    showEditDialog = true
                },
                onDelete = {
                    showMessageMenu = false
                    onDeleteMessage(selectedMessage!!.id)
                    selectedMessage = null
                },
                onMute = {
                    showMessageMenu = false
                    showMuteDialog = true
                }
            )
        }

        // Edit message dialog
        if (showEditDialog && selectedMessage != null) {
            EditMessageDialog(
                message = selectedMessage!!,
                onDismiss = {
                    showEditDialog = false
                    selectedMessage = null
                },
                onConfirm = { newContent ->
                    onEditMessage(selectedMessage!!.id, newContent)
                    showEditDialog = false
                    selectedMessage = null
                }
            )
        }

        // Mute user dialog
        if (showMuteDialog && selectedMessage != null) {
            MuteUserDialog(
                userId = selectedMessage!!.userId,
                username = selectedMessage!!.username,
                onDismiss = {
                    showMuteDialog = false
                    selectedMessage = null
                },
                onConfirm = { scope, mutedUntil, serverId, channelId, reason ->
                    onMuteUser(selectedMessage!!.userId, scope, mutedUntil, serverId, channelId, reason)
                    showMuteDialog = false
                    selectedMessage = null
                }
            )
        }
    }
}

@Composable
private fun ConnectionBanner(
    connectionState: ConnectionState,
    onReconnect: () -> Unit
) {
    AnimatedVisibility(
        visible = connectionState != ConnectionState.CONNECTED,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
    ) {
        val (backgroundColor, text, showReconnect) = when (connectionState) {
            ConnectionState.CONNECTING -> Triple(
                DiscordYellow.copy(alpha = 0.9f),
                "正在连接...",
                false
            )
            ConnectionState.RECONNECTING -> Triple(
                DiscordYellow.copy(alpha = 0.9f),
                "正在重新连接...",
                false
            )
            ConnectionState.DISCONNECTED -> Triple(
                DiscordRed.copy(alpha = 0.9f),
                "连接已断开",
                true
            )
            else -> Triple(Color.Transparent, "", false)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = backgroundColor
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (connectionState == ConnectionState.DISCONNECTED) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    }
                    Text(
                        text = text,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (showReconnect) {
                    TextButton(
                        onClick = onReconnect,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("重连", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    message: Message,
    isGrouped: Boolean = false,
    authToken: String?,
    currentUserId: Long?,
    currentUserPermission: Int?,
    onAttachmentClick: (Attachment) -> Unit,
    onLongClick: (Message) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isGrouped) 2.dp else 16.dp)
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClickLabel = "Long press for options",
                onClick = {},
                onLongClick = { onLongClick(message) }
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Avatar: show for first message in group, placeholder for grouped messages
        if (isGrouped) {
            // Invisible placeholder to maintain alignment
            Spacer(modifier = Modifier.size(40.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(TiColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = message.username.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            // Username and timestamp: hidden for grouped messages
            if (!isGrouped) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = message.username,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )

                    Text(
                        text = formatTimestamp(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }

                // Edited indicator on new line
                if (message.editedAt != null) {
                    Text(
                        text = "(已编辑于 ${formatTimestamp(message.editedAt)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
            }

            // Message content or deleted placeholder
            if (message.isDeleted) {
                Text(
                    text = when {
                        message.deletedBy == currentUserId -> "你撤回了一条消息"
                        message.deletedByUsername != null -> "${message.deletedByUsername}撤回了一条消息"
                        else -> "管理员撤回了一条消息"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            } else {
                if (message.content.isNotBlank()) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                // Attachments
                message.attachments?.forEach { attachment ->
                    Spacer(modifier = Modifier.height(8.dp))
                    AttachmentItem(
                        attachment = attachment,
                        authToken = authToken,
                        onAttachmentClick = onAttachmentClick
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentItem(
    attachment: Attachment,
    authToken: String?,
    onAttachmentClick: (Attachment) -> Unit
) {
    val context = LocalContext.current
    val isImage = attachment.contentType.startsWith("image/")
    val isVideo = attachment.contentType.startsWith("video/")
    val isAudio = attachment.contentType.startsWith("audio/")
    val isPdf = attachment.contentType == "application/pdf"

    val icon = when {
        isImage -> Icons.Default.Image
        isVideo -> Icons.Default.Movie
        isAudio -> Icons.Default.MusicNote
        isPdf -> Icons.Default.PictureAsPdf
        else -> Icons.Default.InsertDriveFile
    }
    val inlineUrl = buildAttachmentUrl(attachment, inline = true)

    // For images, show preview
    if (isImage) {
        val imageRequest = ImageRequest.Builder(context)
            .data(inlineUrl)
            .apply {
                if (!authToken.isNullOrBlank()) {
                    addHeader("Authorization", "Bearer $authToken")
                }
            }
            .build()

        AsyncImage(
            model = imageRequest,
            contentDescription = attachment.filename,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onAttachmentClick(attachment) }
        )
    } else {
        // For other files, show card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAttachmentClick(attachment) },
            shape = RoundedCornerShape(8.dp),
            color = SurfaceLighter
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TiColor,
                    modifier = Modifier.size(24.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = formatFileSize(attachment.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }

                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "下载",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun handleAttachmentClick(
    context: Context,
    attachment: Attachment,
    authToken: String?,
    onPreview: (AttachmentPreview) -> Unit
) {
    when (resolveAttachmentType(attachment)) {
        AttachmentType.IMAGE -> onPreview(
            AttachmentPreview.Image(
                url = buildAttachmentUrl(attachment, inline = true),
                filename = attachment.filename
            )
        )

        AttachmentType.VIDEO -> onPreview(
            AttachmentPreview.Video(
                url = buildAttachmentUrl(attachment, inline = true),
                filename = attachment.filename
            )
        )

        AttachmentType.TEXT -> onPreview(
            AttachmentPreview.Text(
                url = buildAttachmentUrl(attachment, inline = true),
                filename = attachment.filename,
                contentType = attachment.contentType
            )
        )

        AttachmentType.OTHER -> downloadAndOpenAttachment(context, attachment, authToken)
    }
}

private enum class AttachmentType {
    IMAGE,
    VIDEO,
    TEXT,
    OTHER
}

private sealed class AttachmentPreview {
    data class Image(val url: String, val filename: String) : AttachmentPreview()
    data class Video(val url: String, val filename: String) : AttachmentPreview()
    data class Text(val url: String, val filename: String, val contentType: String) : AttachmentPreview()
}

private fun buildAttachmentUrl(attachment: Attachment, inline: Boolean): String {
    val base = "${BuildConfig.API_BASE_URL}${attachment.url}"
    if (!inline) return base
    val separator = if (attachment.url.contains("?")) "&" else "?"
    return base + separator + "inline=1"
}

private fun resolveAttachmentType(attachment: Attachment): AttachmentType {
    val ext = attachment.filename.substringAfterLast('.', "").lowercase()
    val contentType = attachment.contentType.lowercase()
    val imageExt = setOf("jpg", "jpeg", "png", "gif", "webp")
    val videoExt = setOf("mp4", "mov", "mkv", "webm")
    val textExt = setOf("txt", "md", "log", "json", "csv")

    return when {
        contentType.startsWith("image/") || ext in imageExt -> AttachmentType.IMAGE
        contentType.startsWith("video/") || ext in videoExt -> AttachmentType.VIDEO
        contentType.startsWith("text/") || ext in textExt -> AttachmentType.TEXT
        else -> AttachmentType.OTHER
    }
}

private fun downloadAndOpenAttachment(context: Context, attachment: Attachment, authToken: String?) {
    val url = buildAttachmentUrl(attachment, inline = false)
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(attachment.filename)
        .setDescription("Downloading attachment")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, attachment.filename)

    if (!authToken.isNullOrBlank()) {
        request.addRequestHeader("Authorization", "Bearer $authToken")
    }

    val downloadManager = context.getSystemService(DownloadManager::class.java) ?: return
    val downloadId = downloadManager.enqueue(request)
    Toast.makeText(context, "Downloading...", Toast.LENGTH_SHORT).show()

    var receiver: BroadcastReceiver? = null
    receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val receivedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (receivedId != downloadId) return

            try {
                ctx?.unregisterReceiver(this)
            } catch (_: IllegalArgumentException) {
                // Already unregistered
            }
            receiver = null

            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor = downloadManager.query(query)
            if (cursor?.moveToFirst() != true) {
                cursor?.close()
                Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                return
            }

            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = if (statusIndex >= 0) cursor.getInt(statusIndex) else -1
            cursor.close()

            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                return
            }

            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), attachment.filename)
            if (!file.exists()) {
                Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )

            val openIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.contentType.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(openIntent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "Downloaded to app storage", Toast.LENGTH_LONG).show()
            }
        }
    }

    context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
}

@Composable
private fun AttachmentPreviewDialog(
    preview: AttachmentPreview?,
    authToken: String?,
    onDismiss: () -> Unit
) {
    when (preview) {
        is AttachmentPreview.Image -> ImagePreview(preview, authToken, onDismiss)
        is AttachmentPreview.Video -> VideoPreview(preview, authToken, onDismiss)
        is AttachmentPreview.Text -> TextPreview(preview, authToken, onDismiss)
        null -> Unit
    }
}

@Composable
private fun ImagePreview(
    preview: AttachmentPreview.Image,
    authToken: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val imageRequest = remember(preview.url, authToken) {
        ImageRequest.Builder(context)
            .data(preview.url)
            .apply {
                if (!authToken.isNullOrBlank()) {
                    addHeader("Authorization", "Bearer $authToken")
                }
            }
            .build()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            ZoomableAsyncImage(
                model = imageRequest,
                contentDescription = preview.filename,
                modifier = Modifier.fillMaxSize(),
                state = rememberZoomableImageState()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = preview.filename,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPreview(
    preview: AttachmentPreview.Video,
    authToken: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val exoPlayer = remember(preview.url, authToken) {
        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            if (!authToken.isNullOrBlank()) {
                setDefaultRequestProperties(mapOf("Authorization" to "Bearer $authToken"))
            }
        }
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(preview.url))

        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        player = exoPlayer
                        useController = true
                    }
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = preview.filename,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

private sealed class TextContentState {
    object Loading : TextContentState()
    data class Loaded(val content: String) : TextContentState()
    data class Error(val message: String) : TextContentState()
}

@Composable
private fun TextPreview(
    preview: AttachmentPreview.Text,
    authToken: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val client = remember { OkHttpClient() }

    val state by produceState<TextContentState>(initialValue = TextContentState.Loading, preview.url, authToken) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val request = Request.Builder()
                .url(preview.url)
                .apply {
                    if (!authToken.isNullOrBlank()) {
                        header("Authorization", "Bearer $authToken")
                    }
                }
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        TextContentState.Loaded(response.body?.string().orEmpty())
                    } else {
                        TextContentState.Error("Failed to load: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                TextContentState.Error("Failed to load: ${e.message}")
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black.copy(alpha = 0.95f)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                when (val current = state) {
                    is TextContentState.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color.White
                        )
                    }

                    is TextContentState.Loaded -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = preview.filename,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = current.content,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    is TextContentState.Error -> {
                        Text(
                            text = current.message,
                            color = DiscordRed,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / 1024 / 1024} MB"
    }
}

@Composable
private fun PendingFilesPreview(
    context: Context,
    pendingFiles: List<Uri>,
    uploadedAttachments: List<AttachmentResponse>,
    uploadProgress: Map<Uri, Float>,
    isUploading: Boolean,
    onRemovePending: (Uri) -> Unit,
    onRemoveUploaded: (Long) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDarker
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Pending files (not yet uploaded)
            items(pendingFiles) { uri ->
                PendingFileItem(
                    context = context,
                    uri = uri,
                    progress = uploadProgress[uri],
                    isUploading = isUploading,
                    onRemove = { onRemovePending(uri) }
                )
            }
            // Uploaded attachments
            items(uploadedAttachments) { attachment ->
                UploadedAttachmentItem(
                    attachment = attachment,
                    onRemove = { onRemoveUploaded(attachment.id) }
                )
            }
        }
    }
}

@Composable
private fun PendingFileItem(
    context: Context,
    uri: Uri,
    progress: Float?,
    isUploading: Boolean,
    onRemove: () -> Unit
) {
    val fileName = remember(uri) {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = it.getString(index)
                    }
                }
            }
        }
        name ?: uri.lastPathSegment ?: "file"
    }

    Surface(
        modifier = Modifier.width(120.dp),
        shape = RoundedCornerShape(8.dp),
        color = SurfaceLighter
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    tint = TiColor,
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.Center)
                )
                if (!isUploading) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .size(20.dp)
                            .align(Alignment.TopEnd)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (progress != null) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = TiColor
                )
            }
        }
    }
}

@Composable
private fun UploadedAttachmentItem(
    attachment: AttachmentResponse,
    onRemove: () -> Unit
) {
    val icon = when {
        attachment.contentType.startsWith("image/") -> Icons.Default.Image
        attachment.contentType.startsWith("video/") -> Icons.Default.Movie
        attachment.contentType.startsWith("audio/") -> Icons.Default.MusicNote
        attachment.contentType == "application/pdf" -> Icons.Default.PictureAsPdf
        else -> Icons.Default.InsertDriveFile
    }

    Surface(
        modifier = Modifier.width(120.dp),
        shape = RoundedCornerShape(8.dp),
        color = SurfaceLighter
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = TiColor,
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.Center)
                )
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = attachment.filename,
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatFileSize(attachment.size),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}

@Composable
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    sendingState: SendingState,
    isConnected: Boolean,
    hasAttachments: Boolean,
    isUploading: Boolean,
    onAttachClick: () -> Unit,
    onSend: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDarker
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Attach button
            IconButton(
                onClick = onAttachClick,
                enabled = isConnected && !isUploading,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "添加附件",
                    tint = if (isConnected && !isUploading) TiColor else TextMuted
                )
            }

            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = isConnected,
                placeholder = {
                    Text(
                        text = if (isConnected) stringResource(R.string.send_message) else "连接断开，无法发送",
                        color = TextMuted
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceLighter,
                    unfocusedContainerColor = SurfaceLighter,
                    disabledContainerColor = SurfaceLighter.copy(alpha = 0.5f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = TiColor,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    disabledTextColor = TextMuted
                ),
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                singleLine = false,
                maxLines = 4
            )

            // Send button
            AnimatedVisibility(
                visible = value.isNotBlank() || hasAttachments,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                IconButton(
                    onClick = onSend,
                    enabled = isConnected && sendingState != SendingState.SENDING && !isUploading,
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (isConnected && !isUploading) TiColor else TiColor.copy(alpha = 0.5f),
                            CircleShape
                        )
                ) {
                    when {
                        sendingState == SendingState.SENDING || isUploading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "发送",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: String): String {
    return try {
        val normalizedTimestamp = if (timestamp.endsWith("Z")) timestamp else "${timestamp}Z"
        val instant = Instant.parse(normalizedTimestamp)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (e: Exception) {
        timestamp
    }
}

// Message grouping: Discord-style consecutive message merging
private const val MESSAGE_GROUP_THRESHOLD_MINUTES = 7L

private fun shouldGroupWithPrevious(messages: List<Message>, index: Int): Boolean {
    if (index == 0) return false

    val currentMsg = messages[index]
    val prevMsg = messages[index - 1]

    // Different user, don't group
    if (currentMsg.userId != prevMsg.userId) return false

    // Previous message is deleted, don't group
    if (prevMsg.isDeleted) return false

    // Time gap exceeds threshold, don't group
    return try {
        val currentTimestamp = if (currentMsg.createdAt.endsWith("Z")) currentMsg.createdAt else "${currentMsg.createdAt}Z"
        val prevTimestamp = if (prevMsg.createdAt.endsWith("Z")) prevMsg.createdAt else "${prevMsg.createdAt}Z"
        val currentTime = Instant.parse(currentTimestamp)
        val prevTime = Instant.parse(prevTimestamp)
        val diffMinutes = java.time.Duration.between(prevTime, currentTime).toMinutes()
        diffMinutes <= MESSAGE_GROUP_THRESHOLD_MINUTES
    } catch (e: Exception) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageContextMenu(
    message: Message,
    currentUserId: Long?,
    currentUserPermission: Int?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMute: () -> Unit
) {
    val isOwnMessage = message.userId == currentUserId
    val isAdmin = (currentUserPermission ?: 0) >= 3
    val canEdit = isOwnMessage && !message.isDeleted
    val canDelete = !message.isDeleted && (isOwnMessage || isAdmin)
    val canMute = isAdmin && !isOwnMessage

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDarker
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            if (canEdit) {
                MenuOption(
                    text = "编辑消息",
                    icon = androidx.compose.material.icons.Icons.Default.Edit,
                    onClick = onEdit
                )
            }

            if (canDelete) {
                MenuOption(
                    text = "撤回消息",
                    icon = androidx.compose.material.icons.Icons.Default.Delete,
                    onClick = onDelete,
                    isDestructive = true
                )
            }

            if (canMute) {
                MenuOption(
                    text = "禁言用户",
                    icon = androidx.compose.material.icons.Icons.Default.Block,
                    onClick = onMute,
                    isDestructive = true
                )
            }

            if (!canEdit && !canDelete && !canMute) {
                Text(
                    text = "无可用操作",
                    modifier = Modifier.padding(16.dp),
                    color = TextMuted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun MenuOption(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isDestructive) DiscordRed else TextPrimary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isDestructive) DiscordRed else TextPrimary
        )
    }
}

@Composable
private fun EditMessageDialog(
    message: Message,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var editedContent by remember { mutableStateOf(message.content) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑消息") },
        text = {
            TextField(
                value = editedContent,
                onValueChange = { editedContent = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入消息内容") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceLighter,
                    unfocusedContainerColor = SurfaceLighter,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = TiColor
                ),
                shape = RoundedCornerShape(8.dp),
                minLines = 3,
                maxLines = 8
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (editedContent.isNotBlank()) onConfirm(editedContent.trim()) },
                enabled = editedContent.isNotBlank() && editedContent.trim() != message.content
            ) {
                Text("保存", color = TiColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextMuted)
            }
        },
        containerColor = SurfaceDarker
    )
}

@Composable
private fun MuteUserDialog(
    userId: Long,
    username: String,
    onDismiss: () -> Unit,
    onConfirm: (scope: String, mutedUntil: String?, serverId: Long?, channelId: Long?, reason: String?) -> Unit
) {
    var selectedScope by remember { mutableStateOf("channel") }
    var selectedDuration by remember { mutableStateOf("10m") }
    var reason by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("禁言用户: $username") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Scope selection
                Text("禁言范围", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioOption("当前频道", "channel", selectedScope) { selectedScope = it }
                    RadioOption("当前服务器", "server", selectedScope) { selectedScope = it }
                    RadioOption("全局", "global", selectedScope) { selectedScope = it }
                }

                // Duration selection
                Text("禁言时长", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioOption("10分钟", "10m", selectedDuration) { selectedDuration = it }
                    RadioOption("1小时", "1h", selectedDuration) { selectedDuration = it }
                    RadioOption("1天", "1d", selectedDuration) { selectedDuration = it }
                    RadioOption("永久", "permanent", selectedDuration) { selectedDuration = it }
                }

                // Reason input
                Text("原因（可选）", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                TextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入禁言原因") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = SurfaceLighter,
                        unfocusedContainerColor = SurfaceLighter,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = TiColor
                    ),
                    shape = RoundedCornerShape(8.dp),
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val mutedUntil = when (selectedDuration) {
                        "10m" -> java.time.Instant.now().plusSeconds(600).toString()
                        "1h" -> java.time.Instant.now().plusSeconds(3600).toString()
                        "1d" -> java.time.Instant.now().plusSeconds(86400).toString()
                        else -> null
                    }
                    onConfirm(selectedScope, mutedUntil, null, null, reason.ifBlank { null })
                }
            ) {
                Text("确认", color = DiscordRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextMuted)
            }
        },
        containerColor = SurfaceDarker
    )
}

@Composable
private fun RadioOption(
    label: String,
    value: String,
    selectedValue: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        androidx.compose.material3.RadioButton(
            selected = selectedValue == value,
            onClick = { onSelect(value) },
            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                selectedColor = TiColor,
                unselectedColor = TextMuted
            )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
    }
}
