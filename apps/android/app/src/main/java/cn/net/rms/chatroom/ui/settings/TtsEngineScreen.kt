package cn.net.rms.chatroom.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.data.tts.TtsModelState
import cn.net.rms.chatroom.ui.theme.Zhimo
import java.util.Locale

/**
 * Voice-announcement engine management: shows what currently speaks (local
 * pack vs system TTS) and guides the user through downloading/deleting the
 * on-device kokoro pack for devices without a usable system voice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsEngineScreen(
    onNavigateBack: () -> Unit,
    viewModel: TtsEngineViewModel = hiltViewModel()
) {
    val packState by viewModel.packState.collectAsState()
    val systemStatus by viewModel.systemStatus.collectAsState()
    val installedBytes by viewModel.installedBytes.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    val systemUsable = systemStatus is SystemTtsStatus.Available

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_tts_engine_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Zhimo.paperSubtle)
            )
        },
        containerColor = Zhimo.paperSubtle
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            TtsSectionHeader(title = stringResource(R.string.tts_engine_status_section))

            StatusRow(
                title = stringResource(R.string.tts_engine_current),
                value = if (packState is TtsModelState.Ready) {
                    stringResource(R.string.tts_engine_local_engine)
                } else {
                    stringResource(R.string.tts_engine_system_engine)
                },
                highlight = packState is TtsModelState.Ready
            )

            StatusRow(
                title = stringResource(R.string.tts_engine_system_row),
                value = when (val status = systemStatus) {
                    is SystemTtsStatus.Checking ->
                        stringResource(R.string.tts_engine_system_checking)
                    is SystemTtsStatus.Available ->
                        stringResource(R.string.tts_engine_system_ok, status.languageTag)
                    SystemTtsStatus.NoVoiceForLanguage ->
                        stringResource(R.string.tts_engine_system_no_voice)
                    SystemTtsStatus.Unavailable ->
                        stringResource(R.string.tts_engine_system_unavailable)
                },
                highlight = systemUsable
            )

            TtsSectionHeader(title = stringResource(R.string.tts_engine_pack_section))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(Zhimo.paperRaised, RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Zhimo.inkFaint,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.tts_engine_pack_name),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = Zhimo.ink
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.tts_engine_pack_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Zhimo.inkFaint
                )

                Spacer(modifier = Modifier.height(16.dp))

                when (val state = packState) {
                    TtsModelState.NotInstalled -> {
                        if (!systemUsable) {
                            Text(
                                text = stringResource(R.string.tts_engine_guidance_no_system),
                                style = MaterialTheme.typography.bodySmall,
                                color = Zhimo.seal
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Button(
                            onClick = { viewModel.startInstall() },
                            colors = ButtonDefaults.buttonColors(containerColor = Zhimo.seal),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text(
                                stringResource(
                                    R.string.tts_engine_download_action,
                                    formatBytes(viewModel.totalDownloadBytes())
                                )
                            )
                        }
                    }

                    is TtsModelState.Downloading -> {
                        val phaseLabel = stringResource(
                            if (state.phase == TtsModelState.Downloading.Phase.RUNTIME) {
                                R.string.tts_engine_phase_runtime
                            } else {
                                R.string.tts_engine_phase_model
                            }
                        )
                        Text(
                            text = stringResource(
                                R.string.tts_engine_progress,
                                phaseLabel,
                                state.percent
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Zhimo.ink
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = viewModel::cancelInstall,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }

                    TtsModelState.Installing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.tts_engine_installing),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Zhimo.ink
                            )
                        }
                    }

                    is TtsModelState.Ready -> {
                        Text(
                            text = stringResource(
                                R.string.tts_engine_installed,
                                formatBytes(installedBytes)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Zhimo.ink
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.tts_engine_pack_version,
                                state.packVersion,
                                "kokoro int8 v1.1"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Zhimo.inkFaint
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.tts_engine_delete_action))
                        }
                    }

                    is TtsModelState.Failed -> {
                        Text(
                            text = stringResource(R.string.tts_engine_failed, state.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.startInstall() },
                            colors = ButtonDefaults.buttonColors(containerColor = Zhimo.seal),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text(stringResource(R.string.tts_engine_retry))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.tts_engine_footer),
                style = MaterialTheme.typography.bodySmall,
                color = Zhimo.inkFaint,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tts_engine_delete_confirm_title)) },
            text = { Text(stringResource(R.string.tts_engine_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) {
                    Text(stringResource(R.string.tts_engine_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun TtsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = Zhimo.seal,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun StatusRow(title: String, value: String, highlight: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = Zhimo.ink,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) Zhimo.seal else Zhimo.inkFaint,
            fontWeight = if (highlight) FontWeight.Medium else FontWeight.Normal
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, if (value >= 100.0) "%.0f %s" else "%.1f %s", value, units[index])
}
