package cn.net.rms.chatroom.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.ui.theme.Zhimo
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    onNavigateBack: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val stats by viewModel.stats.collectAsState()
    val clearing by viewModel.clearing.collectAsState()
    var confirmChatDbClear by remember { mutableStateOf(false) }

    fun reportFreed(freed: Long?) {
        val message = if (freed == null || freed <= 0L) {
            context.getString(R.string.storage_nothing_to_clean)
        } else {
            context.getString(R.string.storage_cleared, formatBytes(freed))
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_storage_title)) },
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
        val s = stats
        if (s == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            DeviceStorageCard(stats = s)

            StorageSectionHeader(
                title = stringResource(R.string.storage_app_section),
                trailing = formatBytes(s.appTotalBytes)
            )

            s.categories.forEach { category ->
                StorageCategoryRow(
                    category = category,
                    enabled = !clearing,
                    onClear = {
                        if (category.kind == StorageCategoryKind.CHAT_DB) {
                            confirmChatDbClear = true
                        } else {
                            viewModel.clearCategory(category.kind, ::reportFreed)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { viewModel.clearCaches(::reportFreed) },
                enabled = !clearing && s.cacheBytes > 0L,
                colors = ButtonDefaults.buttonColors(containerColor = Zhimo.seal),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
            ) {
                if (clearing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Zhimo.paper,
                        strokeWidth = 2.dp
                    )
                } else if (s.cacheBytes > 0L) {
                    Text(stringResource(R.string.storage_clear_all_with_size, formatBytes(s.cacheBytes)))
                } else {
                    Text(stringResource(R.string.storage_clear_all))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (confirmChatDbClear) {
        AlertDialog(
            onDismissRequest = { confirmChatDbClear = false },
            title = { Text(stringResource(R.string.storage_clear_chat_db_title)) },
            text = { Text(stringResource(R.string.storage_clear_chat_db_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmChatDbClear = false
                    viewModel.clearCategory(StorageCategoryKind.CHAT_DB, ::reportFreed)
                }) {
                    Text(stringResource(R.string.storage_action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmChatDbClear = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun DeviceStorageCard(stats: StorageStats) {
    val deviceUsedFraction = if (stats.deviceTotalBytes > 0L) {
        (stats.deviceUsedBytes.toFloat() / stats.deviceTotalBytes).coerceIn(0f, 1f)
    } else 0f
    val appFraction = if (stats.deviceTotalBytes > 0L) {
        (stats.appTotalBytes.toFloat() / stats.deviceTotalBytes).coerceIn(0f, 1f)
    } else 0f
    val appPercent = (appFraction * 100).let { if (it < 1f && it > 0f) "<1" else it.roundToInt().toString() }
    val otherUsedBytes = (stats.deviceUsedBytes - stats.appTotalBytes).coerceAtLeast(0L)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Zhimo.paperRaised, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.storage_device_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Zhimo.ink,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.storage_device_total, formatBytes(stats.deviceTotalBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = Zhimo.inkFaint
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Single stacked bar: the app share (seal) sits inside the device-wide
        // used span (ink muted); what is left of the track is free space.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Zhimo.paperHover)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(deviceUsedFraction)
                    .background(Zhimo.inkMuted)
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(appFraction)
                    .background(Zhimo.seal)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        StorageLegendRow(
            color = Zhimo.seal,
            label = stringResource(R.string.storage_app_title),
            value = stringResource(
                R.string.storage_legend_app_value,
                formatBytes(stats.appTotalBytes),
                appPercent
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        StorageLegendRow(
            color = Zhimo.inkMuted,
            label = stringResource(R.string.storage_device_other_used),
            value = stringResource(
                R.string.storage_legend_other_value,
                formatBytes(otherUsedBytes),
                formatBytes(stats.deviceAvailableBytes)
            )
        )
    }
}

@Composable
private fun StorageLegendRow(color: Color, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Zhimo.ink
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Zhimo.inkMuted,
            modifier = Modifier
                .weight(1f)
                .wrapContentWidth(Alignment.End)
        )
    }
}

@Composable
private fun StorageSectionHeader(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Zhimo.seal,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelLarge,
            color = Zhimo.inkMuted
        )
    }
}

@Composable
private fun StorageCategoryRow(
    category: StorageCategory,
    enabled: Boolean,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = category.icon(),
            contentDescription = null,
            tint = Zhimo.inkFaint,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.label(),
                style = MaterialTheme.typography.bodyLarge,
                color = Zhimo.ink
            )
            category.description()?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = Zhimo.inkFaint
                )
            }
        }

        Text(
            text = formatBytes(category.bytes),
            style = MaterialTheme.typography.bodyMedium,
            color = Zhimo.inkMuted
        )

        if (category.cleanable && category.bytes > 0L) {
            Spacer(modifier = Modifier.width(12.dp))
            TextButton(
                onClick = onClear,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Zhimo.seal),
                modifier = Modifier.height(36.dp)
            ) {
                Text(stringResource(R.string.storage_action_clear))
            }
        }
    }
}

@Composable
private fun StorageCategory.icon(): ImageVector = when (kind) {
    StorageCategoryKind.IMAGE_CACHE -> Icons.Default.Image
    StorageCategoryKind.OTHER_CACHE -> Icons.Default.Folder
    StorageCategoryKind.UPDATES -> Icons.Default.SystemUpdate
    StorageCategoryKind.CHAT_DB -> Icons.Default.Forum
    StorageCategoryKind.APP_DATA -> Icons.Default.Tune
    StorageCategoryKind.APP_PROGRAM -> Icons.Default.Apps
}

@Composable
private fun StorageCategory.label(): String = when (kind) {
    StorageCategoryKind.IMAGE_CACHE -> stringResource(R.string.storage_category_image_cache)
    StorageCategoryKind.OTHER_CACHE -> stringResource(R.string.storage_category_other_cache)
    StorageCategoryKind.UPDATES -> stringResource(R.string.storage_category_updates)
    StorageCategoryKind.CHAT_DB -> stringResource(R.string.storage_category_chat_db)
    StorageCategoryKind.APP_DATA -> stringResource(R.string.storage_category_app_data)
    StorageCategoryKind.APP_PROGRAM -> stringResource(R.string.storage_category_app_program)
}

@Composable
private fun StorageCategory.description(): String? = when (kind) {
    StorageCategoryKind.CHAT_DB -> stringResource(R.string.storage_category_chat_db_desc)
    StorageCategoryKind.APP_DATA -> stringResource(R.string.storage_category_app_data_desc)
    StorageCategoryKind.APP_PROGRAM -> stringResource(R.string.storage_category_app_program_desc)
    else -> null
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
