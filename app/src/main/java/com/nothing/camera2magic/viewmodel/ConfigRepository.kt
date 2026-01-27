package com.nothing.camera2magic.viewmodel

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import androidx.core.content.edit

private const val TAG = "[VCX][ConfigRepo]"
private const val GROUP_NAME = "camera_magic_config"

class ConfigRepository(private val prefs: SharedPreferences) {

    private var xposedService: XposedService? = null

    init {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                syncAllToRemote()
            }
            override fun onServiceDied(service: XposedService) {
                xposedService = null
            }
        })
    }

    private fun <T> save(key: String, value: T) {
        prefs.edit {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is String -> putString(key, value)
                else -> throw IllegalArgumentException("Unsupported type")
            }
        }

        xposedService?.let { service ->
            try {
                val remotePrefs = service.getRemotePreferences(GROUP_NAME)
                remotePrefs.edit {
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                        else -> throw IllegalArgumentException("Unsupported type")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update remote preferences", e)
            }
        }
    }

    private fun syncAllToRemote() {
        xposedService?.let { service ->
            val remotePrefs = service.getRemotePreferences(GROUP_NAME)
            remotePrefs.edit {
                prefs.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                        else -> throw IllegalArgumentException("Unsupported type")
                    }
                }
            }
        }
    }

    var moduleEnabled: Boolean
        get() = prefs.getBoolean("main_module_enabled", true)
        set(value) = save("main_module_enabled", value)

    var playSound: Boolean
        get() = prefs.getBoolean("main_play_sound", false)
        set(value) =save("main_play_sound", value)

    var enableLog: Boolean
        get() = prefs.getBoolean("main_enable_log", false)
        set(value) = save("main_enable_log", value)

    var injectMenu: Boolean
        get() = prefs.getBoolean("main_inject_menu", false)
        set(value) = save("main_inject_menu", value)

    var manuallyRotate: Boolean
        get() = prefs.getBoolean("main_manually_rotate", false)
        set(value) = save("main_manually_rotate", value)

    var mediaType: Int
        get() = prefs.getInt("media_type", 0)
        set(value) = save("media_type", value)

    var videoId: Long
        get() = prefs.getLong("local_video_id", -1L)
        set(value) = save("local_video_id", value)

    var imageId: Long
        get() = prefs.getLong("local_image_id", -1L)
        set(value) = save("local_image_id", value)
}