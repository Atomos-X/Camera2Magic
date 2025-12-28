package com.nothing.camera2magic.viewmodel

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

@Suppress("UNCHECKED_CAST")
class ViewModelFactory(
    private val app: Application,
    private val prefs: SharedPreferences? // 接收一个可空的 SharedPreferences
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        // 如果 prefs 为 null，我们仍然创建 ViewModel，但传入 null
        return when {
            modelClass.isAssignableFrom(SpotlightViewModel::class.java) -> {
                SpotlightViewModel(app, prefs) as T
            }
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                SettingsViewModel(app, prefs) as T
            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }

    }
}
