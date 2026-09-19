package cn.net.rms.chatroom.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.data.local.AppLocale
import cn.net.rms.chatroom.data.local.AppThemeMode
import cn.net.rms.chatroom.data.local.SettingsPreferences
import cn.net.rms.chatroom.data.local.ThemeMode
import cn.net.rms.chatroom.data.repository.ChatRepository
import cn.net.rms.chatroom.service.MessageConnectionService
import cn.net.rms.chatroom.ui.auth.AuthViewModel
import cn.net.rms.chatroom.ui.common.SplashContent
import cn.net.rms.chatroom.ui.navigation.NavGraph
import cn.net.rms.chatroom.ui.navigation.Screen
import cn.net.rms.chatroom.ui.theme.RMSDiscordTheme
import cn.net.rms.chatroom.ui.theme.Zhimo
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    override fun attachBaseContext(newBase: Context) {
        // Locale first, then theme: the forced night mode must reach the
        // values-night resources (splash colors, window background, status
        // bar appearance) resolved when the window is created, so a forced
        // LIGHT/DARK theme holds from the very first frame instead of
        // following the OS and flipping later.
        super.attachBaseContext(AppThemeMode.wrapContext(AppLocale.wrapContext(newBase)))
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("MainActivity", "Notification permission granted: $granted")
        if (!granted) {
            Toast.makeText(this, R.string.main_notification_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Re-push the stored theme to the platform per-app override (API 31+)
        // so the SystemUI cold-start splash follows the in-app choice even
        // when it was stored by an older build or restored from a backup.
        // Idempotent: same-value writes dispatch no visible change.
        AppThemeMode.apply(this, AppThemeMode.getStored(this))

        handleIntent(intent)
        requestNotificationPermission()

        setContent {
            // In-place locale switching (configChanges=locale): LocalContext must
            // stay the activity (hiltViewModel casts it to ComponentActivity),
            // so a locale switch re-resolves strings by shadowing
            // LocalConfiguration with a fresh instance per AppLocale.tick.
            // Compose's resource helpers read that local purely as the
            // invalidation key; the strings themselves come from the activity
            // Resources, which AppLocale reconfigures in place.
            val localeTick by AppLocale.tick
            val localeConfiguration = remember(localeTick) { Configuration(resources.configuration) }

            CompositionLocalProvider(LocalConfiguration provides localeConfiguration) {
                // Seed from the synchronous theme mirror so the first frame
                // (the splash overlay) already matches the stored choice;
                // DataStore then emits the same value with no visible flip.
                val themeMode by settingsPreferences.themeMode.collectAsState(
                    initial = remember { AppThemeMode.getStored(this@MainActivity) }
                )
                val systemDark = isSystemInDarkTheme()
                val darkTheme = when (themeMode) {
                    ThemeMode.SYSTEM -> systemDark
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }

                RMSDiscordTheme(darkTheme = darkTheme) {
                    val view = LocalView.current
                    // The splash overlay survives the isLoading->settled switch so
                    // the waveform hands off from rhythm to settle without a jump,
                    // then removes itself once its exit choreography finishes.
                    // Saveable so activity recreation does not replay the exit.
                    var splashVisible by rememberSaveable { mutableStateOf(true) }

                    // The splash paper follows the active theme, so system bar
                    // icon appearance is purely a function of darkTheme while
                    // the overlay is up. Registered deeper than the theme's own
                    // effect, it is the last writer on every frame it runs.
                    SideEffect {
                        val window = (view.context as Activity).window
                        val controller = WindowCompat.getInsetsController(window, view)
                        val lightBars = !darkTheme
                        controller.isAppearanceLightStatusBars = lightBars
                        controller.isAppearanceLightNavigationBars = lightBars
                    }

                    val navController = rememberNavController()
                    val authState by authViewModel.state.collectAsState()
                    val backgroundMessageServiceEnabled by settingsPreferences
                        .backgroundMessageServiceEnabled
                        .collectAsState(initial = false)

                    val context = this@MainActivity

                    LaunchedEffect(authState.isLoading, authState.isAuthenticated, backgroundMessageServiceEnabled) {
                        if (authState.isAuthenticated && backgroundMessageServiceEnabled) {
                            MessageConnectionService.start(context)
                        } else {
                            MessageConnectionService.stop(context)
                        }
                        if (!authState.isLoading && !authState.isAuthenticated) {
                            chatRepository.disconnectFromChannel()
                        }
                    }

                    LaunchedEffect(authState.isAuthenticated, authState.isLoading) {
                        if (!authState.isLoading) {
                            val currentRoute = navController.currentDestination?.route
                            if (authState.isAuthenticated && currentRoute == Screen.Login.route) {
                                navController.navigate(Screen.Main.route) {
                                    popUpTo(Screen.Login.route) { inclusive = true }
                                }
                            } else if (!authState.isAuthenticated && currentRoute == Screen.Main.route) {
                                navController.navigate(Screen.Login.route) {
                                    popUpTo(Screen.Main.route) { inclusive = true }
                                }
                            }
                        }
                    }

                    // Show error toast when entering main with network error
                    LaunchedEffect(authState.error) {
                        if (authState.isAuthenticated && authState.error != null) {
                            Toast.makeText(context, authState.error, Toast.LENGTH_LONG).show()
                            authViewModel.clearError()
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (!authState.isLoading) {
                            // Entry destination is decided once from the settled startup
                            // state: stored credentials land in main directly (validity
                            // is verified in the background); login is the no-token entry.
                            val authenticatedAtEntry = authState.isAuthenticated
                            val startDestination = remember {
                                if (authenticatedAtEntry) Screen.Main.route else Screen.Login.route
                            }
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = Zhimo.paper
                            ) {
                                NavGraph(
                                    navController = navController,
                                    startDestination = startDestination,
                                    onSsoLogin = { launchSsoLogin() },
                                    splashSettled = !splashVisible
                                )
                            }
                        }

                        if (splashVisible) {
                            SplashContent(
                                darkTheme = darkTheme,
                                settled = !authState.isLoading,
                                onExitFinished = { splashVisible = false }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        chatRepository.isAppInForeground = true
        chatRepository.cancelNotifications()
    }

    override fun onPause() {
        super.onPause()
        chatRepository.isAppInForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges=locale delivers locale changes here instead of
        // relaunching the activity (the relaunch gap is what flashed black).
        // Bumping the tick makes composition derive a fresh locale context so
        // stringResource readers re-resolve with the new configuration.
        AppLocale.tick.longValue++
    }

    private fun requestNotificationPermission() {
        Log.d("MainActivity", "Android SDK: ${Build.VERSION.SDK_INT}, TIRAMISU: ${Build.VERSION_CODES.TIRAMISU}")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            val isGranted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            val shouldShowRationale = shouldShowRequestPermissionRationale(permission)
            
            Log.d("MainActivity", "Permission granted: $isGranted, shouldShowRationale: $shouldShowRationale")
            
            when {
                isGranted -> {
                    Log.d("MainActivity", "Notification permission already granted")
                }
                shouldShowRationale -> {
                    Log.d("MainActivity", "Showing permission request (rationale)")
                    notificationPermissionLauncher.launch(permission)
                }
                else -> {
                    Log.d("MainActivity", "Launching permission request")
                    notificationPermissionLauncher.launch(permission)
                }
            }
        } else {
            // Android < 13: check if notifications are enabled in system settings
            Log.d("MainActivity", "Android < 13, checking notification enabled status")
            checkNotificationEnabled()
        }
    }
    
    private fun checkNotificationEnabled() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val areNotificationsEnabled = notificationManager.areNotificationsEnabled()
        Log.d("MainActivity", "Notifications enabled: $areNotificationsEnabled")
        
        if (!areNotificationsEnabled) {
            showNotificationPermissionDialog()
        }
    }
    
    private fun showNotificationPermissionDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.main_open_notification_settings)
            .setMessage(R.string.main_notification_permission_rationale)
            .setPositiveButton(R.string.main_go_to_settings) { _, _ ->
                val intent = Intent().apply {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.main_later, null)
            .show()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            Log.d("MainActivity", "handleIntent uri=$uri")
            when (uri.scheme) {
                "rmschatroom" -> {
                    when (uri.host) {
                        "callback" -> {
                            val accessToken = uri.getQueryParameter("access_token")
                                ?: uri.getQueryParameter("token") // fallback to legacy
                            val refreshToken = uri.getQueryParameter("refresh_token")

                            if (accessToken != null) {
                                Log.d("MainActivity", "callback accessToken.len=${accessToken.length} hasRefresh=${refreshToken != null}")
                                authViewModel.handleSsoCallback(accessToken, refreshToken)
                            } else {
                                Log.e("MainActivity", "callback: no token found in URI")
                            }
                        }
                        "voice-invite" -> {
                            // Handled by navigation deep link
                        }
                    }
                }
            }
        }
    }

    private fun launchSsoLogin() {
        val ssoUrl = buildSsoUrl()
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.launchUrl(this, Uri.parse(ssoUrl))
    }

    private fun buildSsoUrl(): String {
        val redirectUrl = "rmschatroom://callback"
        val baseUrl = BuildConfig.API_BASE_URL
        return "$baseUrl/api/auth/login?redirect_url=$redirectUrl"
    }
}
