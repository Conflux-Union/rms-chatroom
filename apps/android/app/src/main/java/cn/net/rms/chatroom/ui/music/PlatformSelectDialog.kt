package cn.net.rms.chatroom.ui.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.ui.theme.*

@Composable
fun PlatformSelectDialog(
    qqLoggedIn: Boolean,
    neteaseLoggedIn: Boolean,
    onSelectPlatform: (String) -> Unit,
    onDismiss: () -> Unit
) {
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
                    text = stringResource(R.string.music_select_platform),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Zhimo.ink
                )

                Spacer(modifier = Modifier.height(24.dp))

                // QQ Music button
                if (!qqLoggedIn) {
                    Button(
                        onClick = { onSelectPlatform("qq") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10B981)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.music_qq_name),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // NetEase Music button
                if (!neteaseLoggedIn) {
                    Button(
                        onClick = { onSelectPlatform("netease") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE60026)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.music_netease_name),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Close button
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel), color = Zhimo.inkFaint)
                }
            }
        }
    }
}
