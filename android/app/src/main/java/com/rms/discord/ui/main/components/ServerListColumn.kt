package com.rms.discord.ui.main.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.rms.discord.ui.theme.DiscordBlurple
import com.rms.discord.ui.theme.SurfaceDarker
import com.rms.discord.ui.theme.SurfaceLight

@Composable
fun ServerListColumn(
    servers: List<Server>,
    currentServerId: Long?,
    onServerClick: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .fillMaxHeight()
            .background(SurfaceDarker)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(servers, key = { it.id }) { server ->
                ServerItem(
                    server = server,
                    isSelected = server.id == currentServerId,
                    onClick = { onServerClick(server.id) }
                )
            }
        }
    }
}

@Composable
private fun ServerItem(
    server: Server,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val indicatorHeight by animateDpAsState(
        targetValue = if (isSelected) 40.dp else 8.dp,
        animationSpec = tween(200),
        label = "indicatorHeight"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) DiscordBlurple else SurfaceLight,
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
                .clickable(onClick = onClick),
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
