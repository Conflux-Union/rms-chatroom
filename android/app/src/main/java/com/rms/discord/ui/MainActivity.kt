package com.rms.discord.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.rms.discord.BuildConfig
import com.rms.discord.ui.auth.AuthViewModel
import com.rms.discord.ui.navigation.NavGraph
import com.rms.discord.ui.navigation.Screen
import com.rms.discord.ui.theme.RMSDiscordTheme
import com.rms.discord.ui.theme.SurfaceDarker
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val authViewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Handle splash screen
        installSplashScreen().apply {
            setKeepOnScreenCondition {
                authViewModel.state.value.isLoading
            }
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle deep link from SSO callback
        handleIntent(intent)

        setContent {
            RMSDiscordTheme {
                val navController = rememberNavController()
                val authState by authViewModel.state.collectAsState()

                // Navigate when auth state changes
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            when (uri.scheme) {
                "rmsdiscord" -> {
                    when (uri.host) {
                        "callback" -> {
                            // SSO callback: rmsdiscord://callback?token=xxx
                            uri.getQueryParameter("token")?.let { token ->
                                authViewModel.handleSsoCallback(token)
                            }
                        }
                        "voice-invite" -> {
                            // Voice invite: rmsdiscord://voice-invite/{token}
                            // Handled by navigation deep link
                        }
                    }
                }
            }
        }
    }

    private fun launchSsoLogin() {
        // Build SSO login URL with redirect
        val ssoUrl = buildSsoUrl()

        // Launch Custom Tabs for SSO login
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()

        customTabsIntent.launchUrl(this, Uri.parse(ssoUrl))
    }

    private fun buildSsoUrl(): String {
        // SSO login URL with callback redirect
        // The SSO server should redirect to: rmsdiscord://callback?token=xxx
        val redirectUrl = "rmsdiscord://callback"
        val baseUrl = BuildConfig.API_BASE_URL
        return "$baseUrl/api/auth/login?redirect_url=$redirectUrl"
    }
}
