package cn.net.rms.chatroom.crash

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import cn.net.rms.chatroom.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort uncaught-exception handler. Instead of the silent system
 * teardown it persists the crash, relaunches into [CrashReportActivity] and
 * kills the process; the system then restarts the process with the report
 * screen as the task root.
 *
 * Everything here runs on the crashing thread of a dying process: no
 * coroutines, no DI, and any failure falls back to the platform handler.
 * Installed in Application.onCreate *before* TelemetryReporter's handler, so
 * the chain is: telemetry persist -> CrashGuard handoff -> process kill.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val DIR_NAME = "crash"
    private const val PENDING_FILE = "pending.txt"
    private const val LAST_CRASH_FILE = "last_crash_ms"
    private const val MAX_REPORT_CHARS = 24_000

    // Two crashes this close together across process restarts can only be a
    // boot loop (e.g. the report screen crashing on launch); break it with
    // the platform handler instead of relaunching forever.
    private const val LOOP_GUARD_MS = 3_000L

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)

    private lateinit var appContext: Context
    private var platformHandler: Thread.UncaughtExceptionHandler? = null

    /** True from the report screen's first onCreate to onPause: a crash in
     *  that window means the screen itself is broken and must not relaunch. */
    @Volatile
    var crashScreenActive: Boolean = false
        private set

    fun install(context: Context) {
        appContext = context.applicationContext
        platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handle(thread, throwable)
        }
    }

    fun markCrashScreenActive(active: Boolean) {
        crashScreenActive = active
    }

    /** Read and delete the report written by the dying process. */
    fun consumePendingCrash(): String? = try {
        val file = pendingFile()
        if (!file.exists()) null else file.readText().also { file.delete() }.ifBlank { null }
    } catch (_: Exception) {
        null
    }

    private fun handle(thread: Thread, throwable: Throwable) {
        if (crashScreenActive || withinLoopGuard()) {
            platformHandler?.uncaughtException(thread, throwable)
            return
        }
        try {
            persist(thread, throwable)
            appContext.startActivity(
                Intent(appContext, CrashReportActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "crash handoff failed", e)
            platformHandler?.uncaughtException(thread, throwable)
            return
        }
        // The process state is undefined after an uncaught exception; let the
        // system restart it to host the report screen.
        Process.killProcess(Process.myPid())
        Runtime.getRuntime().exit(10)
    }

    private fun withinLoopGuard(): Boolean = try {
        val file = lastCrashFile()
        val now = System.currentTimeMillis()
        val last = file.readText().trim().toLongOrNull()
        file.writeText(now.toString())
        last != null && now - last < LOOP_GUARD_MS
    } catch (_: Exception) {
        false
    }

    private fun persist(thread: Thread, throwable: Throwable) {
        val dir = File(appContext.filesDir, DIR_NAME)
        dir.mkdirs()
        val report = buildString {
            appendLine("time: ${timeFormat.format(Date())}")
            appendLine("thread: ${thread.name}")
            appendLine("version: ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
            appendLine()
            append(Log.getStackTraceString(throwable).take(MAX_REPORT_CHARS))
        }
        File(dir, PENDING_FILE).writeText(report)
    }

    private fun pendingFile() = File(File(appContext.filesDir, DIR_NAME), PENDING_FILE)

    private fun lastCrashFile() = File(File(appContext.filesDir, DIR_NAME), LAST_CRASH_FILE)
}
