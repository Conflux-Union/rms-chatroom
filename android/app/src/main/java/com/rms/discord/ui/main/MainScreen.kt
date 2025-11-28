package com.rms.discord.ui.main

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rms.discord.R
import com.rms.discord.data.model.ChannelType
import com.rms.discord.data.websocket.ConnectionState
import com.rms.discord.ui.auth.AuthViewModel
import com.rms.discord.ui.chat.ChatScreen
import com.rms.discord.ui.main.components.ChannelListColumn
import com.rms.discord.ui.main.components.ServerListColumn
import com.rms.discord.ui.theme.DiscordBlurple
import com.rms.discord.ui.theme.SurfaceDark
import com.rms.discord.ui.theme.SurfaceDarker
import com.rms.discord.ui.voice.VoiceScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
    onLogout: () -> Unit
) {
    val mainState by mainViewModel.state.collectAsState()
    val authState by authViewModel.state.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showMusicPanel by remember { mutableStateOf(false) }

    // Handle logout
    LaunchedEffect(authState.isAuthenticated) {
        if (!authState.isAuthenticated && !authState.isLoading) {
            onLogout()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(312.dp),
                drawerContainerColor = SurfaceDarker
            ) {
                Row(modifier = Modifier.fillMaxHeight()) {
                    // Server List
                    ServerListColumn(
                        servers = mainState.servers,
                        currentServerId = mainState.currentServer?.id,
                        onServerClick = { serverId ->
                            mainViewModel.selectServer(serverId)
                        }
                    )

                    // Channel List
                    ChannelListColumn(
                        server = mainState.currentServer,
                        currentChannelId = mainState.currentChannel?.id,
                        onChannelClick = { channel ->
                            mainViewModel.selectChannel(channel)
                            scope.launch { drawerState.close() }
                        },
                        username = authState.user?.nickname ?: authState.user?.username ?: "",
                        onLogout = { authViewModel.logout() }
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = mainState.currentChannel?.name
                                ?: stringResource(R.string.select_channel)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = SurfaceDark
                    )
                )
            },
            floatingActionButton = {
                // Music button - only show when connected to voice (Phase 3)
                // TODO: Check voice connection state
            },
            containerColor = SurfaceDark
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when {
                    mainState.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = DiscordBlurple
                        )
                    }

                    mainState.currentChannel == null -> {
                        Text(
                            text = stringResource(R.string.select_channel),
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    mainState.currentChannel?.type == ChannelType.TEXT -> {
                        val connectionState by mainViewModel.connectionState.collectAsState()
                        ChatScreen(
                            messages = mainViewModel.messages.collectAsState().value,
                            isLoading = mainState.isMessagesLoading,
                            connectionState = connectionState,
                            onSendMessage = { mainViewModel.sendMessage(it) },
                            onRefresh = { mainViewModel.refreshMessages() },
                            onReconnect = { mainViewModel.reconnectWebSocket() }
                        )
                    }

                    mainState.currentChannel?.type == ChannelType.VOICE -> {
                        VoiceScreen(channelId = mainState.currentChannel!!.id)
                    }
                }

                // Error Snackbar
                mainState.error?.let { error ->
                    Snackbar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                        action = {
                            TextButton(onClick = { mainViewModel.clearError() }) {
                                Text("关闭")
                            }
                        }
                    ) {
                        Text(error)
                    }
                }
            }
        }
    }
}
