package cn.net.rms.chatroom.data.local

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

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

    /**
     * Persist the choice. On API 33+ this also writes LocaleManager
     * .applicationLocales, which makes the framework apply (and recreate
     * activities) automatically. Pre-33 the caller must recreate the activity
     * for the change to take effect; the application-level Resources
     * configuration is updated here so @ApplicationContext consumers
     * (ViewModels, repositories, services, notifications) pick up the new
     * locale immediately instead of keeping the attach-time one.
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
        } else {
            val appRes = context.applicationContext.resources
            val config = Configuration(appRes.configuration)
            config.setLocales(
                when (language) {
                    AppLanguage.ZH -> LocaleList.forLanguageTags("zh")
                    AppLanguage.EN -> LocaleList.forLanguageTags("en")
                    AppLanguage.SYSTEM -> LocaleList.getDefault()
                }
            )
            appRes.updateConfiguration(config, appRes.displayMetrics)
        }
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
