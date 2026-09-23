package cn.net.rms.chatroom.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.data.local.AppLanguage
import cn.net.rms.chatroom.data.local.AppLocale
import cn.net.rms.chatroom.data.local.ThemeMode
import cn.net.rms.chatroom.ui.theme.Zhimo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val floatingWindowEnabled by viewModel.floatingWindowEnabled.collectAsState()
    val backgroundMessageServiceEnabled by viewModel.backgroundMessageServiceEnabled.collectAsState()
    val telemetryEnabled by viewModel.telemetryEnabled.collectAsState()
    val voiceJoinLeaveAnnouncements by viewModel.voiceJoinLeaveAnnouncements.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsState()
    val isIgnoringBatteryOptimization by viewModel.isIgnoringBatteryOptimization.collectAsState()
    // Not routed through DataStore: the effective language is owned by
    // AppLocale (SharedPreferences + Locale Manager). The switch applies in
    // place (no activity relaunch), so key the read on AppLocale.tick to pick
    // up a change made on this very screen.
    val language = remember(AppLocale.tick.longValue) { AppLocale.current(context) }

    // Refresh overlay permission when screen resumes
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshOverlayPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            // Appearance section
            SettingsSectionHeader(title = stringResource(R.string.settings_section_appearance))

            ThemeModeItem(
                mode = themeMode,
                onChange = { viewModel.setThemeMode(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Language section
            SettingsSectionHeader(title = stringResource(R.string.settings_section_language))

            LanguageItem(
                current = language,
                onChange = { selected ->
                    // Applies in place: apply() bumps AppLocale.tick, which
                    // swaps the composition's locale context (and, on API 33+,
                    // the framework keeps LocaleManager in sync for the system
                    // per-app language UI). No activity relaunch, no black gap.
                    AppLocale.apply(context, selected)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Voice call section
            SettingsSectionHeader(title = stringResource(R.string.settings_section_voice))

            // Floating window toggle
            SettingsItem(
                icon = Icons.Default.PictureInPicture,
                title = stringResource(R.string.settings_floating_window_title),
                subtitle = if (hasOverlayPermission) {
                    stringResource(R.string.settings_floating_window_desc_on)
                } else {
                    stringResource(R.string.settings_floating_window_desc_off)
                },
                trailing = {
                    Switch(
                        checked = floatingWindowEnabled && hasOverlayPermission,
                        onCheckedChange = { enabled ->
                            if (!hasOverlayPermission && enabled) {
                                // Open overlay permission settings
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } else {
                                viewModel.setFloatingWindowEnabled(enabled)
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Zhimo.seal)
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Voice join/leave TTS announcements
            SettingsItem(
                icon = Icons.Default.RecordVoiceOver,
                title = stringResource(R.string.settings_voice_announcements_title),
                subtitle = if (voiceJoinLeaveAnnouncements) {
                    stringResource(R.string.settings_voice_announcements_desc_on)
                } else {
                    stringResource(R.string.settings_voice_announcements_desc_off)
                },
                trailing = {
                    Switch(
                        checked = voiceJoinLeaveAnnouncements,
                        onCheckedChange = { enabled ->
                            viewModel.setVoiceJoinLeaveAnnouncements(enabled)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Zhimo.seal)
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSectionHeader(title = stringResource(R.string.settings_section_notifications))

            SettingsItem(
                icon = Icons.Default.NotificationsActive,
                title = stringResource(R.string.settings_background_service_title),
                subtitle = if (backgroundMessageServiceEnabled) {
                    stringResource(R.string.settings_background_service_desc_on)
                } else {
                    stringResource(R.string.settings_background_service_desc_off)
                },
                trailing = {
                    Switch(
                        checked = backgroundMessageServiceEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setBackgroundMessageServiceEnabled(enabled)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Zhimo.seal)
                    )
                }
            )

            if (backgroundMessageServiceEnabled && !isIgnoringBatteryOptimization) {
                SettingsItem(
                    icon = Icons.Default.BatterySaver,
                    title = stringResource(R.string.settings_battery_title),
                    subtitle = stringResource(R.string.settings_battery_desc),
                    onClick = { viewModel.openBatteryOptimizationSettings() }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSectionHeader(title = stringResource(R.string.settings_section_privacy))

            SettingsItem(
                icon = Icons.Default.BugReport,
                title = stringResource(R.string.settings_telemetry_title),
                subtitle = if (telemetryEnabled) {
                    stringResource(R.string.settings_telemetry_desc_on)
                } else {
                    stringResource(R.string.settings_telemetry_desc_off)
                },
                trailing = {
                    Switch(
                        checked = telemetryEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.setTelemetryEnabled(enabled)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Zhimo.seal)
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // About section
            SettingsSectionHeader(title = stringResource(R.string.settings_section_about))

            // About app
            SettingsItem(
                icon = Icons.Default.Info,
                title = stringResource(R.string.settings_about_title),
                subtitle = stringResource(R.string.settings_about_desc),
                onClick = onNavigateToAbout
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = Zhimo.seal,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

// Theme appearance picker. Segmented buttons inherit the zhimo shapes and the
// secondaryContainer slot (paperHover in both themes) from MaterialTheme.
@Composable
private fun ThemeModeItem(
    mode: ThemeMode,
    onChange: (ThemeMode) -> Unit
) {
    val options = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.theme_follow_system),
        ThemeMode.LIGHT to stringResource(R.string.theme_light),
        ThemeMode.DARK to stringResource(R.string.theme_dark)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.DarkMode,
            contentDescription = null,
            tint = Zhimo.inkFaint,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_theme_title),
                style = MaterialTheme.typography.bodyLarge,
                color = Zhimo.ink
            )
            Text(
                text = stringResource(R.string.settings_theme_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Zhimo.inkFaint
            )

            Spacer(modifier = Modifier.height(10.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, text) ->
                    SegmentedButton(
                        selected = mode == value,
                        onClick = { onChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        )
                    ) {
                        Text(text)
                    }
                }
            }
        }
    }
}

// In-app language picker. Mirrors the theme picker; the change is applied by
// AppLocale (persisted + LocaleManager on API 33+) and lands in place via the
// composition's locale context instead of an activity relaunch. The zh and en
// option labels stay in their own language in every locale.
@Composable
private fun LanguageItem(
    current: AppLanguage,
    onChange: (AppLanguage) -> Unit
) {
    val options = listOf(
        AppLanguage.SYSTEM to stringResource(R.string.language_system),
        AppLanguage.ZH to stringResource(R.string.language_zh),
        AppLanguage.EN to stringResource(R.string.language_en)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Language,
            contentDescription = null,
            tint = Zhimo.inkFaint,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_language_title),
                style = MaterialTheme.typography.bodyLarge,
                color = Zhimo.ink
            )
            Text(
                text = stringResource(R.string.settings_language_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Zhimo.inkFaint
            )

            Spacer(modifier = Modifier.height(10.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, text) ->
                    SegmentedButton(
                        selected = current == value,
                        onClick = { onChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        )
                    ) {
                        Text(text)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
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

        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Zhimo.inkFaint
            )
        }
    }
}
