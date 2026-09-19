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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.ui.theme.*

@Composable
fun MusicLoginDialog(
    qrCodeUrl: String?,
    loginStatus: String,
    loginPlatform: String = "qq",
    onRefreshQRCode: () -> Unit,
    onDismiss: () -> Unit
) {
    val platformName = if (loginPlatform == "qq") stringResource(R.string.music_qq_name) else stringResource(R.string.music_netease_name)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Zhimo.paperSubtle
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 300.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.music_scan_login, platformName),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Zhimo.ink
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
                                .background(Zhimo.paperHover),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.music_qrcode_failed_load),
                                color = Zhimo.inkFaint
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Zhimo.paperHover),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Zhimo.seal,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Status text
                Text(
                    text = when (loginStatus) {
                        "loading" -> stringResource(R.string.loading)
                        "waiting" -> stringResource(R.string.music_status_waiting)
                        "scanned" -> stringResource(R.string.music_status_scanned)
                        "expired" -> stringResource(R.string.music_status_expired)
                        "refused" -> stringResource(R.string.music_status_refused)
                        "success" -> stringResource(R.string.music_status_success)
                        "error" -> stringResource(R.string.music_status_error)
                        else -> stringResource(R.string.loading)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (loginStatus) {
                        "success" -> Zhimo.success
                        "expired", "refused", "error" -> Zhimo.danger
                        "scanned" -> Zhimo.warning
                        else -> Zhimo.inkFaint
                    },
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Refresh button (for expired QR code)
                if (loginStatus == "expired" || loginStatus == "error") {
                    Button(
                        onClick = onRefreshQRCode,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Zhimo.seal
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.music_refresh_qrcode))
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Close button
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close), color = Zhimo.inkFaint)
                }
            }
        }
    }
}
