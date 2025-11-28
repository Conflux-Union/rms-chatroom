package com.rms.discord

import app.cash.turbine.test
import com.rms.discord.data.model.*
import com.rms.discord.data.repository.ChatRepository
import com.rms.discord.data.websocket.ConnectionState
import com.rms.discord.ui.main.MainViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var chatRepository: ChatRepository
    private val testDispatcher = StandardTestDispatcher()

    private val testServer = Server(
        id = 1,
        name = "Test Server",
        icon = null,
        ownerId = 1,
        channels = listOf(
            Channel(id = 1, serverId = 1, name = "general", type = ChannelType.TEXT, position = 0),
            Channel(id = 2, serverId = 1, name = "voice", type = ChannelType.VOICE, position = 1)
        )
    )

    private val testTextChannel = Channel(
        id = 1,
        serverId = 1,
        name = "general",
        type = ChannelType.TEXT,
        position = 0
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        chatRepository = mockk(relaxed = true)

        coEvery { chatRepository.fetchServers() } returns Result.success(listOf(testServer))
        coEvery { chatRepository.fetchServer(any()) } returns Result.success(testServer)
        every { chatRepository.messages } returns MutableStateFlow(emptyList())
        every { chatRepository.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { chatRepository.voiceChannelUsers } returns MutableStateFlow(emptyMap())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state should load servers`() = runTest {
        val viewModel = MainViewModel(chatRepository)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals(1, state.servers.size)
            assertEquals(testServer.name, state.servers.first().name)
        }
    }

    @Test
    fun `selectServer should update current server`() = runTest {
        val viewModel = MainViewModel(chatRepository)
        advanceUntilIdle()

        viewModel.selectServer(testServer.id)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertNotNull(state.currentServer)
            assertEquals(testServer.name, state.currentServer?.name)
        }
    }

    @Test
    fun `selectChannel should update current channel`() = runTest {
        val viewModel = MainViewModel(chatRepository)
        advanceUntilIdle()

        viewModel.selectServer(testServer.id)
        advanceUntilIdle()

        viewModel.selectChannel(testTextChannel)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertEquals(testTextChannel.name, state.currentChannel?.name)
        }
    }

    @Test
    fun `error state should be set on server load failure`() = runTest {
        coEvery { chatRepository.fetchServers() } returns Result.failure(
            Exception("Network error")
        )

        val viewModel = MainViewModel(chatRepository)
        advanceUntilIdle()

        viewModel.state.test {
            val state = awaitItem()
            assertNotNull(state.error)
            assertTrue(state.servers.isEmpty())
        }
    }

    @Test
    fun `clearError should remove error from state`() = runTest {
        coEvery { chatRepository.fetchServers() } returns Result.failure(
            Exception("Network error")
        )

        val viewModel = MainViewModel(chatRepository)
        advanceUntilIdle()

        viewModel.clearError()

        viewModel.state.test {
            val state = awaitItem()
            assertNull(state.error)
        }
    }
}
