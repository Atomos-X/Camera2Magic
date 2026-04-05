package com.nothing.camera2magic.viewmodel

import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream


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

enum class MediaType(val value: Int, val label: String) {
    VIDEO(0, "video"),
    IMAGE(1, "image");
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
                is String? -> putString(key, value)
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
        }
    }

    fun prepareRemoteMedia(fileName: String, inputStream: InputStream): Boolean {
        return runCatching {
            val pfd = xposedService?.openRemoteFile(fileName) ?: return false
            pfd.use {
                FileOutputStream(it.fileDescriptor).use { fos ->
                    fos.channel.truncate(0)
                    inputStream.copyTo(fos, 1024 * 32)
                }
            }
            true
        }.getOrDefault(false)
    }

    fun deleteRemoteMedia(fileName: String) {
        xposedService?.deleteRemoteFile(fileName)
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

    var videoUri: String?
        get() = prefs.getString("local_video_uri", null)
        set(value) = save("local_video_uri", value)

    var remoteVideoFile: String?
        get() = prefs.getString("remote_video_file", null)
        set(value) = save("remote_video_file", value)

    var imageUri: String?
        get() = prefs.getString("local_image_uri", null)
        set(value) = save("local_image_uri", value)

    var remoteImageFile: String?
        get() = prefs.getString("remote_image_file", null)
        set(value) = save("remote_image_file", value)

    var rtspUri: String?
        get() = prefs.getString("network_rtsp_uri", null)
        set(value) = save("network_rtsp_uri", value)
}
