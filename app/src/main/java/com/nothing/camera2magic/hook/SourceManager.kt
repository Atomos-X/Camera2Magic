package com.nothing.camera2magic.hook

import android.content.ContentUris
import android.content.SharedPreferences
import android.provider.MediaStore
import androidx.core.net.toUri
import com.nothing.camera2magic.GlobalState
import java.io.FileNotFoundException

object SourceManager {

    private const val TAG = "[MediaSource]"
    private const val KEY_MODULE_ENABLED = "main_module_enabled"
    private const val KEY_PLAY_SOUND = "main_play_sound"
    private const val KEY_ENABLE_LOG = "main_enable_log"
    private const val KEY_MEDIA_SOURCE = "media_source" // 0: local, 1: network
    private const val KEY_LOCAL_MEDIA_TYPE = "local_media_type" // 0: video, 1: image
    private const val KEY_LOCAL_VIDEO_ID = "local_video_id"
    private const val KEY_LOCAL_IMAGE_ID = "local_image_id"
    private const val KEY_NETWORK_RTSP_URI = "network_rtsp_uri"

    private lateinit var prefs: SharedPreferences

    @Volatile
    var moduleEnabled: Boolean = true
        private set
    @Volatile
    var playSound: Boolean = false
        private set
    @Volatile
    var enableLog: Boolean = false
        private set
    @Volatile
    private var mediaSource: Int = 0
    @Volatile
    private var mediaType: Int = 0
    @Volatile
    private var selectedMedia: Int = 0x0000
    @Volatile
    var toastMessage: String? = null
    @Volatile
    private var videoId: Long = -1L
    @Volatile
    private var imageId: Long = -1L
    @Volatile
    private var rtspUri: String = ""

    val readyForHook: Boolean
        get() = moduleEnabled
    @Volatile
    var validMedia: ValidMedia? = null
        private set

    fun init(remotePrefs: SharedPreferences) {
        this.prefs = remotePrefs
        refreshPrefs()
    }

    private fun updateState(media: ValidMedia?, message: String) {
        validMedia = media
        toastMessage = message
    }

    fun refreshAndDispatch() {
        refreshPrefs()
        if (!moduleEnabled) {
            updateState(null, "Module disabled.")
            return
        }
        val magicType = MagicType.fromValue(selectedMedia)
        val label = magicType.label
        val rawUri = when(magicType) {
            MagicType.LOCAL_VIDEO -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
            MagicType.LOCAL_IMAGE -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)
            MagicType.NETWORK_RTSP -> rtspUri.toUri()
        }

        if (magicType == MagicType.NETWORK_RTSP) {
            updateState(ValidMedia(rawUri, magicType), "$rawUri")
            return
        }

        runCatching {
            GlobalState.appContext.contentResolver.openFileDescriptor(rawUri, "r")?.close()
            updateState(ValidMedia(rawUri, magicType), "$label file is ready.")
        }.onFailure { e ->
            val msg = when (e) {
                is SecurityException -> "Permission denied: Host app cannot access the $label."
                is FileNotFoundException -> "$label File not found."
                else -> "pick the $label file in module ui first."
            }
            updateState(null, msg)
        }
    }

    private fun refreshPrefs() {
        try {
            if (!::prefs.isInitialized) return
            moduleEnabled = prefs.getBoolean(KEY_MODULE_ENABLED, true)
            playSound = prefs.getBoolean(KEY_PLAY_SOUND, false)
            enableLog = prefs.getBoolean(KEY_ENABLE_LOG, false)

            mediaSource = prefs.getInt(KEY_MEDIA_SOURCE, 0)
            mediaType = prefs.getInt(KEY_LOCAL_MEDIA_TYPE, 0)
            selectedMedia = (mediaSource shl 8) or mediaType

            videoId = prefs.getLong(KEY_LOCAL_VIDEO_ID, -1L)
            imageId = prefs.getLong(KEY_LOCAL_IMAGE_ID, -1L)
            rtspUri = prefs.getString(KEY_NETWORK_RTSP_URI, "") ?: ""

        } catch (e: Exception) { /* Do Nothing */ }
    }
}