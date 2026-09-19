package cn.net.rms.chatroom.data.local

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * App-wide theme application, parallel to AppLocale: the DataStore choice is
 * mirrored into a plain SharedPreferences for synchronous reads (first-frame
 * composition, attachBaseContext), and the choice is pushed to the platform
 * where supported so every resource-qualified surface follows the in-app
 * setting, not the OS:
 *
 *  - API 31+: UiModeManager.setApplicationNightMode is a system-level per-app
 *    config override (like per-app locale). It is persisted by the system and
 *    reaches the SystemUI-drawn cold-start splash too.
 *  - API <31: wrapContext() forces uiMode on the activity context; the
 *    androidx compat splash and window background are resolved in-process, so
 *    they follow.
 *
 * SYSTEM (MODE_NIGHT_AUTO / no wrap) is the only mode that follows the OS.
 */
object AppThemeMode {
    private const val PREFS_NAME = "app_theme"
    private const val KEY_MODE = "mode"

    fun mirror(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    fun getStored(context: Context): ThemeMode =
        ThemeMode.fromStored(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MODE, null)
        )

    /** Persist the choice everywhere it needs to be known synchronously. */
    fun apply(context: Context, mode: ThemeMode) {
        mirror(context, mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                when (mode) {
                    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                }
            )
        }
    }

    /**
     * Wrap a base context with the forced night mode, or return it unchanged
     * when the choice is SYSTEM. The full base configuration is copied (not an
     * empty one) so only the night flag is overridden. On API 31+ the system
     * override normally already carries this; the wrap keeps the
     * in-process-resolved resources (window background) aligned even when the
     * two stores drift (e.g. prefs restored from a backup).
     */
    fun wrapContext(base: Context): Context {
        val mode = getStored(base)
        if (mode == ThemeMode.SYSTEM) return base
        val night = when (mode) {
            ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
            ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            ThemeMode.SYSTEM -> return base
        }
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return base.createConfigurationContext(config)
    }
}
