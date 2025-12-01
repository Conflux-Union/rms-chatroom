package com.rms.discord.ui.main.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rms.discord.data.model.Server
import com.rms.discord.ui.theme.TiColor
import com.rms.discord.ui.theme.SurfaceDarker
import com.rms.discord.ui.theme.SurfaceLight

@Composable
fun ServerListColumn(
    servers: List<Server>,
    currentServerId: Long?,
    onServerClick: (Long) -> Unit,
    isAdmin: Boolean = false,
    onCreateServer: (name: String) -> Unit = {},
    onDeleteServer: (serverId: Long) -> Unit = {}
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var serverToDelete by remember { mutableStateOf<Server?>(null) }

    Column(
        modifier = Modifier
            .width(72.dp)
            .fillMaxHeight()
            .background(SurfaceDarker)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(servers, key = { it.id }) { server ->
                ServerItem(
                    server = server,
                    isSelected = server.id == currentServerId,
                    onClick = { onServerClick(server.id) },
                    onLongClick = if (isAdmin) {
                        {
                            serverToDelete = server
                            showDeleteDialog = true
                        }
                    } else null
                )
            }
        }

        // Add server button (admin only)
        if (isAdmin) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(
                modifier = Modifier
                    .width(32.dp)
                    .padding(vertical = 4.dp),
                color = Color.Gray.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            AddServerButton(onClick = { showCreateDialog = true })
        }
    }

    // Create Server Dialog
    if (showCreateDialog) {
        CreateServerDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                onCreateServer(name)
                showCreateDialog = false
            }
        )
    }

    // Delete Server Dialog
    if (showDeleteDialog && serverToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                serverToDelete = null
            },
            title = { Text("删除服务器") },
            text = { Text("确定要删除服务器「${serverToDelete?.name}」吗？所有频道和消息都将被删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        serverToDelete?.let { onDeleteServer(it.id) }
                        showDeleteDialog = false
                        serverToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFED4245))
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    serverToDelete = null
                }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun CreateServerDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit
) {
    var serverName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建服务器") },
        text = {
            OutlinedTextField(
                value = serverName,
                onValueChange = { serverName = it },
                label = { Text("服务器名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (serverName.isNotBlank()) onCreate(serverName.trim()) },
                enabled = serverName.isNotBlank()
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
private fun AddServerButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(SurfaceLight)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "添加服务器",
            tint = TiColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerItem(
    server: Server,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val indicatorHeight by animateDpAsState(
        targetValue = if (isSelected) 40.dp else 8.dp,
        animationSpec = tween(200),
        label = "indicatorHeight"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) TiColor else SurfaceLight,
        animationSpec = tween(200),
        label = "backgroundColor"
    )

    val cornerRadius by animateDpAsState(
        targetValue = if (isSelected) 16.dp else 24.dp,
        animationSpec = tween(200),
        label = "cornerRadius"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection indicator
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(indicatorHeight)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .background(if (isSelected) Color.White else Color.Transparent)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Server icon
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(cornerRadius))
                .background(backgroundColor)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (server.icon != null) {
                AsyncImage(
                    model = server.icon,
                    contentDescription = server.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = server.name.take(2).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
