package cn.net.rms.chatroom.data.tts

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface TtsModelState {
    /** Nothing installed and no download in flight. */
    data object NotInstalled : TtsModelState

    data class Downloading(
        /** Which artifact is transferring; runtime first, then the model. */
        val phase: Phase,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : TtsModelState {
        enum class Phase { RUNTIME, MODEL }

        /** Overall percent across both artifacts (0..100). */
        val percent: Int
            get() = if (totalBytes <= 0) 0
            else ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    data object Installing : TtsModelState

    data class Ready(val packVersion: Int) : TtsModelState

    /** Terminal: the last install attempt failed; nothing is installed. */
    data class Failed(val message: String) : TtsModelState
}

/**
 * Downloads and installs the on-demand TTS pack (sherpa-onnx runtime .so +
 * kokoro model) into filesDir/tts, and reports a [TtsModelState].
 *
 * Install is transactional: archives stream into cacheDir, then unzip into a
 * staging dir, then a marker file swaps the install in. A crash mid-install
 * leaves at worst a stale staging dir, cleaned by the next attempt.
 */
@Singleton
class TtsModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TtsModelManager"
        private const val ROOT_DIR = "tts"
        private const val STAGING_DIR = "tts-staging"
        private const val MARKER_FILE = "installed.json"
        // Progress emissions are throttled by bytes, not time: the flow only
        // feeds a UI progress bar.
        private const val PROGRESS_INTERVAL_BYTES = 512L * 1024
    }

    private val client = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var installJob: Job? = null

    private val _state = MutableStateFlow<TtsModelState>(initialState())
    val state: StateFlow<TtsModelState> = _state.asStateFlow()

    val rootDir: File get() = File(context.filesDir, ROOT_DIR)
    val runtimeDir: File get() = File(rootDir, "runtime")
    val modelDir: File get() = File(rootDir, "model")

    private fun initialState(): TtsModelState {
        val marker = File(rootDir, MARKER_FILE)
        if (!marker.exists()) return TtsModelState.NotInstalled
        val version = runCatching { marker.readText().trim().toIntOrNull() }.getOrNull()
        return if (version != null && version > 0) TtsModelState.Ready(version)
        else TtsModelState.NotInstalled
    }

    fun isInstalled(): Boolean = _state.value is TtsModelState.Ready

    fun diskUsageBytes(): Long = dirSize(rootDir)

    /**
     * Start an install. Returns false when one is already in flight or the
     * device ABI has no runtime build. Failures land in [state] as Failed.
     */
    fun startInstall(): Boolean {
        if (TtsPackCatalog.runtimeArtifact() == null) {
            _state.value = TtsModelState.Failed("Unsupported device ABI")
            return false
        }
        return synchronized(this) {
            if (installJob?.isActive == true) return false
            installJob = scope.launch { install() }
            true
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
    }

    /** Wipes the install (or any partial one). Blocks until the delete lands. */
    suspend fun delete() {
        installJob?.cancel()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                rootDir.deleteRecursively()
                File(context.filesDir, STAGING_DIR).deleteRecursively()
            }
            _state.value = TtsModelState.NotInstalled
        }
    }

    private suspend fun install() {
        mutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    val runtime = TtsPackCatalog.runtimeArtifact()
                        ?: throw IOException("Unsupported device ABI")
                    val model = TtsPackCatalog.modelArtifact()
                    val totalBytes = runtime.totalBytes + model.totalBytes

                    // Downloaded archives are plain cache; the OS may reap
                    // them under storage pressure between attempts.
                    val downloadDir = File(context.cacheDir, "tts-download").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    // Staging lives OUTSIDE rootDir: the final swap deletes
                    // rootDir wholesale, which must not take the staged tree
                    // with it.
                    val staging = File(context.filesDir, STAGING_DIR)
                    staging.deleteRecursively()

                    val runtimeZip = download(
                        artifact = runtime,
                        urls = TtsPackCatalog.urlsFor(runtime),
                        phase = TtsModelState.Downloading.Phase.RUNTIME,
                        alreadyDone = 0L,
                        reportTotal = totalBytes
                    )
                    val modelZip = download(
                        artifact = model,
                        urls = TtsPackCatalog.urlsFor(model),
                        phase = TtsModelState.Downloading.Phase.MODEL,
                        alreadyDone = runtime.totalBytes,
                        reportTotal = totalBytes
                    )

                    _state.value = TtsModelState.Installing
                    val stagedRoot = File(staging, ROOT_DIR)
                    unzip(runtimeZip, File(stagedRoot, "runtime"))
                    unzip(modelZip, File(stagedRoot, "model"))
                    verifyLayout(stagedRoot)

                    // Swap in: clear the previous install, move staging into
                    // place, then write the marker last so Ready implies a
                    // complete tree.
                    rootDir.deleteRecursively()
                    check(stagedRoot.renameTo(rootDir)) { "staging rename failed" }
                    File(rootDir, MARKER_FILE).writeText(TtsPackCatalog.PACK_VERSION.toString())
                    downloadDir.deleteRecursively()
                    _state.value = TtsModelState.Ready(TtsPackCatalog.PACK_VERSION)
                    Log.i(TAG, "TTS pack v${TtsPackCatalog.PACK_VERSION} installed")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.IO) { File(context.cacheDir, "tts-download").deleteRecursively() }
                _state.value = TtsModelState.NotInstalled
                Log.i(TAG, "TTS pack install cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "TTS pack install failed", e)
                _state.value = TtsModelState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Streams the first URL that answers, hashing while writing; mirrors are
     * only rotated between whole attempts, so a partial file never survives a
     * mirror switch.
     */
    private fun download(
        artifact: TtsPackCatalog.Artifact,
        urls: List<String>,
        phase: TtsModelState.Downloading.Phase,
        alreadyDone: Long,
        reportTotal: Long
    ): File {
        val dest = File(File(context.cacheDir, "tts-download"), artifact.fileName)
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
                    val body = response.body ?: throw IOException("Empty body from $url")

                    _state.value = TtsModelState.Downloading(phase, alreadyDone, reportTotal)
                    val digest = MessageDigest.getInstance("SHA-256")
                    dest.outputStream().use { out ->
                        val source = body.byteStream()
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var nextProgress = 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            if (downloaded >= nextProgress) {
                                _state.value =
                                    TtsModelState.Downloading(phase, alreadyDone + downloaded, reportTotal)
                                nextProgress = downloaded + PROGRESS_INTERVAL_BYTES
                            }
                        }
                    }

                    if (artifact.totalBytes > 0 && dest.length() != artifact.totalBytes) {
                        throw IOException(
                            "Size mismatch for ${artifact.fileName}: " +
                                "expected ${artifact.totalBytes}, got ${dest.length()}"
                        )
                    }
                    val sha = okio.ByteString.of(*digest.digest()).hex()
                    if (!sha.equals(artifact.sha256, ignoreCase = true)) {
                        throw IOException("sha256 mismatch for ${artifact.fileName}")
                    }
                    return dest
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                dest.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                dest.delete()
                Log.w(TAG, "Download via $url failed: ${e.message}")
            }
        }
        throw IOException("All mirrors failed for ${artifact.fileName}", lastError)
    }

    /** Extracts a zip flat into [dest]; entries keep their relative paths. */
    private fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        val destCanonical = dest.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(destCanonical)) {
                    throw IOException("Zip entry escapes destination: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
                input.closeEntry()
            }
        }
    }

    /** Fail before the swap if the pack layout ever drifts from the engine's expectations. */
    private fun verifyLayout(root: File) {
        val required = listOf(
            File(root, "model/model.int8.onnx"),
            File(root, "model/tokens.txt"),
            File(root, "model/voices.bin"),
            File(root, "model/lexicon-zh.txt"),
            File(root, "model/lexicon-us-en.txt"),
            File(root, "model/espeak-ng-data"),
            File(root, "model/dict"),
            File(root, "runtime/${android.os.Build.SUPPORTED_ABIS.firstOrNull()}/libsherpa-onnx-jni.so"),
            File(root, "runtime/${android.os.Build.SUPPORTED_ABIS.firstOrNull()}/libonnxruntime.so")
        )
        val missing = required.filterNot { it.exists() }.map { it.relativeToOrSelf(root).path }
        if (missing.isNotEmpty()) throw IOException("Incomplete pack, missing: $missing")
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }
}
