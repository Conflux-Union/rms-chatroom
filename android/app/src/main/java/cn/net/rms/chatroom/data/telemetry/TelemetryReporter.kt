package cn.net.rms.chatroom.data.telemetry

import android.content.Context
import android.util.Log
import cn.net.rms.chatroom.BuildConfig
import cn.net.rms.chatroom.data.local.SettingsPreferences
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fire-and-forget client telemetry: crash reports, connection quality and
 * auth failures are POSTed to /api/telemetry/client. Must never throw into
 * app code and never send anything when the user has disabled telemetry.
 *
 * Uses its own bare OkHttpClient (not the DI one): the shared client depends
 * on TokenAuthenticator, which itself reports through this class — reusing it
 * would create a dependency cycle. The endpoint accepts anonymous requests,
 * so no Authorization header is needed.
 */
@Singleton
class TelemetryReporter @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsPreferences: SettingsPreferences,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "TelemetryReporter"
        private const val PER_KEY_MIN_INTERVAL_MS = 60_000L
        private const val MAX_STORED_CRASHES = 5
        private const val MAX_STACK_LEN = 16_384
        private const val MAX_MESSAGE_LEN = 2_048

        private val TOKEN_PARAM_RE = Regex("(?i)\\b(token|access_token|refresh_token)=[^&\\s'\"]+")
        private val JWT_RE = Regex("\\beyJ[\\w-]{10,}\\.[\\w-]{10,}\\.[\\w-]{10,}\\b")
    }

    private data class TelemetryEvent(
        val type: String,
        val message: String?,
        val stack: String?,
        val meta: Map<String, Any?>?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val lastSentByKey = ConcurrentHashMap<String, Long>()
    private val crashDir = File(context.filesDir, "telemetry")

    // Cached flag for the crash handler, which cannot suspend while the
    // process is dying. Suspend paths read the DataStore flow directly.
    @Volatile
    private var enabledCache = true

    init {
        scope.launch {
            settingsPreferences.telemetryEnabled.collect { enabledCache = it }
        }
    }

    /**
     * Queue a telemetry event. Safe to call from any thread; drops the event
     * silently when telemetry is disabled or the same event fired recently.
     */
    fun report(
        type: String,
        message: String? = null,
        stack: String? = null,
        meta: Map<String, Any?>? = null
    ) {
        scope.launch {
            try {
                if (!enabledCache) return@launch
                val key = "$type:${message.orEmpty()}"
                val now = System.currentTimeMillis()
                val last = lastSentByKey[key]
                if (last != null && now - last < PER_KEY_MIN_INTERVAL_MS) return@launch
                lastSentByKey[key] = now
                post(listOf(buildEvent(type, message, stack, meta)))
            } catch (e: Exception) {
                Log.w(TAG, "report failed: ${e.message}")
            }
        }
    }

    /**
     * Install the uncaught-exception handler. The crash is persisted to disk
     * synchronously (the process is about to die) and uploaded on next launch
     * by [uploadPendingCrashes]; the previous handler still runs so the
     * system crash dialog and process teardown are unaffected.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (enabledCache) persistCrash(thread, throwable)
            } catch (_: Exception) {
                // Never interfere with crash teardown.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Upload crashes persisted by a previous run, deleting them on success. */
    fun uploadPendingCrashes() {
        scope.launch {
            try {
                if (!settingsPreferences.telemetryEnabled.first()) return@launch
                val files = crashDir.listFiles()?.sortedBy { it.name } ?: return@launch
                if (files.isEmpty()) return@launch
                val events = files.mapNotNull { file ->
                    runCatching { gson.fromJson(file.readText(), TelemetryEvent::class.java) }.getOrNull()
                }
                if (events.isEmpty() || post(events)) {
                    files.forEach { it.delete() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "pending crash upload failed: ${e.message}")
            }
        }
    }

    private fun persistCrash(thread: Thread, throwable: Throwable) {
        crashDir.mkdirs()
        // Cap stored crashes so a crash loop cannot fill the disk.
        crashDir.listFiles()
            ?.sortedBy { it.name }
            ?.dropLast(MAX_STORED_CRASHES - 1)
            ?.forEach { it.delete() }

        val event = buildEvent(
            type = "crash",
            message = throwable.toString(),
            stack = Log.getStackTraceString(throwable),
            meta = mapOf("thread" to thread.name)
        )
        File(crashDir, "crash-${System.currentTimeMillis()}.json").writeText(gson.toJson(event))
    }

    private fun buildEvent(
        type: String,
        message: String?,
        stack: String?,
        meta: Map<String, Any?>?
    ) = TelemetryEvent(
        type = type,
        message = message?.let { sanitize(it.take(MAX_MESSAGE_LEN)) },
        stack = stack?.let { sanitize(it.take(MAX_STACK_LEN)) },
        meta = meta
    )

    private fun sanitize(text: String): String =
        text.replace(TOKEN_PARAM_RE, "$1=[redacted]").replace(JWT_RE, "[redacted-jwt]")

    private fun post(events: List<TelemetryEvent>): Boolean {
        val payload = mapOf(
            "platform" to "android",
            "app_version" to "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
            "events" to events
        )
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${BuildConfig.API_BASE_URL}/api/telemetry/client")
            .post(body)
            .build()
        return try {
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "post failed: ${e.message}")
            false
        }
    }
}
