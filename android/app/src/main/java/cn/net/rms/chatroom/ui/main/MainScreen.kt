package cn.net.rms.chatroom.ui.main

import androidx.compose.animation.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.data.model.ChannelType
import cn.net.rms.chatroom.data.websocket.ConnectionState
import cn.net.rms.chatroom.ui.auth.AuthViewModel
import cn.net.rms.chatroom.ui.chat.ChatScreen
import cn.net.rms.chatroom.ui.main.components.ChannelListColumn
import cn.net.rms.chatroom.ui.main.components.ServerListColumn
import cn.net.rms.chatroom.ui.common.BatteryOptimizationDialog
import cn.net.rms.chatroom.ui.theme.TiColor
import cn.net.rms.chatroom.ui.theme.SurfaceDark
import cn.net.rms.chatroom.ui.theme.SurfaceDarker
import cn.net.rms.chatroom.ui.voice.VoiceScreen
import cn.net.rms.chatroom.util.BatteryOptimizationHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel = hiltViewModel(),
    authViewModel: AuthViewModel = hiltViewModel(),
    onLogout: () -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    val mainState by mainViewModel.state.collectAsState()
    val authState by authViewModel.state.collectAsState()
    val voiceChannelUsers by mainViewModel.voiceChannelUsers.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showMusicPanel by remember { mutableStateOf(false) }
    var showBugReportDialog by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Battery optimization preferences
    val prefs = remember { context.getSharedPreferences("battery_optimization", 0) }
    val neverShowBatteryDialog = remember { mutableStateOf(prefs.getBoolean("never_show", false)) }
    val isIgnoringBatteryOptimization = remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) }
    var showStartupBatteryDialog by remember { mutableStateOf(false) }

    // Check battery optimization on startup
    LaunchedEffect(Unit) {
        if (!neverShowBatteryDialog.value && !isIgnoringBatteryOptimization.value) {
            showStartupBatteryDialog = true
        }
    }

    // Register download receiver
    DisposableEffect(Unit) {
        val receiver = mainViewModel.getUpdateRepository().registerDownloadReceiver { success ->
            mainViewModel.onDownloadComplete(success)
        }
        onDispose {
            mainViewModel.getUpdateRepository().unregisterDownloadReceiver(receiver)
        }
    }

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
                        },
                        isAdmin = (authState.user?.permissionLevel ?: 0) >= 3,
                        onCreateServer = { name ->
                            mainViewModel.createServer(name)
                        },
                        onDeleteServer = { serverId ->
                            mainViewModel.deleteServer(serverId)
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
                        onLogout = { authViewModel.logout() },
                        onSettings = {
                            scope.launch { drawerState.close() }
                            onNavigateToSettings()
                        },
                        voiceChannelUsers = voiceChannelUsers,
                        isAdmin = (authState.user?.permissionLevel ?: 0) >= 3,
                        onCreateChannel = { name, type ->
                            mainViewModel.createChannel(name, type)
                        },
                        onDeleteChannel = { channelId ->
                            mainViewModel.deleteChannel(channelId)
                        }
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
                    actions = {
                        IconButton(
                            onClick = { showBugReportDialog = true },
                            enabled = !mainState.bugReportSubmitting
                        ) {
                            if (mainState.bugReportSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = TiColor
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.BugReport,
                                    contentDescription = "Bug Report"
                                )
                            }
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
                            color = TiColor
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
                            authToken = authState.token,
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

    // Bug Report Confirmation Dialog
    if (showBugReportDialog) {
        AlertDialog(
            onDismissRequest = { showBugReportDialog = false },
            title = { Text("上报Bug") },
            text = { Text("将收集设备信息和应用日志并上传，是否继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBugReportDialog = false
                        mainViewModel.submitBugReport()
                    }
                ) {
                    Text("确认上报")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBugReportDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Bug Report Success Dialog
    mainState.bugReportId?.let { reportId ->
        AlertDialog(
            onDismissRequest = { mainViewModel.clearBugReportId() },
            title = { Text("上报成功") },
            text = { Text("Report ID:\n$reportId") },
            confirmButton = {
                TextButton(onClick = { mainViewModel.clearBugReportId() }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(reportId))
                    }
                ) {
                    Text("复制ID")
                }
            }
        )
    }

    // Update Available Dialog
    mainState.updateInfo?.let { updateInfo ->
        AlertDialog(
            onDismissRequest = { if (!updateInfo.forceUpdate) mainViewModel.dismissUpdate() },
            title = { Text("发现新版本") },
            text = {
                Column {
                    Text("版本: ${updateInfo.versionName}")
                    if (updateInfo.changelog.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("更新内容:\n${updateInfo.changelog}")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { mainViewModel.downloadUpdate() },
                    enabled = !mainState.isDownloading
                ) {
                    if (mainState.isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("下载中...")
                    } else {
                        Text("下载更新")
                    }
                }
            },
            dismissButton = {
                if (!updateInfo.forceUpdate) {
                    TextButton(onClick = { mainViewModel.dismissUpdate() }) {
                        Text("稍后再说")
                    }
                }
            }
        )
    }

    // Battery Optimization Dialog - triggered on startup
    if (showStartupBatteryDialog) {
        BatteryOptimizationDialog(
            onDismiss = { showStartupBatteryDialog = false },
            onOpenSettings = {
                showStartupBatteryDialog = false
                BatteryOptimizationHelper.openBatterySettings(context)
            },
            onNeverShowAgain = {
                showStartupBatteryDialog = false
                neverShowBatteryDialog.value = true
                prefs.edit().putBoolean("never_show", true).apply()
            }
        )
    }
}
