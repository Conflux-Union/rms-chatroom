package cn.net.rms.chatroom.ui.settings

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.net.rms.chatroom.data.local.SettingsPreferences
import cn.net.rms.chatroom.service.MessageConnectionService
import cn.net.rms.chatroom.util.BatteryOptimizationHelper
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

    val backgroundMessageServiceEnabled: StateFlow<Boolean> =
        settingsPreferences.backgroundMessageServiceEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _hasOverlayPermission = MutableStateFlow(checkOverlayPermission())
    val hasOverlayPermission: StateFlow<Boolean> = _hasOverlayPermission.asStateFlow()

    private val _isIgnoringBatteryOptimization = MutableStateFlow(checkBatteryOptimization())
    val isIgnoringBatteryOptimization: StateFlow<Boolean> = _isIgnoringBatteryOptimization.asStateFlow()

    fun setFloatingWindowEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferences.setFloatingWindowEnabled(enabled)
        }
    }

    fun setBackgroundMessageServiceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsPreferences.setBackgroundMessageServiceEnabled(enabled)
            if (enabled) {
                MessageConnectionService.start(context)
            } else {
                MessageConnectionService.stop(context)
            }
        }
    }

    fun refreshOverlayPermission() {
        _hasOverlayPermission.value = checkOverlayPermission()
        _isIgnoringBatteryOptimization.value = checkBatteryOptimization()
    }

    fun openBatteryOptimizationSettings() {
        BatteryOptimizationHelper.openBatterySettings(context)
    }

    private fun checkOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    private fun checkBatteryOptimization(): Boolean {
        return BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
    }
}
