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
import cn.net.rms.chatroom.data.api.GitHubReleaseResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long
)

@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService
) {
    companion object {
        private const val TAG = "UpdateRepository"
        private const val APK_FILE_NAME = "rms-chatroom-update.apk"
        private const val GITHUB_REPO_OWNER = "RMS-Server"
        private const val GITHUB_REPO_NAME = "rms-chatroom"
        private const val GITHUB_RELEASE_API = "https://api.github.com/repos/$GITHUB_REPO_OWNER/$GITHUB_REPO_NAME/releases/latest"

        // ghproxy mirrors for mainland China acceleration (verified 2026-07).
        // ghp.ci is dead (connection refused). ghproxy.net returns 403 on the
        // api.github.com proxy but still serves release assets, so it stays
        // useful for downloads; checkUpdate just falls through to the next.
        private val GHPROXY_MIRRORS = listOf(
            "https://gh-proxy.com",
            "https://moeyy.cn/gh-proxy",
            "https://ghproxy.net"
        )
    }

    private var downloadId: Long = -1
    private var originalDownloadUrl: String? = null
    private var mirrorIndex = 0
    private var downloadCompleteCallback: ((Boolean) -> Unit)? = null

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

    /**
     * Smart update check: try ghproxy mirrors first, fallback to GitHub
     */
    suspend fun checkUpdate(): Result<AppUpdateResponse?> = withContext(Dispatchers.IO) {
        val currentVersionCode = BuildConfig.VERSION_CODE

        // Try ghproxy mirrors first
        for (mirror in GHPROXY_MIRRORS) {
            try {
                val mirrorApi = "$mirror/$GITHUB_RELEASE_API"
                Log.d(TAG, "Trying mirror: $mirror")

                val release = api.checkGitHubRelease(mirrorApi)
                val result = parseReleaseResponse(release, currentVersionCode, mirror)
                if (result != null) {
                    Log.i(TAG, "Update check succeeded via mirror: $mirror")
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mirror $mirror failed: ${e.message}")
                // Continue to next mirror
            }
        }

        // Fallback to official GitHub API
        try {
            Log.d(TAG, "Trying official GitHub API")
            val release = api.checkGitHubRelease(GITHUB_RELEASE_API)
            val result = parseReleaseResponse(release, currentVersionCode, "GitHub")
            if (result != null) {
                Log.i(TAG, "Update check succeeded via GitHub")
                return@withContext result
            }
        } catch (e: Exception) {
            Log.e(TAG, "GitHub API failed: ${e.message}")
            return@withContext Result.failure(e)
        }

        Result.success(null)
    }

    /**
     * Parse GitHub release response and return update info if available
     */
    private fun parseReleaseResponse(
        release: GitHubReleaseResponse,
        currentVersionCode: Int,
        source: String
    ): Result<AppUpdateResponse?>? {
        // Parse version info from tag
        val versionCode = parseVersionCode(release.tagName)
        val versionName = parseVersionName(release.tagName)

        if (versionCode == null || versionName == null) {
            Log.e(TAG, "Failed to parse version from tag: ${release.tagName}")
            return null
        }

        // Find APK asset
        val apkAsset = release.assets.find { it.name.endsWith(".apk") }
        if (apkAsset == null) {
            Log.e(TAG, "No APK found in release assets")
            return null
        }

        return if (versionCode > currentVersionCode) {
            Log.i(TAG, "Update available: $versionName (code: $versionCode) from $source")
            Result.success(
                AppUpdateResponse(
                    versionCode = versionCode,
                    versionName = versionName,
                    changelog = "",  // No changelog display needed
                    forceUpdate = false,
                    downloadUrl = apkAsset.browserDownloadUrl
                )
            )
        } else {
            Result.success(null)
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

        // downloadUrl is the raw GitHub browser_download_url; route it through
        // ghproxy mirrors one by one, falling back on download failure.
        originalDownloadUrl = downloadUrl
        mirrorIndex = 0
        return enqueueMirrorDownload(0)
    }

    private fun enqueueMirrorDownload(index: Int): Long {
        val original = originalDownloadUrl ?: return -1
        val mirror = GHPROXY_MIRRORS.getOrNull(index)
        val finalUrl = if (mirror != null) "$mirror/$original" else original
        Log.d(TAG, "Downloading update via mirror[$index]: ${mirror ?: "direct"}")

        val request = DownloadManager.Request(Uri.parse(finalUrl))
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

    /**
     * Query the current download's progress from DownloadManager.
     * Reads the live downloadId, so it keeps tracking after a mirror
     * fallback re-enqueues the download (byte counts restart from 0).
     */
    suspend fun queryDownloadProgress(): DownloadProgress? = withContext(Dispatchers.IO) {
        if (downloadId == -1L) return@withContext null
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            DownloadProgress(
                bytesDownloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                totalBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            )
        }
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
        downloadCompleteCallback = onComplete
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return

                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
                if (!cursor.moveToFirst()) {
                    cursor.close()
                    downloadCompleteCallback?.invoke(false)
                    return
                }

                val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                cursor.close()

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        downloadCompleteCallback?.invoke(true)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        mirrorIndex++
                        if (mirrorIndex < GHPROXY_MIRRORS.size) {
                            Log.w(TAG, "Mirror failed, retrying with ${GHPROXY_MIRRORS[mirrorIndex]}")
                            enqueueMirrorDownload(mirrorIndex)
                        } else {
                            Log.e(TAG, "All download mirrors failed")
                            downloadCompleteCallback?.invoke(false)
                        }
                    }
                    // PENDING / RUNNING: wait for the completion broadcast
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
