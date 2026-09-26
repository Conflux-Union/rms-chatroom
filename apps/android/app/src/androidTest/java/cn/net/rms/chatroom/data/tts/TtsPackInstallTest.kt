package cn.net.rms.chatroom.data.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end coverage of the on-demand TTS pack against the debug dev server
 * (http://10.0.2.2:8000/tts, served by scripts/build-tts-pack.sh output):
 * download -> sha256 verify -> unzip -> System.load from filesDir ->
 * kokoro generation for zh and en -> delete.
 *
 * This is also the guard for the System.load-from-app-storage approach on
 * current targetSdk: if the platform ever blocks dlopen of downloaded libs,
 * this test fails with UnsatisfiedLinkError.
 */
@RunWith(AndroidJUnit4::class)
class TtsPackInstallTest {

    private fun awaitReady(manager: TtsModelManager, timeoutMs: Long = 600_000) = runBlocking {
        withTimeout(timeoutMs) {
            manager.state.first { it is TtsModelState.Ready || it is TtsModelState.Failed }
        }
    }

    @Test
    fun installLoadGenerateDelete() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val manager = TtsModelManager(context)
        assertTrue(manager.startInstall())

        val settled = awaitReady(manager)
        assertTrue("install failed: $settled", settled is TtsModelState.Ready)
        assertTrue(manager.isInstalled())
        assertTrue(manager.diskUsageBytes() > 100L * 1024 * 1024)

        val engine = SherpaTtsEngine(manager)
        engine.setDesired(true)
        runBlocking {
            withTimeout(120_000) {
                while (!engine.isReady()) delay(500)
            }
        }
        assertTrue(engine.isReady())

        val zh = engine.generateSamples("张三 加入了", "zh")
        assertTrue("no zh audio generated", zh != null && zh.samples.isNotEmpty())
        assertTrue("zh audio suspiciously short: ${zh!!.samples.size}", zh.samples.size > 24_000)
        val peak = zh.samples.maxOf { abs(it) }
        assertTrue("zh audio near silence (peak=$peak)", peak > 0.05f)

        val en = engine.generateSamples("Alice joined", "en")
        assertTrue("no en audio generated", en != null && en.samples.isNotEmpty())
        assertTrue(en!!.samples.size > 12_000)

        runBlocking { manager.delete() }
        assertFalse(manager.isInstalled())
        // The engine unloads reactively from its own state collector.
        runBlocking {
            withTimeout(30_000) {
                while (engine.isReady()) delay(200)
            }
        }
        assertFalse(engine.isReady())
    }
}
