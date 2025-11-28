package com.rms.discord

import app.cash.turbine.test
import com.rms.discord.data.model.User
import com.rms.discord.data.repository.AuthException
import com.rms.discord.data.repository.AuthRepository
import com.rms.discord.ui.auth.AuthViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private lateinit var authRepository: AuthRepository
    private val testDispatcher = StandardTestDispatcher()

    private val testUser = User(
        id = 1,
        username = "testuser",
        nickname = "Test User",
        email = "test@example.com",
        permissionLevel = 1,
        isActive = true
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        authRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state should check existing token`() = runTest {
        coEvery { authRepository.getToken() } returns "existing_token"
        coEvery { authRepository.verifyToken("existing_token") } returns Result.success(testUser)

        val viewModel = AuthViewModel(authRepository)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.isAuthenticated)
            assertEquals(testUser, state.user)
            assertFalse(state.isLoading)
        }
    }

    @Test
    fun `initial state should be unauthenticated when no token exists`() = runTest {
        coEvery { authRepository.getToken() } returns null

        val viewModel = AuthViewModel(authRepository)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isAuthenticated)
            assertNull(state.user)
            assertFalse(state.isLoading)
        }
    }

    @Test
    fun `handleSsoCallback should authenticate on valid token`() = runTest {
        coEvery { authRepository.getToken() } returns null
        coEvery { authRepository.verifyToken("valid_token") } returns Result.success(testUser)
        coEvery { authRepository.saveToken("valid_token") } just Runs

        val viewModel = AuthViewModel(authRepository)
        advanceUntilIdle()

        viewModel.handleSsoCallback("valid_token")
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertTrue(state.isAuthenticated)
            assertEquals(testUser, state.user)
            assertFalse(state.isLoading)
        }

        coVerify { authRepository.saveToken("valid_token") }
    }

    @Test
    fun `handleSsoCallback should set error on invalid token`() = runTest {
        coEvery { authRepository.getToken() } returns null
        coEvery { authRepository.verifyToken("invalid_token") } returns Result.failure(
            AuthException("Invalid token")
        )

        val viewModel = AuthViewModel(authRepository)
        advanceUntilIdle()

        viewModel.handleSsoCallback("invalid_token")
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isAuthenticated)
            assertNull(state.user)
            assertNotNull(state.error)
            assertTrue(state.error!!.contains("登录失败"))
        }
    }

    @Test
    fun `logout should clear token and reset state`() = runTest {
        coEvery { authRepository.getToken() } returns "existing_token"
        coEvery { authRepository.verifyToken("existing_token") } returns Result.success(testUser)
        coEvery { authRepository.clearToken() } just Runs

        val viewModel = AuthViewModel(authRepository)
        advanceUntilIdle()

        viewModel.logout()
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isAuthenticated)
            assertNull(state.user)
        }

        coVerify { authRepository.clearToken() }
    }

    @Test
    fun `clearError should remove error from state`() = runTest {
        coEvery { authRepository.getToken() } returns null
        coEvery { authRepository.verifyToken("invalid") } returns Result.failure(
            AuthException("Error")
        )

        val viewModel = AuthViewModel(authRepository)
        advanceUntilIdle()

        viewModel.handleSsoCallback("invalid")
        advanceUntilIdle()

        viewModel.clearError()

        viewModel.state.test {
            val state = awaitItem()
            assertNull(state.error)
        }
    }
}
