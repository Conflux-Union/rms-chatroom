package cn.net.rms.chatroom.crash

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.data.local.SettingsPreferences
import cn.net.rms.chatroom.data.local.ThemeMode
import cn.net.rms.chatroom.ui.MainActivity
import cn.net.rms.chatroom.ui.theme.RMSDiscordTheme
import cn.net.rms.chatroom.ui.theme.Zhimo
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Crash report screen launched by [CrashGuard] in a fresh process. Shows the
 * crash dialog with the same bug-report action as the main top bar, plus
 * restart/exit; telemetry (if enabled) was already persisted by the dying
 * process and uploads from this process's Application.onCreate.
 */
@AndroidEntryPoint
class CrashReportActivity : ComponentActivity() {

    private val viewModel: CrashReportViewModel by viewModels()

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before anything else can throw: a crash here means this screen is
        // broken and CrashGuard must fall back to the platform handler.
        CrashGuard.markCrashScreenActive(true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by settingsPreferences.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            RMSDiscordTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = Zhimo.paper) {
                    val state = viewModel.state
                    val telemetryEnabled by settingsPreferences.telemetryEnabled
                        .collectAsState(initial = null)
                    val clipboardManager = LocalClipboardManager.current
                    CrashReportDialog(
                        state = state,
                        crashText = viewModel.crashText,
                        telemetryEnabled = telemetryEnabled,
                        onSubmit = viewModel::submitReport,
                        onCopyReportId = { reportId ->
                            clipboardManager.setText(AnnotatedString(reportId))
                            Toast.makeText(this, getString(R.string.crash_report_id_copied), Toast.LENGTH_SHORT).show()
                        },
                        onRestart = ::restartApp,
                        onExit = ::finishAffinity
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CrashGuard.markCrashScreenActive(true)
    }

    override fun onPause() {
        super.onPause()
        CrashGuard.markCrashScreenActive(false)
    }

    private fun restartApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }
}

@Composable
private fun CrashReportDialog(
    state: CrashReportState,
    crashText: String?,
    telemetryEnabled: Boolean?,
    onSubmit: () -> Unit,
    onCopyReportId: (String) -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit
) {
    BackHandler(onBack = onExit)

    if (state is CrashReportState.Success) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.bug_report_success)) },
            text = { Text(stringResource(R.string.bug_report_success_id, state.reportId)) },
            confirmButton = {
                TextButton(onClick = onRestart) { Text(stringResource(R.string.action_restart_app)) }
            },
            dismissButton = {
                TextButton(onClick = { onCopyReportId(state.reportId) }) { Text(stringResource(R.string.action_copy_id)) }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column {
                Text(stringResource(R.string.crash_body))
                val hint = when (telemetryEnabled) {
                    true -> stringResource(R.string.crash_telemetry_on)
                    false -> stringResource(R.string.crash_telemetry_off)
                    null -> null
                }
                hint?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = 4.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Stack excerpt, not the full report: enough to recognize the
                // crash, the complete trace travels with the bug report zip.
                crashText?.let { text ->
                    Text(
                        text = text.substringAfter("\n\n").take(800),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state is CrashReportState.Failed) {
                    Text(
                        text = stringResource(R.string.crash_report_failed, state.reason),
                        modifier = Modifier.padding(top = 8.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = state != CrashReportState.Submitting
            ) {
                if (state == CrashReportState.Submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Text(text = stringResource(R.string.crash_submitting), modifier = Modifier.padding(start = 6.dp))
                } else {
                    Text(stringResource(R.string.crash_report_action))
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRestart) { Text(stringResource(R.string.action_restart_app)) }
                TextButton(onClick = onExit) { Text(stringResource(R.string.action_exit)) }
            }
        }
    )
}
