package com.nothing.camera2magic.viewmodel

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

private const val TAG = "[VCX][ConfigRepo]"
private const val GROUP_NAME = "camera_magic_config"

class ConfigRepository(private val prefs: SharedPreferences) {

    private var xposedService: XposedService? = null

    init {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                xposedService = service
                Log.w(TAG, "LSP_Service,服务链接成功，当前权限等级：${service.frameworkPrivilege}")
                syncAllToRemote()
            }
            override fun onServiceDied(service: XposedService) {
                xposedService = null
            }
        })
    }

    private fun <T> save(key: String, value: T) {
        val editor = prefs.edit()
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            else -> throw IllegalArgumentException("Unsupported type")
        }
        editor.apply()
        xposedService?.let { service ->
            try {
                val remotePrefs = service.getRemotePreferences(GROUP_NAME)
                val remoteEditor = remotePrefs.edit()
                when (value) {
                    is Boolean -> remoteEditor.putBoolean(key, value)
                    is Int -> remoteEditor.putInt(key, value)
                    is Long -> remoteEditor.putLong(key, value)
                    is Float -> remoteEditor.putFloat(key, value)
                    is String -> remoteEditor.putString(key, value)
                    else -> throw IllegalArgumentException("Unsupported type")
                }
                remoteEditor.apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update remote preferences", e)
            }
        }
    }

    private fun syncAllToRemote() {
        xposedService?.let { service ->
            val remoteEditor = service.getRemotePreferences(GROUP_NAME).edit()
            prefs.all.forEach { (key, value) ->
                when (value) {
                    is Boolean -> remoteEditor.putBoolean(key, value)
                    is Int -> remoteEditor.putInt(key, value)
                    is Long -> remoteEditor.putLong(key, value)
                    is Float -> remoteEditor.putFloat(key, value)
                    is String -> remoteEditor.putString(key, value)
                    else -> throw IllegalArgumentException("Unsupported type")
                }
            }
            remoteEditor.apply()
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