package cn.net.rms.chatroom.ui.settings

import android.content.BroadcastReceiver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.net.rms.chatroom.data.api.AppUpdateResponse
import cn.net.rms.chatroom.data.repository.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data class UpdateAvailable(val update: AppUpdateResponse) : UpdateCheckState
    data class Downloading(val update: AppUpdateResponse) : UpdateCheckState
    data object Failed : UpdateCheckState
}

/**
 * Manual update check from the About screen. Reuses UpdateRepository for
 * discovery and download; download progress lives in the system
 * notification (this screen does not poll it), and completion
 * auto-launches the installer.
 */
@HiltViewModel
class UpdateCheckViewModel @Inject constructor(
    private val updateRepository: UpdateRepository
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val state: StateFlow<UpdateCheckState> = _state.asStateFlow()

    private var downloadReceiver: BroadcastReceiver? = null

    fun checkForUpdate() {
        if (_state.value == UpdateCheckState.Checking) return
        _state.value = UpdateCheckState.Checking
        viewModelScope.launch {
            updateRepository.checkUpdate()
                .onSuccess { update ->
                    _state.value = if (update != null) {
                        UpdateCheckState.UpdateAvailable(update)
                    } else {
                        UpdateCheckState.UpToDate
                    }
                }
                .onFailure {
                    _state.value = UpdateCheckState.Failed
                }
        }
    }

    fun downloadUpdate(update: AppUpdateResponse) {
        if (downloadReceiver != null) return
        downloadReceiver = updateRepository.registerDownloadReceiver { success ->
            if (success) updateRepository.installApk()
        }
        updateRepository.downloadUpdate(update.downloadUrl)
        _state.value = UpdateCheckState.Downloading(update)
    }

    fun dismissResult() {
        if (_state.value != UpdateCheckState.Checking) {
            _state.value = UpdateCheckState.Idle
        }
    }

    override fun onCleared() {
        downloadReceiver?.let { updateRepository.unregisterDownloadReceiver(it) }
        super.onCleared()
    }
}
