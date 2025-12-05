package cn.net.rms.chatroom.ui.music

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cn.net.rms.chatroom.ui.theme.*

@Composable
fun MusicLoginDialog(
    qrCodeUrl: String?,
    loginStatus: String,
    loginPlatform: String = "qq",
    onRefreshQRCode: () -> Unit,
    onDismiss: () -> Unit
) {
    val platformName = if (loginPlatform == "qq") "QQ 音乐" else "网易云音乐"
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 300.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "扫码登录 $platformName",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // QR Code
                if (qrCodeUrl != null) {
                    val bitmap = remember(qrCodeUrl) {
                        try {
                            // Parse base64 data URL
                            val base64Data = qrCodeUrl.substringAfter("base64,")
                            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code",
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceLight),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "二维码加载失败",
                                color = TextMuted
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceLight),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = TiColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Status text
                Text(
                    text = when (loginStatus) {
                        "loading" -> "加载中..."
                        "waiting" -> "等待扫码..."
                        "scanned" -> "扫码成功！请在手机上确认..."
                        "expired" -> "二维码已过期"
                        "refused" -> "登录被拒绝"
                        "success" -> "登录成功！"
                        "error" -> "加载失败"
                        else -> "加载中..."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (loginStatus) {
                        "success" -> VoiceConnected
                        "expired", "refused", "error" -> DiscordRed
                        "scanned" -> DiscordYellow
                        else -> TextMuted
                    },
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Refresh button (for expired QR code)
                if (loginStatus == "expired" || loginStatus == "error") {
                    Button(
                        onClick = onRefreshQRCode,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TiColor
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("刷新二维码")
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Close button
                TextButton(onClick = onDismiss) {
                    Text("关闭", color = TextMuted)
                }
            }
        }
    }
}
