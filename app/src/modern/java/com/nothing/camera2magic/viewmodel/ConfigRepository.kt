package com.nothing.camera2magic.viewmodel

import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import androidx.core.content.edit

private const val TAG = "[VCX][ConfigRepo]"
private const val GROUP_NAME = "camera_magic_config"

enum class MediaSource(val value: Int, val label: String) {
    LOCAL(0, "Local"),
    NETWORK(1, "Network");
    companion object {
        fun fromValue(value: Int): MediaSource {
            return entries.find { it.value == value }
                ?: throw IllegalArgumentException("Invalid MediaSource value: $value")
        }
    }
}

enum class MediaType(val value: Int, val mimeType: String) {
    VIDEO(0, "video/*"),
    IMAGE(1, "image/*");
    companion object {
        fun fromValue(value: Int): MediaType {
            return entries.find { it.value == value }
                ?: throw IllegalArgumentException("Invalid MediaType value: $value")
        }
    }
}

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
                Log.e(TAG, "Failed to set_internal_state remote preferences", e)
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
            val remoteFile = service.openRemoteFile("xxx.txt")
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

    var mediaSource: Int
        get() = prefs.getInt("media_source", 0)
        set(value) {
            if (value !in MediaSource.entries.map { it.value }) {
                throw IllegalArgumentException("Invalid MediaSource value: $value")
            } else {
                save("media_source", value)
            }
        }

    var localMediaType: Int
        get() = prefs.getInt("local_media_type", 0)
        set(value) {
            if (value !in MediaType.entries.map { it.value }) {
                throw IllegalArgumentException("Invalid MediaType value: $value")
            } else {
                save("local_media_type", value)
            }
        }

    var videoId: Long
        get() = prefs.getLong("local_video_id", -1L)
        set(value) = save("local_video_id", value)

    var imageId: Long
        get() = prefs.getLong("local_image_id", -1L)
        set(value) = save("local_image_id", value)

    var rtspUri: String
        get() = prefs.getString("network_rtsp_uri", "") ?: ""
        set(value) = save("network_rtsp_uri", value)
}
