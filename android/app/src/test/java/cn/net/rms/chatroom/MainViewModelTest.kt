package cn.net.rms.chatroom

import app.cash.turbine.test
import cn.net.rms.chatroom.data.model.*
import cn.net.rms.chatroom.data.repository.BugReportRepository
import cn.net.rms.chatroom.data.repository.ChatRepository
import cn.net.rms.chatroom.data.repository.UpdateRepository
import cn.net.rms.chatroom.data.websocket.ConnectionState
import cn.net.rms.chatroom.data.websocket.WebSocketEvent
import cn.net.rms.chatroom.ui.main.MainViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import androidx.lifecycle.ViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var bugReportRepository: BugReportRepository
    private lateinit var updateRepository: UpdateRepository
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
        bugReportRepository = mockk(relaxed = true)
        updateRepository = mockk(relaxed = true)

        coEvery { chatRepository.fetchServers() } returns Result.success(listOf(testServer))
        coEvery { chatRepository.fetchServer(any()) } returns Result.success(testServer)
        coEvery { chatRepository.fetchMessages(any()) } returns Result.success(emptyList())
        coEvery { chatRepository.fetchAllVoiceChannelUsers() } returns Unit
        coEvery { chatRepository.disconnectFromChannel() } returns Unit
        coEvery { chatRepository.connectToChannel(any()) } returns Unit
        every { chatRepository.messages } returns MutableStateFlow(emptyList())
        every { chatRepository.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { chatRepository.voiceChannelUsers } returns MutableStateFlow(emptyMap())
        every { chatRepository.webSocketEvents } returns MutableSharedFlow<WebSocketEvent>()
        coEvery { bugReportRepository.submitBugReport() } returns Result.success("test-report-id")
        coEvery { updateRepository.checkUpdate() } returns Result.success(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state should load servers`() = runTest {
        val viewModel = MainViewModel(chatRepository, bugReportRepository, updateRepository)
        runCurrent()

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertEquals(1, state.servers.size)
        assertEquals(testServer.name, state.servers.first().name)
        viewModel.cleanup()
    }

    @Test
    fun `selectServer should update current server`() = runTest {
        val viewModel = MainViewModel(chatRepository, bugReportRepository, updateRepository)
        runCurrent()

        viewModel.selectServer(testServer.id)
        runCurrent()

        val state = viewModel.state.value
        assertNotNull(state.currentServer)
        assertEquals(testServer.name, state.currentServer?.name)
        viewModel.cleanup()
    }

    @Test
    fun `selectChannel should update current channel`() = runTest {
        val viewModel = MainViewModel(chatRepository, bugReportRepository, updateRepository)
        runCurrent()

        viewModel.selectServer(testServer.id)
        runCurrent()

        viewModel.selectChannel(testTextChannel)
        runCurrent()

        val state = viewModel.state.value
        assertEquals(testTextChannel.name, state.currentChannel?.name)
        viewModel.cleanup()
    }

    @Test
    fun `error state should be set on server load failure`() = runTest {
        coEvery { chatRepository.fetchServers() } returns Result.failure(
            Exception("Network error")
        )

        val viewModel = MainViewModel(chatRepository, bugReportRepository, updateRepository)
        runCurrent()

        val state = viewModel.state.value
        assertNotNull(state.error)
        assertTrue(state.servers.isEmpty())
        viewModel.cleanup()
    }

    @Test
    fun `clearError should remove error from state`() = runTest {
        coEvery { chatRepository.fetchServers() } returns Result.failure(
            Exception("Network error")
        )

        val viewModel = MainViewModel(chatRepository, bugReportRepository, updateRepository)
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
