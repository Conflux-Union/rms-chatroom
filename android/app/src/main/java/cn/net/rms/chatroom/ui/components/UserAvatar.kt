package cn.net.rms.chatroom.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private val AvatarColors = listOf(
    Color(0xFF5865F2), // Discord Blurple
    Color(0xFF57F287), // Green
    Color(0xFFFEE75C), // Yellow
    Color(0xFFEB459E), // Fuchsia
    Color(0xFFED4245), // Red
    Color(0xFF3BA55C), // Green Alt
    Color(0xFFFAA61A), // Orange
    Color(0xFF9B59B6), // Purple
    Color(0xFFE91E63), // Pink
    Color(0xFF00BCD4)  // Cyan
)

/**
 * User avatar component that displays the first letter of username
 * with a stable background color based on username hashCode.
 */
@Composable
fun UserAvatar(
    username: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val backgroundColor = remember(username) {
        val index = abs(username.hashCode()) % AvatarColors.size
        AvatarColors[index]
    }

    val initial = remember(username) {
        username.firstOrNull()?.uppercase() ?: "?"
    }

    // Calculate font size based on avatar size
    val fontSize = remember(size) {
        (size.value * 0.45f).sp
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
    }
}
