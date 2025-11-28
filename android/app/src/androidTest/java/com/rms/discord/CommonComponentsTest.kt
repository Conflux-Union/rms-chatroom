package com.rms.discord

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.rms.discord.ui.common.*
import com.rms.discord.ui.theme.RMSDiscordTheme
import org.junit.Rule
import org.junit.Test

class CommonComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loadingContent_displaysLoadingIndicator() {
        composeTestRule.setContent {
            RMSDiscordTheme {
                LoadingContent(message = "测试加载中...")
            }
        }

        composeTestRule
            .onNodeWithText("测试加载中...")
            .assertIsDisplayed()
    }

    @Test
    fun emptyContent_displaysEmptyState() {
        composeTestRule.setContent {
            RMSDiscordTheme {
                EmptyContent(
                    title = "暂无数据",
                    description = "这里什么都没有"
                )
            }
        }

        composeTestRule.onNodeWithText("暂无数据").assertIsDisplayed()
        composeTestRule.onNodeWithText("这里什么都没有").assertIsDisplayed()
    }

    @Test
    fun emptyContent_actionButtonClickable() {
        var actionClicked = false

        composeTestRule.setContent {
            RMSDiscordTheme {
                EmptyContent(
                    title = "暂无数据",
                    actionText = "刷新",
                    onAction = { actionClicked = true }
                )
            }
        }

        composeTestRule
            .onNodeWithText("刷新")
            .performClick()

        assert(actionClicked)
    }

    @Test
    fun errorContent_displaysError() {
        composeTestRule.setContent {
            RMSDiscordTheme {
                ErrorContent(message = "发生了一个错误")
            }
        }

        composeTestRule.onNodeWithText("出错了").assertIsDisplayed()
        composeTestRule.onNodeWithText("发生了一个错误").assertIsDisplayed()
    }

    @Test
    fun errorContent_retryButtonClickable() {
        var retryClicked = false

        composeTestRule.setContent {
            RMSDiscordTheme {
                ErrorContent(
                    message = "网络错误",
                    onRetry = { retryClicked = true }
                )
            }
        }

        composeTestRule
            .onNodeWithText("重试")
            .performClick()

        assert(retryClicked)
    }

    @Test
    fun networkErrorContent_displaysNetworkError() {
        composeTestRule.setContent {
            RMSDiscordTheme {
                NetworkErrorContent()
            }
        }

        composeTestRule.onNodeWithText("网络连接失败").assertIsDisplayed()
        composeTestRule.onNodeWithText("请检查网络连接后重试").assertIsDisplayed()
    }
}
