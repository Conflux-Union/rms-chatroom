package cn.net.rms.chatroom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import cn.net.rms.chatroom.R
import cn.net.rms.chatroom.data.local.SettingsPreferences
import cn.net.rms.chatroom.data.repository.AuthRepository
import cn.net.rms.chatroom.data.repository.ChatRepository
import cn.net.rms.chatroom.data.websocket.ConnectionState
import cn.net.rms.chatroom.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MessageConnectionService : Service() {
    companion object {
        private const val TAG = "MessageConnectionService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "message_connection_channel"
        private const val ACTION_STOP = "cn.net.rms.chatroom.ACTION_STOP_MESSAGE_CONNECTION"

        fun start(context: Context) {
            val intent = Intent(context, MessageConnectionService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start message connection service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MessageConnectionService::class.java))
        }
    }

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var chatRepository: ChatRepository

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        observeSettings()
        observeToken()
        observeConnection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                settingsPreferences.setBackgroundMessageServiceEnabled(false)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        startForegroundCompat()
        serviceScope.launch {
            ensureConnection()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
        if (!isAppInForeground()) {
            chatRepository.disconnectFromChannel()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun observeSettings() {
        serviceScope.launch {
            settingsPreferences.backgroundMessageServiceEnabled.collectLatest { enabled ->
                if (!enabled) {
                    stopSelf()
                    return@collectLatest
                }
                ensureConnection()
            }
        }
    }

    private fun observeToken() {
        serviceScope.launch {
            authRepository.tokenFlow.collectLatest { token ->
                if (token == null) {
                    stopSelf()
                    return@collectLatest
                }
                ensureConnection()
            }
        }
    }

    private fun observeConnection() {
        serviceScope.launch {
            chatRepository.connectionState.collectLatest { state ->
                if (state != ConnectionState.DISCONNECTED) return@collectLatest
                if (!settingsPreferences.backgroundMessageServiceEnabled.first()) return@collectLatest
                delay(1_500L)
                ensureConnection()
            }
        }
    }

    private suspend fun ensureConnection() {
        val enabled = settingsPreferences.backgroundMessageServiceEnabled.first()
        if (!enabled) {
            stopSelf()
            return
        }

        val token = authRepository.getToken()
        if (token == null) {
            stopSelf()
            return
        }

        chatRepository.isAppInForeground = isAppInForeground()
        chatRepository.loadCurrentUserFromToken()
        if (!chatRepository.isWebSocketConnected()) {
            chatRepository.connectToChannel(0)
        }
    }

    private fun startForegroundCompat() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台消息连接",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持消息连接以便后台接收提醒"
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MessageConnectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            "停止驻留",
            stopPendingIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RMS ChatRoom")
            .setContentText("正在保持后台消息连接")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(stopAction)
            .build()
    }

    private fun isAppInForeground(): Boolean {
        return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RMSChatRoom:MessageConnectionWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}
