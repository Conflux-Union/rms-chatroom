package cn.net.rms.chatroom.ui.settings

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.net.rms.chatroom.data.local.SettingsPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsPreferences: SettingsPreferences
) : ViewModel() {

    val floatingWindowEnabled: StateFlow<Boolean> = settingsPreferences.floatingWindowEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _hasOverlayPermission = MutableStateFlow(checkOverlayPermission())
    val hasOverlayPermission: StateFlow<Boolean> = _hasOverlayPermission.asStateFlow()

    fun setFloatingWindowEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferences.setFloatingWindowEnabled(enabled)
        }
    }

    fun refreshOverlayPermission() {
        _hasOverlayPermission.value = checkOverlayPermission()
    }

    private fun checkOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }
}
