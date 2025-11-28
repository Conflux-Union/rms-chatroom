package com.rms.discord.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.rms.discord.BuildConfig
import com.rms.discord.data.repository.ChatRepository
import com.rms.discord.ui.auth.AuthViewModel
import com.rms.discord.ui.navigation.NavGraph
import com.rms.discord.ui.navigation.Screen
import com.rms.discord.ui.theme.RMSDiscordTheme
import com.rms.discord.ui.theme.SurfaceDarker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    @Inject
    lateinit var chatRepository: ChatRepository

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled silently */ }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            when (uri.scheme) {
                "rmsdiscord" -> {
                    when (uri.host) {
                        "callback" -> {
                            uri.getQueryParameter("token")?.let { token ->
                                authViewModel.handleSsoCallback(token)
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
        val redirectUrl = "rmsdiscord://callback"
        val baseUrl = BuildConfig.API_BASE_URL
        return "$baseUrl/api/auth/login?redirect_url=$redirectUrl"
    }
}
