package com.rms.discord

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.rms.discord.ui.auth.LoginScreen
import com.rms.discord.ui.theme.RMSDiscordTheme
import org.junit.Rule
import org.junit.Test

class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loginScreen_displaysTitle() {
        composeTestRule.setContent {
            RMSDiscordTheme {
                LoginScreen(onLogin = {})
            }
        }

        composeTestRule
            .onNodeWithText("RMS Discord")
            .assertIsDisplayed()
    }

    @Test
    fun loginScreen_displaysLoginButton() {
        composeTestRule.setContent {
            RMSDiscordTheme {
                LoginScreen(onLogin = {})
            }
        }

        composeTestRule
            .onNodeWithText("使用 RMSSSO 登录", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun loginScreen_loginButtonClickable() {
        var loginClicked = false

        composeTestRule.setContent {
            RMSDiscordTheme {
                LoginScreen(onLogin = { loginClicked = true })
            }
        }

        composeTestRule
            .onNodeWithText("使用 RMSSSO 登录", substring = true)
            .performClick()

        assert(loginClicked)
    }
}
