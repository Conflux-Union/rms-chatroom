package cn.net.rms.chatroom.data.tts

import android.os.Build
import cn.net.rms.chatroom.BuildConfig

/**
 * Catalog of the on-demand TTS pack: which archives make up an install, where
 * they are downloaded from, and their digests.
 *
 * The pack is not bundled in the APK (the model alone is ~150 MB). Instead two
 * zips — a per-ABI sherpa-onnx runtime and the kokoro model files — are
 * attached to the `tts-assets-vN` release of Conflux-Union/rms-chatroom and
 * fetched through the same gh-proxy mirror chain as app updates. A pack bump
 * rides an app update: bump [PACK_VERSION] together with the digests below.
 *
 * Local layout after install (under filesDir/tts):
 *   runtime/<abi>/libonnxruntime.so, libsherpa-onnx-jni.so
 *   model/model.int8.onnx, tokens.txt, voices.bin, lexicon-*, *.fst,
 *        espeak-ng-data/, dict/
 */
object TtsPackCatalog {
    const val PACK_VERSION = 1
    private const val RUNTIME_VERSION = "1.13.8"

    // Model: kokoro-int8-multi-lang-v1_1 (zh + en, Apache-2.0, 24 kHz).
    private const val RUNTIME_ASSET_DIR =
        "https://github.com/Conflux-Union/rms-chatroom/releases/download/tts-assets-v1"
    private val MIRRORS = listOf(
        "https://gh-proxy.com",
        "https://moeyy.cn/gh-proxy",
        "https://ghproxy.net",
        ""
    )

    // Digests and sizes are produced by scripts/build-tts-pack.sh; regenerate
    // on any pack change.
    private val RUNTIME_ARM64_SHA256 =
        "c89bb646b2bbb2beebb7023b36157820e60a7708c71cba5a01c71527f10d9c55"
    private val RUNTIME_X86_64_SHA256 =
        "e4565416f03f1fe1c0f49094d8d3245d2ec0d0b8041b7e744675834fdfccfdb8"
    private val MODEL_SHA256 =
        "0f95257c0e9fa2a387e04492bd371dbb179e76f12ce9103a43130379ba3a8b7f"
    private val RUNTIME_ARM64_BYTES = 9_930_603L
    private val RUNTIME_X86_64_BYTES = 11_127_773L
    private val MODEL_ZIP_BYTES = 146_767_700L

    data class Artifact(
        val fileName: String,
        val sha256: String,
        val totalBytes: Long
    )

    /** Runtime zip for the device's primary ABI, or null on unsupported ABIs. */
    fun runtimeArtifact(): Artifact? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> Artifact(
            fileName = "tts-runtime-$RUNTIME_VERSION-arm64-v8a.zip",
            sha256 = RUNTIME_ARM64_SHA256,
            totalBytes = RUNTIME_ARM64_BYTES
        )
        "x86_64" -> Artifact(
            fileName = "tts-runtime-$RUNTIME_VERSION-x86_64.zip",
            sha256 = RUNTIME_X86_64_SHA256,
            totalBytes = RUNTIME_X86_64_BYTES
        )
        else -> null
    }

    fun modelArtifact(): Artifact = Artifact(
        fileName = "tts-model-kokoro-int8-v1_1.zip",
        sha256 = MODEL_SHA256,
        totalBytes = MODEL_ZIP_BYTES
    )

    /**
     * Candidate URLs for an artifact, cheapest-access first. Debug builds
     * point at a local dev server (host loopback from the emulator) so the
     * flow can be exercised without publishing assets.
     */
    fun urlsFor(artifact: Artifact): List<String> {
        val base = BuildConfig.TTS_PACK_BASE_URL
        if (base.isNotEmpty()) return listOf("$base/${artifact.fileName}")
        return MIRRORS.map { mirror ->
            if (mirror.isEmpty()) {
                "$RUNTIME_ASSET_DIR/${artifact.fileName}"
            } else {
                "$mirror/$RUNTIME_ASSET_DIR/${artifact.fileName}"
            }
        }
    }
}
