package com.rms.discord.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.rms.discord.R
import com.rms.discord.data.livekit.LiveKitManager
import com.rms.discord.data.local.SettingsPreferences
import com.rms.discord.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service to keep voice call active when app is in background.
 * Manages WakeLock to prevent CPU sleep during voice calls.
 */
@AndroidEntryPoint
class VoiceCallService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_call_channel"

        const val ACTION_MUTE = "com.rms.discord.ACTION_MUTE"
        const val ACTION_UNMUTE = "com.rms.discord.ACTION_UNMUTE"
        const val ACTION_HANG_UP = "com.rms.discord.ACTION_HANG_UP"

        fun start(context: Context, channelName: String) {
            val intent = Intent(context, VoiceCallService::class.java).apply {
                putExtra("channel_name", channelName)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ may throw ForegroundServiceStartNotAllowedException
                // if app is in background. Service will be started when app returns to foreground.
                android.util.Log.w("VoiceCallService", "Cannot start foreground service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceCallService::class.java))
        }
    }

    @Inject
    lateinit var liveKitManager: LiveKitManager

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    private var wakeLock: PowerManager.WakeLock? = null
    private var channelName: String = "语音通话"
    private var isMuted: Boolean = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isAppInForeground = true

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            isAppInForeground = true
            FloatingWindowService.hide(this@VoiceCallService)
        }

        override fun onStop(owner: LifecycleOwner) {
            isAppInForeground = false
            showFloatingWindowIfEnabled()
        }
    }

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_MUTE -> {
                    liveKitManager.setMuted(true)
                }
                ACTION_UNMUTE -> {
                    liveKitManager.setMuted(false)
                }
                ACTION_HANG_UP -> {
                    liveKitManager.disconnect()
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()

        // Register broadcast receiver for notification actions
        val filter = IntentFilter().apply {
            addAction(ACTION_MUTE)
            addAction(ACTION_UNMUTE)
            addAction(ACTION_HANG_UP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }

        // Observe mute state changes to update notification
        serviceScope.launch {
            liveKitManager.isMuted.collectLatest { muted ->
                isMuted = muted
                updateNotification()
            }
        }

        // Register lifecycle observer for floating window
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channelName = intent?.getStringExtra("channel_name") ?: "语音通话"

        val notification = createNotification()
        // Use MICROPHONE type to maintain background microphone access
        // Service is started after connection success, so RECORD_AUDIO permission is already granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
        try {
            unregisterReceiver(actionReceiver)
        } catch (_: Exception) {}
        
        // Remove lifecycle observer and stop floating window
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        FloatingWindowService.stop(this)
        
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RMSChatRoom:VoiceCallWakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // 10 hours max
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音通话",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "语音通话进行中"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Mute/Unmute action
        val muteIntent = Intent(if (isMuted) ACTION_UNMUTE else ACTION_MUTE)
        val mutePendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            muteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val muteAction = NotificationCompat.Action.Builder(
            if (isMuted) R.drawable.ic_notification else R.drawable.ic_notification,
            if (isMuted) "取消静音" else "静音",
            mutePendingIntent
        ).build()

        // Hang up action
        val hangUpIntent = Intent(ACTION_HANG_UP)
        val hangUpPendingIntent = PendingIntent.getBroadcast(
            this,
            2,
            hangUpIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hangUpAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            "挂断",
            hangUpPendingIntent
        ).build()

        val statusText = if (isMuted) "正在 $channelName 中通话 (已静音)" else "正在 $channelName 中通话"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RMS ChatRoom")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(muteAction)
            .addAction(hangUpAction)
            .build()
    }

    private fun showFloatingWindowIfEnabled() {
        serviceScope.launch {
            val enabled = settingsPreferences.floatingWindowEnabled.first()
            if (enabled && FloatingWindowService.canDrawOverlays(this@VoiceCallService)) {
                FloatingWindowService.show(this@VoiceCallService)
            }
        }
    }
}
