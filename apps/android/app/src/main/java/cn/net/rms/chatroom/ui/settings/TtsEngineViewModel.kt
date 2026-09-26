package cn.net.rms.chatroom.ui.settings

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.net.rms.chatroom.data.tts.TtsModelManager
import cn.net.rms.chatroom.data.tts.TtsModelState
import cn.net.rms.chatroom.data.tts.TtsPackCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the system TTS engine can do for the app's current locale. */
sealed interface SystemTtsStatus {
    data object Checking : SystemTtsStatus

    /** Engine present and it has a voice for the locale (e.g. "zh-CN"). */
    data class Available(val languageTag: String) : SystemTtsStatus

    /** Engine present but no voice data for the locale. */
    data object NoVoiceForLanguage : SystemTtsStatus

    /** No engine / init failed at all. */
    data object Unavailable : SystemTtsStatus
}

@HiltViewModel
class TtsEngineViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsModelManager: TtsModelManager
) : ViewModel() {

    // Held only to reference the engine from inside its own init callback.
    private val probeEngine = AtomicReference<TextToSpeech>()

    val packState: StateFlow<TtsModelState> = ttsModelManager.state

    private val _systemStatus = MutableStateFlow<SystemTtsStatus>(SystemTtsStatus.Checking)
    val systemStatus: StateFlow<SystemTtsStatus> = _systemStatus.asStateFlow()

    private val _installedBytes = MutableStateFlow(0L)
    val installedBytes: StateFlow<Long> = _installedBytes.asStateFlow()

    init {
        probeSystemTts()
        viewModelScope.launch {
            packState.collect { state ->
                _installedBytes.value =
                    if (state is TtsModelState.Ready) ttsModelManager.diskUsageBytes() else 0L
            }
        }
    }

    /**
     * One-shot probe. The engine instance is released right after reading
     * setLanguage()'s result — presence reporting is all it is used for here.
     */
    fun probeSystemTts() {
        _systemStatus.value = SystemTtsStatus.Checking
        val probe = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                _systemStatus.value = SystemTtsStatus.Unavailable
                return@TextToSpeech
            }
            // The callback may race past shutdown; a dead engine reports
            // LANG_NOT_SUPPORTED, so read the result before releasing.
            val engine = probeEngine.get()
            if (engine == null) {
                _systemStatus.value = SystemTtsStatus.Unavailable
                return@TextToSpeech
            }
            val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
            val result = engine.setLanguage(locale)
            runCatching { engine.shutdown() }
            _systemStatus.value = when (result) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED ->
                    SystemTtsStatus.NoVoiceForLanguage
                else -> SystemTtsStatus.Available(locale.toLanguageTag())
            }
        }
        probeEngine.set(probe)
    }

    fun startInstall(): Boolean = ttsModelManager.startInstall()

    fun cancelInstall() = ttsModelManager.cancelInstall()

    fun delete() {
        viewModelScope.launch { ttsModelManager.delete() }
    }

    /** Combined download size of both artifacts, for the action button label. */
    fun totalDownloadBytes(): Long {
        val runtime = TtsPackCatalog.runtimeArtifact()
        val model = TtsPackCatalog.modelArtifact()
        return (runtime?.totalBytes ?: 0L) + model.totalBytes
    }
}
