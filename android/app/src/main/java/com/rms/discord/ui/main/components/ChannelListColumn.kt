package com.rms.discord.ui.main.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rms.discord.data.model.Channel
import com.rms.discord.data.model.ChannelType
import com.rms.discord.data.model.Server
import com.rms.discord.ui.theme.*

@Composable
fun ChannelListColumn(
    server: Server?,
    currentChannelId: Long?,
    onChannelClick: (Channel) -> Unit,
    username: String,
    onLogout: () -> Unit
) {
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
            if (textChannels.isNotEmpty()) {
                item {
                    ChannelSectionHeader(title = "文字频道")
                }
                items(textChannels, key = { it.id }) { channel ->
                    ChannelItem(
                        channel = channel,
                        isSelected = channel.id == currentChannelId,
                        onClick = { onChannelClick(channel) }
                    )
                }
            }

            // Voice channels section
            val voiceChannels = server?.channels?.filter { it.type == ChannelType.VOICE } ?: emptyList()
            if (voiceChannels.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    ChannelSectionHeader(title = "语音频道")
                }
                items(voiceChannels, key = { it.id }) { channel ->
                    ChannelItem(
                        channel = channel,
                        isSelected = channel.id == currentChannelId,
                        onClick = { onChannelClick(channel) }
                    )
                }
            }
        }

        // User panel at bottom
        UserPanel(
            username = username,
            onLogout = onLogout
        )
    }
}

@Composable
private fun ChannelSectionHeader(title: String) {
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
    }
}

@Composable
private fun ChannelItem(
    channel: Channel,
    isSelected: Boolean,
    onClick: () -> Unit
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
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (channel.type == ChannelType.TEXT) Icons.Default.Tag else Icons.Default.VolumeUp,
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

@Composable
private fun UserPanel(
    username: String,
    onLogout: () -> Unit
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
                    .background(DiscordBlurple),
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

            // Logout button
            IconButton(
                onClick = onLogout,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Logout,
                    contentDescription = "退出登录",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
