package cn.net.rms.chatroom.ui.chat

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
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    onSendMessage: (String) -> Unit,
    onRefresh: () -> Unit = {},
    onReconnect: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    var messageText by remember { mutableStateOf("") }
    var sendingState by remember { mutableStateOf(SendingState.IDLE) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val pullRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var attachmentPreview by remember { mutableStateOf<AttachmentPreview?>(null) }

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
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(vertical = 16.dp)
                        ) {
                            items(messages, key = { it.id }) { message ->
                                MessageItem(
                                    message = message,
                                    authToken = authToken,
                                    onAttachmentClick = { attachment ->
                                        handleAttachmentClick(
                                            context = context,
                                            attachment = attachment,
                                            authToken = authToken,
                                            onPreview = { preview -> attachmentPreview = preview }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Message input
            MessageInput(
                value = messageText,
                onValueChange = { messageText = it },
                sendingState = sendingState,
                isConnected = connectionState == ConnectionState.CONNECTED,
                onSend = {
                    if (messageText.isNotBlank() && connectionState == ConnectionState.CONNECTED) {
                        sendingState = SendingState.SENDING
                        val content = messageText.trim()
                        messageText = ""
                        keyboardController?.hide()
                        onSendMessage(content)
                        sendingState = SendingState.SENT
                    }
                }
            )
        }

        AttachmentPreviewDialog(
            preview = attachmentPreview,
            authToken = authToken,
            onDismiss = { attachmentPreview = null }
        )
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

@Composable
private fun MessageItem(
    message: Message,
    authToken: String?,
    onAttachmentClick: (Attachment) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Avatar
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

        Column(modifier = Modifier.weight(1f)) {
            // Username and timestamp
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

            Spacer(modifier = Modifier.height(4.dp))

            // Message content
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
private fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    sendingState: SendingState,
    isConnected: Boolean,
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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                visible = value.isNotBlank(),
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                IconButton(
                    onClick = onSend,
                    enabled = isConnected && sendingState != SendingState.SENDING,
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (isConnected) TiColor else TiColor.copy(alpha = 0.5f),
                            CircleShape
                        )
                ) {
                    when (sendingState) {
                        SendingState.SENDING -> {
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
        // Backend returns UTC time without 'Z' suffix, append it if missing
        val normalizedTimestamp = if (timestamp.endsWith("Z")) timestamp else "${timestamp}Z"
        val instant = Instant.parse(normalizedTimestamp)
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (e: Exception) {
        timestamp
    }
}
