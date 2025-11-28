package com.rms.discord.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rms.discord.ui.theme.TiColor
import com.rms.discord.ui.theme.TextMuted
import com.rms.discord.ui.theme.TextSecondary

/**
 * Animated loading indicator with pulsing effect
 */
@Composable
fun LoadingContent(
    modifier: Modifier = Modifier,
    message: String = "加载中..."
) {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .scale(scale),
                color = TiColor,
                strokeWidth = 4.dp
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
        }
    }
}

/**
 * Empty state placeholder with customizable icon and message
 */
@Composable
fun EmptyContent(
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Inbox,
    title: String,
    description: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = TextMuted
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
            }
            if (actionText != null && onAction != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onAction,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TiColor
                    )
                ) {
                    Text(actionText)
                }
            }
        }
    }
}

/**
 * Error state with retry option
 */
@Composable
fun ErrorContent(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: (() -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = "出错了",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TiColor
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重试")
                }
            }
        }
    }
}

/**
 * Network error state
 */
@Composable
fun NetworkErrorContent(
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = TextMuted
            )
            Text(
                text = "网络连接失败",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
            )
            Text(
                text = "请检查网络连接后重试",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                textAlign = TextAlign.Center
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TiColor
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重试")
                }
            }
        }
    }
}

/**
 * Shimmer loading effect for skeleton screens
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    baseColor: Color = TextMuted.copy(alpha = 0.1f),
    highlightColor: Color = TextMuted.copy(alpha = 0.2f)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    Surface(
        modifier = modifier,
        color = baseColor.copy(alpha = alpha),
        shape = MaterialTheme.shapes.small
    ) {}
}
