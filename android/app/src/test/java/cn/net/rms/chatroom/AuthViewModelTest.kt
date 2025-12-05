package cn.net.rms.chatroom

import app.cash.turbine.test
import cn.net.rms.chatroom.data.model.User
import cn.net.rms.chatroom.data.repository.AuthException
import cn.net.rms.chatroom.data.repository.AuthRepository
import cn.net.rms.chatroom.ui.auth.AuthViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import androidx.lifecycle.ViewModel
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
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.isAuthenticated)
        assertEquals(testUser, state.user)
        assertFalse(state.isLoading)
        viewModel.cleanup()
    }

    @Test
    fun `initial state should be unauthenticated when no token exists`() = runTest {
        coEvery { authRepository.getToken() } returns null

        val viewModel = AuthViewModel(authRepository)
        runCurrent()

        val state = viewModel.state.value
        assertFalse(state.isAuthenticated)
        assertNull(state.user)
        assertFalse(state.isLoading)
        viewModel.cleanup()
    }

    @Test
    fun `handleSsoCallback should authenticate on valid token`() = runTest {
        coEvery { authRepository.getToken() } returns null
        coEvery { authRepository.verifyToken("valid_token") } returns Result.success(testUser)
        coEvery { authRepository.saveToken("valid_token") } just Runs

        val viewModel = AuthViewModel(authRepository)
        runCurrent()

        viewModel.handleSsoCallback("valid_token")
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.isAuthenticated)
        assertEquals(testUser, state.user)
        assertFalse(state.isLoading)
        viewModel.cleanup()

        coVerify { authRepository.saveToken("valid_token") }
    }

    @Test
    fun `handleSsoCallback should set error on invalid token`() = runTest {
        coEvery { authRepository.getToken() } returns null
        coEvery { authRepository.verifyToken("invalid_token") } returns Result.failure(
            AuthException("Invalid token")
        )

        val viewModel = AuthViewModel(authRepository)
        runCurrent()

        viewModel.handleSsoCallback("invalid_token")
        runCurrent()

        val state = viewModel.state.value
        assertFalse(state.isAuthenticated)
        assertNull(state.user)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("登录失败"))
        viewModel.cleanup()
    }

    @Test
    fun `logout should clear token and reset state`() = runTest {
        coEvery { authRepository.getToken() } returns "existing_token"
        coEvery { authRepository.verifyToken("existing_token") } returns Result.success(testUser)
        coEvery { authRepository.clearToken() } just Runs

        val viewModel = AuthViewModel(authRepository)
        runCurrent()

        viewModel.logout()
        runCurrent()

        val state = viewModel.state.value
        assertFalse(state.isAuthenticated)
        assertNull(state.user)
        assertFalse(state.isLoading)
        viewModel.cleanup()

        coVerify { authRepository.clearToken() }
    }

    @Test
    fun `clearError should remove error from state`() = runTest {
        coEvery { authRepository.getToken() } returns null
        coEvery { authRepository.verifyToken("invalid") } returns Result.failure(
            AuthException("Error")
        )

        val viewModel = AuthViewModel(authRepository)
        runCurrent()

        viewModel.handleSsoCallback("invalid")
        runCurrent()

        viewModel.clearError()

        val state = viewModel.state.value
        assertNull(state.error)
        viewModel.cleanup()
    }

    private fun ViewModel.cleanup() {
        val method = ViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(this)
    }
}
