package com.rms.discord.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.rms.discord.BuildConfig
import com.rms.discord.data.api.ApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BugReportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService
) {
    companion object {
        private const val TAG = "BugReportRepository"
    }

    suspend fun submitBugReport(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val zipFile = createReportZip()
            val requestBody = zipFile.asRequestBody("application/zip".toMediaType())
            val part = MultipartBody.Part.createFormData("file", zipFile.name, requestBody)
            
            val response = api.submitBugReport(part)
            zipFile.delete()
            
            Result.success(response.reportId)
        } catch (e: Exception) {
            Log.e(TAG, "submitBugReport failed", e)
            Result.failure(e)
        }
    }

    private fun createReportZip(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(context.cacheDir, "bug_report_$timestamp.zip")
        
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // Add system info
            val systemInfo = collectSystemInfo()
            zos.putNextEntry(ZipEntry("system_info.txt"))
            zos.write(systemInfo.toByteArray())
            zos.closeEntry()
            
            // Add logcat logs
            val logs = collectLogs()
            zos.putNextEntry(ZipEntry("logcat.txt"))
            zos.write(logs.toByteArray())
            zos.closeEntry()
        }
        
        return zipFile
    }

    private fun collectSystemInfo(): String {
        return buildString {
            appendLine("=== Device Info ===")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Product: ${Build.PRODUCT}")
            appendLine()
            appendLine("=== Android Info ===")
            appendLine("SDK Version: ${Build.VERSION.SDK_INT}")
            appendLine("Android Version: ${Build.VERSION.RELEASE}")
            appendLine("Security Patch: ${Build.VERSION.SECURITY_PATCH}")
            appendLine()
            appendLine("=== App Info ===")
            appendLine("Package: ${BuildConfig.APPLICATION_ID}")
            appendLine("Version Name: ${BuildConfig.VERSION_NAME}")
            appendLine("Version Code: ${BuildConfig.VERSION_CODE}")
            appendLine("Build Type: ${BuildConfig.BUILD_TYPE}")
            appendLine()
            appendLine("=== Runtime Info ===")
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
            appendLine("Available Memory: ${Runtime.getRuntime().freeMemory() / 1024 / 1024} MB")
            appendLine("Max Memory: ${Runtime.getRuntime().maxMemory() / 1024 / 1024} MB")
            appendLine("Total Memory: ${Runtime.getRuntime().totalMemory() / 1024 / 1024} MB")
        }
    }

    private fun collectLogs(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "*:V"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val logs = reader.readText()
            reader.close()
            process.waitFor()
            logs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect logs", e)
            "Failed to collect logs: ${e.message}"
        }
    }
}
