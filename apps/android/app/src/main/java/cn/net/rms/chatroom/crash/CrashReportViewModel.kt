package cn.net.rms.chatroom.crash

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.net.rms.chatroom.data.repository.BugReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CrashReportState {
    data object Idle : CrashReportState
    data object Submitting : CrashReportState
    data class Success(val reportId: String) : CrashReportState
    data class Failed(val reason: String) : CrashReportState
}

@HiltViewModel
class CrashReportViewModel @Inject constructor(
    private val bugReportRepository: BugReportRepository
) : ViewModel() {

    // Consume the dying process's report exactly once: the file is deleted on
    // first read, so a rotation or process restore cannot double-count it.
    val crashText: String? = CrashGuard.consumePendingCrash()

    var state: CrashReportState by mutableStateOf(CrashReportState.Idle)
        private set

    fun submitReport() {
        if (state == CrashReportState.Submitting) return
        state = CrashReportState.Submitting
        viewModelScope.launch {
            state = bugReportRepository
                .submitBugReport(crashLog = crashText)
                .fold(
                    onSuccess = { CrashReportState.Success(it) },
                    onFailure = { CrashReportState.Failed(it.message ?: it.javaClass.simpleName) }
                )
        }
    }
}
