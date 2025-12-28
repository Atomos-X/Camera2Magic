package com.nothing.camera2magic.viewmodel

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsViewModel(
    app: Application,
    private val prefs: SharedPreferences?
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    companion object {
        private const val KEY_PLAY_SOUND = "play_sound"
        private const val KEY_ENABLE_LOG = "enable_log"
        private const val KEY_INJECT_MENU_ENABLED = "inject_menu"
    }

    init {
        loadInitialSettings()
    }

    fun onPlaySoundToggled() {
        val newState = !_uiState.value.soundEnabled
        _uiState.update { it.copy(soundEnabled = newState) }
        saveBoolean(KEY_PLAY_SOUND, newState)
    }

    fun onEnableLogToggled() {
        val newState = !_uiState.value.logEnabled
        _uiState.update { it.copy(logEnabled = newState) }
        saveBoolean(KEY_ENABLE_LOG, newState)
    }

    fun onInjectMenuToggled() {
        val newState = !_uiState.value.injectMenuEnabled
        _uiState.update { it.copy(injectMenuEnabled = newState) }
        saveBoolean(KEY_INJECT_MENU_ENABLED, newState)
    }

    private fun loadInitialSettings() {
        _uiState.value = SettingsUiState(
            soundEnabled = prefs?.getBoolean(KEY_PLAY_SOUND, false) ?: false,
            logEnabled = prefs?.getBoolean(KEY_ENABLE_LOG, false) ?: false,
            injectMenuEnabled = prefs?.getBoolean(KEY_INJECT_MENU_ENABLED, false) ?: false,
        )
    }

    private fun saveBoolean(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }
}

data class SettingsUiState(
    val soundEnabled: Boolean = false,
    val logEnabled: Boolean = false,
    val injectMenuEnabled: Boolean = false,
)
