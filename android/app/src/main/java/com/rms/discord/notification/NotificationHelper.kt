package com.rms.discord.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rms.discord.R
import com.rms.discord.data.model.Message
import com.rms.discord.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID_MESSAGES = "messages"
        const val CHANNEL_ID_VOICE = "voice_calls"

        private const val NOTIFICATION_GROUP_MESSAGES = "com.rms.discord.MESSAGES"
        private const val SUMMARY_NOTIFICATION_ID = 0
    }

    private val notificationManager = NotificationManagerCompat.from(context)
    private var notificationIdCounter = 1

    fun createNotificationChannels() {
        val messageChannel = NotificationChannel(
            CHANNEL_ID_MESSAGES,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_messages_desc)
            enableVibration(true)
            setShowBadge(true)
        }

        val voiceChannel = NotificationChannel(
            CHANNEL_ID_VOICE,
            context.getString(R.string.notification_channel_voice),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_voice_desc)
            setShowBadge(false)
        }

        notificationManager.createNotificationChannels(listOf(messageChannel, voiceChannel))
    }

    fun showMessageNotification(
        message: Message,
        channelName: String,
        serverName: String
    ) {
        if (!hasNotificationPermission()) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("channel_id", message.channelId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            message.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$channelName · $serverName")
            .setContentText("${message.username}: ${message.content}")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${message.username}: ${message.content}")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(NOTIFICATION_GROUP_MESSAGES)
            .build()

        val notificationId = notificationIdCounter++
        notificationManager.notify(notificationId, notification)

        // Show summary notification for grouping
        showSummaryNotification()
    }

    private fun showSummaryNotification() {
        if (!hasNotificationPermission()) return

        val summaryNotification = NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("新消息")
            .setGroup(NOTIFICATION_GROUP_MESSAGES)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(SUMMARY_NOTIFICATION_ID, summaryNotification)
    }

    fun cancelAllMessageNotifications() {
        notificationManager.cancelAll()
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
