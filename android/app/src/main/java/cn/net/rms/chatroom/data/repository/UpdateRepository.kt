package cn.net.rms.chatroom.data.repository

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.data.api.ApiService
import cn.net.rms.chatroom.data.api.AppUpdateResponse
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
        private const val GITHUB_REPO_OWNER = "Trirrin"
        private const val GITHUB_REPO_NAME = "rms-discord"
        private const val GITHUB_RELEASE_API = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"
    }

    private var downloadId: Long = -1

    /**
     * Parse version code from tag name
     * Example: "v1.0.7-fix-2(33)" -> 33
     */
    private fun parseVersionCode(tagName: String): Int? {
        val regex = """v[^(]+\((\d+)\)""".toRegex()
        return regex.find(tagName)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Parse version name from tag name
     * Example: "v1.0.7-fix-2(33)" -> "1.0.7-fix-2"
     */
    private fun parseVersionName(tagName: String): String? {
        val regex = """v([^(]+)\(\d+\)""".toRegex()
        return regex.find(tagName)?.groupValues?.get(1)
    }

    suspend fun checkUpdate(): Result<AppUpdateResponse?> = withContext(Dispatchers.IO) {
        try {
            val release = api.checkGitHubRelease(GITHUB_RELEASE_API)
            val currentVersionCode = BuildConfig.VERSION_CODE

            // Parse version info from tag
            val versionCode = parseVersionCode(release.tagName)
            val versionName = parseVersionName(release.tagName)

            if (versionCode == null || versionName == null) {
                Log.e(TAG, "Failed to parse version from tag: ${release.tagName}")
                return@withContext Result.failure(Exception("Invalid tag format"))
            }

            // Find APK asset
            val apkAsset = release.assets.find { it.name.endsWith(".apk") }
            if (apkAsset == null) {
                Log.e(TAG, "No APK found in release assets")
                return@withContext Result.failure(Exception("No APK found"))
            }

            if (versionCode > currentVersionCode) {
                // Convert to AppUpdateResponse format
                val updateResponse = AppUpdateResponse(
                    versionCode = versionCode,
                    versionName = versionName,
                    changelog = release.body,
                    forceUpdate = false,  // GitHub releases don't have force update flag
                    downloadUrl = apkAsset.browserDownloadUrl
                )
                Result.success(updateResponse)
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

        // downloadUrl is already a full URL from GitHub
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
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
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
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
