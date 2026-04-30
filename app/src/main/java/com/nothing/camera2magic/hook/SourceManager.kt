package com.nothing.camera2magic.hook

import android.content.ContentUris
import android.content.SharedPreferences
import android.provider.MediaStore
import androidx.core.net.toUri
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.utils.Dog
import java.io.FileNotFoundException

object SourceManager {

    private const val TAG = "[MediaSource]"
    private const val KEY_MODULE_ENABLED = "main_module_enabled"
    private const val KEY_PLAY_SOUND = "main_play_sound"
    private const val KEY_ENABLE_LOG = "main_enable_log"
    private const val KEY_MEDIA_SOURCE = "media_source" // 0: local, 1: network
    private const val KEY_LOCAL_MEDIA_TYPE = "local_media_type" // 0: video, 1: image
    private const val KEY_VIDEO_FILE = "remote_video_file"
    private const val KEY_IMAGE_FILE = "remote_image_file"
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
    private var videoFile: String? = null
    @Volatile
    private var imageFile: String? = null
    @Volatile
    private var rtspUri: String? = null

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

        val type = MagicType.fromValue(selectedMedia)
        val label = type.label

        val source = when (type) {
            MagicType.LOCAL_VIDEO -> videoFile
            MagicType.LOCAL_IMAGE -> imageFile
            MagicType.NETWORK_RTSP -> rtspUri
        }

        if (source == null) {
            updateState(null, "$label does not exist")
            return
        }

        updateState(ValidMedia(source, type), "$label is ready.")
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

            videoFile = prefs.getString(KEY_VIDEO_FILE, null)
            imageFile = prefs.getString(KEY_IMAGE_FILE, null)
            rtspUri = prefs.getString(KEY_NETWORK_RTSP_URI, null)

        } catch (e: Exception) { /* Do Nothing */ }
    }
}