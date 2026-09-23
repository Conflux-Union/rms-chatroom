package cn.net.rms.chatroom.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.ui.common.ChangelogList
import cn.net.rms.chatroom.ui.theme.Zhimo

private const val GITHUB_REPO_URL = "https://github.com/Conflux-Union/rms-chatroom"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    updateCheckViewModel: UpdateCheckViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val updateCheckState by updateCheckViewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about_title)) },
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
            // Version
            AboutItem(
                icon = Icons.Default.Info,
                title = stringResource(R.string.about_version),
                subtitle = BuildConfig.VERSION_NAME
            )

            // Copyright
            AboutItem(
                icon = Icons.Default.Copyright,
                title = stringResource(R.string.about_copyright),
                subtitle = stringResource(R.string.about_copyright_value)
            )

            // Manual update check
            AboutItem(
                icon = Icons.Default.SystemUpdate,
                title = stringResource(R.string.update_check),
                subtitle = when (updateCheckState) {
                    UpdateCheckState.Checking -> stringResource(R.string.update_checking)
                    else -> null
                },
                onClick = { updateCheckViewModel.checkForUpdate() }
            )

            // GitHub repository
            AboutItem(
                icon = ImageVector.vectorResource(R.drawable.ic_github),
                title = stringResource(R.string.about_github),
                subtitle = "Conflux-Union/rms-chatroom",
                onClick = {
                    runCatching {
                        CustomTabsIntent.Builder()
                            .setShowTitle(true)
                            .build()
                            .launchUrl(context, Uri.parse(GITHUB_REPO_URL))
                    }.onFailure {
                        Toast.makeText(context, context.getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // Open source licenses
            AboutItem(
                icon = Icons.Default.Code,
                title = stringResource(R.string.about_licenses),
                subtitle = stringResource(R.string.about_licenses_desc),
                onClick = onNavigateToLicenses
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Manual check results
    when (val s = updateCheckState) {
        UpdateCheckState.UpToDate -> AlertDialog(
            onDismissRequest = { updateCheckViewModel.dismissResult() },
            title = { Text(stringResource(R.string.update_up_to_date)) },
            confirmButton = {
                TextButton(onClick = { updateCheckViewModel.dismissResult() }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )

        is UpdateCheckState.UpdateAvailable -> {
            val update = s.update
            AlertDialog(
                onDismissRequest = { updateCheckViewModel.dismissResult() },
                title = { Text(stringResource(R.string.update_available)) },
                text = {
                    Column {
                        Text(stringResource(R.string.update_version_label, update.versionName))
                        update.changelog?.let { changelog ->
                            Spacer(modifier = Modifier.height(10.dp))
                            ChangelogList(changelog)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { updateCheckViewModel.downloadUpdate(update) }) {
                        Text(stringResource(R.string.update_download))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateCheckViewModel.dismissResult() }) {
                        Text(stringResource(R.string.main_later))
                    }
                }
            )
        }

        is UpdateCheckState.Downloading -> {
            val update = s.update
            AlertDialog(
                onDismissRequest = { updateCheckViewModel.dismissResult() },
                title = { Text(stringResource(R.string.update_available)) },
                text = {
                    Column {
                        Text(stringResource(R.string.update_version_label, update.versionName))
                        update.changelog?.let { changelog ->
                            Spacer(modifier = Modifier.height(10.dp))
                            ChangelogList(changelog)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.update_started_download),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { updateCheckViewModel.dismissResult() }) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
            )
        }

        UpdateCheckState.Failed -> AlertDialog(
            onDismissRequest = { updateCheckViewModel.dismissResult() },
            title = { Text(stringResource(R.string.update_check_failed)) },
            confirmButton = {
                TextButton(onClick = { updateCheckViewModel.checkForUpdate() }) {
                    Text(stringResource(R.string.action_retry))
                }
            },
            dismissButton = {
                TextButton(onClick = { updateCheckViewModel.dismissResult() }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )

        UpdateCheckState.Checking, UpdateCheckState.Idle -> Unit
    }
}

@Composable
private fun AboutItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Zhimo.inkFaint,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Zhimo.ink
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Zhimo.inkFaint
                )
            }
        }

        if (onClick != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Zhimo.inkFaint
            )
        }
    }
}
