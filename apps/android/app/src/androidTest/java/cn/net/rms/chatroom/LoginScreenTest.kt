package cn.net.rms.chatroom

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cn.net.rms.chatroom.ui.auth.LoginScreen
import cn.net.rms.chatroom.ui.theme.RMSDiscordTheme
import org.junit.Rule
import org.junit.Test

class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loginScreen_displaysTitle() {
        composeTestRule.setContent {
            RMSDiscordTheme {
                LoginScreen(onLoginClick = {}, onLoginSuccess = {})
            }
        }

        composeTestRule
            .onNodeWithText("RMS ChatRoom")
            .assertIsDisplayed()
    }

    @Test
    fun loginScreen_displaysLoginButton() {
        composeTestRule.setContent {
            RMSDiscordTheme {
                LoginScreen(onLoginClick = {}, onLoginSuccess = {})
            }
        }

        composeTestRule
            .onNodeWithText("使用 CXU 账号登录", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun loginScreen_loginButtonClickable() {
        var loginClicked = false

        composeTestRule.setContent {
            RMSDiscordTheme {
                LoginScreen(onLoginClick = { loginClicked = true }, onLoginSuccess = {})
            }
        }

        composeTestRule
            .onNodeWithText("使用 CXU 账号登录", substring = true)
            .performClick()

        assert(loginClicked)
    }
}
