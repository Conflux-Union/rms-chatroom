package com.rms.discord.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper class for managing battery optimization settings.
 * Provides detection and navigation to battery optimization settings,
 * with special handling for Xiaomi/HyperOS devices.
 */
object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptHelper"

    /**
     * Check if the app is ignoring battery optimizations
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Check if the device is a Xiaomi/MIUI/HyperOS device
     */
    fun isXiaomiDevice(): Boolean {
        return Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
                Build.BRAND.equals("Redmi", ignoreCase = true) ||
                Build.BRAND.equals("POCO", ignoreCase = true)
    }

    /**
     * Get the system property value (for detecting MIUI/HyperOS)
     */
    @SuppressLint("PrivateApi")
    private fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, key) as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if running on MIUI or HyperOS
     */
    fun isMiuiOrHyperOS(): Boolean {
        val miuiVersion = getSystemProperty("ro.miui.ui.version.name")
        val hyperOSVersion = getSystemProperty("ro.mi.os.version.name")
        return !miuiVersion.isNullOrEmpty() || !hyperOSVersion.isNullOrEmpty()
    }

    /**
     * Request to ignore battery optimizations using system dialog
     * Returns true if intent was launched successfully
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimization(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }

        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request ignore battery optimization", e)
            false
        }
    }

    /**
     * Open the battery optimization settings list
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization settings", e)
            false
        }
    }

    /**
     * Open Xiaomi/HyperOS specific battery settings for the app
     */
    fun openXiaomiBatterySettings(context: Context): Boolean {
        val intents = listOf(
            // MIUI/HyperOS app-specific battery saver settings (most direct)
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity"
                )
                putExtra("package_name", context.packageName)
                putExtra("package_label", getAppName(context))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // Alternative: App power saver activity
            Intent().apply {
                component = ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
                putExtra("package_name", context.packageName)
                putExtra("package_label", getAppName(context))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // Security center - autostart management
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // App details page in settings (most reliable fallback)
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in intents) {
            try {
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.d(TAG, "Intent failed: ${intent.component}", e)
                // Continue to next intent
            }
        }

        // Final fallback: open app info page (should always work)
        return try {
            val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "All intents failed, including fallback", e)
            false
        }
    }

    /**
     * Open the appropriate battery settings based on device manufacturer
     */
    fun openBatterySettings(context: Context): Boolean {
        return if (isXiaomiDevice() || isMiuiOrHyperOS()) {
            openXiaomiBatterySettings(context)
        } else {
            requestIgnoreBatteryOptimization(context)
        }
    }

    /**
     * Open Xiaomi autostart settings
     */
    fun openXiaomiAutostartSettings(context: Context): Boolean {
        val intents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in intents) {
            try {
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.d(TAG, "Autostart intent failed", e)
            }
        }
        return false
    }

    private fun getAppName(context: Context): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            "RMS ChatRoom"
        }
    }

    /**
     * Get user-friendly description of required settings for the current device
     */
    fun getSettingsDescription(): String {
        return if (isXiaomiDevice() || isMiuiOrHyperOS()) {
            "请在弹出的设置页面中：\n" +
                    "1. 将省电策略设为「无限制」\n" +
                    "2. 允许自启动\n" +
                    "3. 允许后台运行"
        } else {
            "请在弹出的设置页面中选择「不优化」或「无限制」"
        }
    }
}
