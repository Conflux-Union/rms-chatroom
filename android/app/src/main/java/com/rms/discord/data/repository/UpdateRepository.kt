package com.rms.discord.data.repository

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.rms.discord.BuildConfig
import com.rms.discord.data.api.ApiService
import com.rms.discord.data.api.AppUpdateResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService
) {
    companion object {
        private const val TAG = "UpdateRepository"
        private const val APK_FILE_NAME = "rms-chatroom-update.apk"
    }

    private var downloadId: Long = -1

    suspend fun checkUpdate(): Result<AppUpdateResponse?> = withContext(Dispatchers.IO) {
        try {
            val response = api.checkUpdate()
            val currentVersionCode = BuildConfig.VERSION_CODE
            
            if (response.versionCode > currentVersionCode) {
                Result.success(response)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkUpdate failed", e)
            Result.failure(e)
        }
    }

    fun downloadUpdate(downloadUrl: String): Long {
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_FILE_NAME
        )
        if (apkFile.exists()) {
            apkFile.delete()
        }

        val fullUrl = if (downloadUrl.startsWith("http")) {
            downloadUrl
        } else {
            BuildConfig.API_BASE_URL.trimEnd('/') + downloadUrl
        }

        val request = DownloadManager.Request(Uri.parse(fullUrl))
            .setTitle("RMS Chatroom Update")
            .setDescription("Downloading update...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                APK_FILE_NAME
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = downloadManager.enqueue(request)
        return downloadId
    }

    fun installApk() {
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            APK_FILE_NAME
        )
        
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found")
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                apkFile
            )
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun registerDownloadReceiver(onComplete: (Boolean) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    
                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = cursor.getInt(statusIndex)
                        onComplete(status == DownloadManager.STATUS_SUCCESSFUL)
                    } else {
                        onComplete(false)
                    }
                    cursor.close()
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        return receiver
    }

    fun unregisterDownloadReceiver(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister receiver", e)
        }
    }
}
