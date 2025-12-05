package cn.net.rms.chatroom.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.data.repository.ChatRepository
import cn.net.rms.chatroom.ui.auth.AuthViewModel
import cn.net.rms.chatroom.ui.navigation.NavGraph
import cn.net.rms.chatroom.ui.navigation.Screen
import cn.net.rms.chatroom.ui.theme.RMSDiscordTheme
import cn.net.rms.chatroom.ui.theme.SurfaceDarker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    @Inject
    lateinit var chatRepository: ChatRepository

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("MainActivity", "Notification permission granted: $granted")
        if (!granted) {
            Toast.makeText(this, "需要通知权限才能接收消息提醒", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().apply {
            setKeepOnScreenCondition {
                authViewModel.state.value.isLoading
            }
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)
        requestNotificationPermission()

        setContent {
            RMSDiscordTheme {
                val navController = rememberNavController()
                val authState by authViewModel.state.collectAsState()

                val context = this@MainActivity
                
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

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SurfaceDarker
                ) {
                    NavGraph(
                        navController = navController,
                        startDestination = Screen.Login.route,
                        onSsoLogin = { launchSsoLogin() }
                    )
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
            .setTitle("开启通知")
            .setMessage("开启通知权限后，您可以及时收到新消息提醒，不错过重要信息。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent().apply {
                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            Log.d("MainActivity", "handleIntent uri=$uri")
            when (uri.scheme) {
                "rmschatroom" -> {
                    when (uri.host) {
                        "callback" -> {
                            uri.getQueryParameter("token")?.let { token ->
                                Log.d("MainActivity", "callback token length=${token.length}")
                                authViewModel.handleSsoCallback(token)
                            } ?: run {
                                Log.e("MainActivity", "callback token missing")
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
