package cn.net.rms.chatroom.data.local

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.mutableLongStateOf

/** Languages the in-app setting can select. */
enum class AppLanguage { SYSTEM, ZH, EN }

/**
 * Per-app language persistence and application.
 *
 * Uses plain SharedPreferences instead of DataStore because
 * MainActivity.attachBaseContext needs a synchronous read before any
 * coroutine can run.
 */
object AppLocale {
    private const val PREFS_NAME = "app_locale"
    private const val KEY_LANGUAGE = "language"

    /**
     * Bumped whenever the effective locale changes in place (apply() below,
     * or an onConfigurationChanged delivery). Composition reads it to derive a
     * fresh locale context so stringResource call sites re-resolve without an
     * activity relaunch — the relaunch gap is what used to flash pure black
     * on language switch (old window destroyed, new window not yet drawn).
     */
    val tick = mutableLongStateOf(0L)

    private fun languageTag(language: AppLanguage): String = when (language) {
        AppLanguage.ZH -> "zh"
        AppLanguage.EN -> "en"
        AppLanguage.SYSTEM -> "system"
    }

    fun getStored(context: Context): AppLanguage {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "system")
        return when (stored) {
            "zh" -> AppLanguage.ZH
            "en" -> AppLanguage.EN
            else -> AppLanguage.SYSTEM
        }
    }

    /**
     * The effective language. On API 33+ the framework owns the locale list,
     * so read it first (empty list means "follow system"); older APIs only
     * have the stored preference.
     */
    fun current(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val locales = manager?.applicationLocales
            if (locales != null && !locales.isEmpty) {
                val language = locales.get(0)?.language
                if (language != null) {
                    return if (language.startsWith("zh")) AppLanguage.ZH else AppLanguage.EN
                }
            }
        }
        return getStored(context)
    }

    private fun localesFor(language: AppLanguage): LocaleList = when (language) {
        AppLanguage.ZH -> LocaleList.forLanguageTags("zh")
        AppLanguage.EN -> LocaleList.forLanguageTags("en")
        AppLanguage.SYSTEM -> LocaleList.getDefault()
    }

    /**
     * Persist the choice. On API 33+ this also writes LocaleManager
     * .applicationLocales; with configChanges=locale declared the change
     * arrives as onConfigurationChanged instead of an activity relaunch.
     * Pre-33 the application-level Resources configuration is updated here so
     * @ApplicationContext consumers (ViewModels, repositories, services,
     * notifications) pick up the new locale immediately, and the caller's own
     * Resources (the activity's wrapped instance — a different object from
     * the application's) is reconfigured in place so composition re-resolves
     * strings from it. [tick] is bumped either way so the shadowed
     * LocalConfiguration swaps to a fresh instance.
     */
    fun apply(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, languageTag(language))
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                when (language) {
                    AppLanguage.ZH -> LocaleList.forLanguageTags("zh")
                    AppLanguage.EN -> LocaleList.forLanguageTags("en")
                    AppLanguage.SYSTEM -> LocaleList.getEmptyLocaleList()
                }
        }
        // Reconfigure resources in place on every API level. Pre-33 this IS the
        // application mechanism. On 33+ LocaleManager applies asynchronously and
        // only refreshes the activity's base resources — but the composition
        // reads the wrapped context from attachBaseContext (AppThemeMode wrap),
        // which owns a separate Resources instance the framework never touches.
        // Without this in-place reconfigure, the tick-driven recomposition
        // resolves strings from the stale locale and the switch only lands
        // after a relaunch.
        val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (language) {
                AppLanguage.ZH -> LocaleList.forLanguageTags("zh")
                AppLanguage.EN -> LocaleList.forLanguageTags("en")
                AppLanguage.SYSTEM -> LocaleList.getDefault()
            }
        } else {
            localesFor(language)
        }
        val appRes = context.applicationContext.resources
        val appConfig = Configuration(appRes.configuration)
        appConfig.setLocales(locales)
        appRes.updateConfiguration(appConfig, appRes.displayMetrics)
        if (context.resources !== appRes) {
            val config = Configuration(context.resources.configuration)
            config.setLocales(locales)
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        }
        tick.longValue++
    }

    /**
     * Force this context's (wrapped) Resources onto the effective locale.
     *
     * The composition reads the wrapped context from attachBaseContext
     * (AppThemeMode wrap), which owns a separate Resources instance the
     * framework's configuration propagation never refreshes — worse, the
     * propagation dispatched by a per-app locale write can arrive carrying the
     * STALE locale while the write commits, clobbering the in-place
     * reconfiguration done in [apply]. Called from onConfigurationChanged so
     * the wrapper is realigned before the tick-driven recomposition reads it.
     */
    fun realign(context: Context) {
        val locales = when (current(context)) {
            AppLanguage.ZH -> LocaleList.forLanguageTags("zh")
            AppLanguage.EN -> LocaleList.forLanguageTags("en")
            AppLanguage.SYSTEM -> LocaleList.getDefault()
        }
        val res = context.resources
        if (res.configuration.locales == locales) return
        val config = Configuration(res.configuration)
        config.setLocales(locales)
        res.updateConfiguration(config, res.displayMetrics)
    }

    /**
     * Pre-33 only: wrap a base context with the stored locale. Applied to both
     * the Application (so @ApplicationContext consumers follow the in-app
     * setting) and MainActivity. API 33+ returns the base unchanged — the
     * framework applies applicationLocales on its own.
     */
    fun wrapContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val language = getStored(base)
        if (language == AppLanguage.SYSTEM) return base
        val config = Configuration()
        config.setLocales(LocaleList.forLanguageTags(languageTag(language)))
        return base.createConfigurationContext(config)
    }
}

