package com.nothing.camera2magic.hook

import android.content.ContentUris
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.provider.MediaStore
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.utils.Dog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SourceManager {
    private const val TAG = "[MediaSource]"
    private const val LOCAL_MEDIA_TYPE_VIDEO = 0x0000
    private const val LOCAL_MEDIA_TYPE_IMAGE = 0x0001
    private const val NETWORK_MEDIA_TYPE_RTSP = 0x0100
    private const val KEY_MODULE_ENABLED = "main_module_enabled"
    private const val KEY_PLAY_SOUND = "main_play_sound"
    private const val KEY_ENABLE_LOG = "main_enable_log"
    private const val KEY_INJECT_MENU = "main_inject_menu"
    private const val KEY_MANUALLY_ROTATE = "main_manually_rotate"
    private const val KEY_MEDIA_SOURCE = "media_source" // 0: local, 1: network
    private const val KEY_LOCAL_MEDIA_TYPE = "local_media_type" // 0: video, 1: image
    private const val KEY_LOCAL_VIDEO_ID = "local_video_id"
    private const val KEY_LOCAL_IMAGE_ID = "local_image_id"
    private const val KEY_NETWORK_RTSP_URI = "network_rtsp_uri"

    private lateinit var prefs: SharedPreferences

    private var lastMediaFingerprint: String = ""
    @Volatile
    private var moduleEnabled: Boolean = true
    @Volatile
    private var playSound: Boolean = false
    @Volatile
    var enableLog: Boolean = false
        private set
    @Volatile
    var injectMenuEnabled: Boolean = false
        private set
    @Volatile
    private var manuallyRotate: Boolean = false
    @Volatile
    private var mediaSource: Int = 0
    @Volatile
    private var mediaType: Int = 0

    @Volatile
    private var selectedMedia: Int = 0x0000
    @Volatile
    private var videoId: Long = -1L
    @Volatile
    private var imageId: Long = -1L
    @Volatile
    private var rtspUri: String = ""

    @Volatile
    var mediaIsReady: Boolean = false
        private set


    fun init(remotePrefs: SharedPreferences) {
        this.prefs = remotePrefs
        refreshPrefs()
    }

    fun refreshAndDispatch() {
        refreshPrefs()
        val fingerprint = getMediaFingerprint()
        if (fingerprint != lastMediaFingerprint) {
            dispatchMediaSourceToNative()
            lastMediaFingerprint = fingerprint
        }
    }

    private fun refreshPrefs() {
        try {
            if (!::prefs.isInitialized) return
            moduleEnabled = prefs.getBoolean(KEY_MODULE_ENABLED, true)
            playSound = prefs.getBoolean(KEY_PLAY_SOUND, false)
            enableLog = prefs.getBoolean(KEY_ENABLE_LOG, false)
            injectMenuEnabled = prefs.getBoolean(KEY_INJECT_MENU, false)
            manuallyRotate = prefs.getBoolean(KEY_MANUALLY_ROTATE, false)

            mediaSource = prefs.getInt(KEY_MEDIA_SOURCE, 0)
            mediaType = prefs.getInt(KEY_LOCAL_MEDIA_TYPE, 0)
            selectedMedia = (mediaSource shl 8) or mediaType

            videoId = prefs.getLong(KEY_LOCAL_VIDEO_ID, -1L)
            imageId = prefs.getLong(KEY_LOCAL_IMAGE_ID, -1L)
            rtspUri = prefs.getString(KEY_NETWORK_RTSP_URI, "") ?: ""

            NativeBridge.updateGlobalConfig(playSound, enableLog, manuallyRotate)

        } catch (e: Exception) { /* Do Nothing */ }
    }

    fun isReadyForHook(): Boolean = moduleEnabled && mediaIsReady

    private fun dispatchMediaSourceToNative() {
        mediaIsReady = false
        when (selectedMedia) {
            LOCAL_MEDIA_TYPE_VIDEO -> { updateVideoSource() }
            LOCAL_MEDIA_TYPE_IMAGE -> { }
            NETWORK_MEDIA_TYPE_RTSP -> { }
        }
    }

    private fun getMediaFingerprint(): String {
        return when (selectedMedia) {
            0x0000 -> "$selectedMedia:$videoId"
            0x0001 -> "$selectedMedia:$imageId"
            0x0100 -> "$selectedMedia:$rtspUri"
            else -> ""
        }
    }

    private fun updateVideoSource() {
        val context = GlobalState.appContext
        if (videoId == -1L) {
            mediaIsReady = false
            NativeBridge.resetVideoSource()
            return
        }

        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                mediaIsReady = NativeBridge.processVideo(
                    afd.parcelFileDescriptor.fd,
                    afd.startOffset,
                    afd.length
                )
            }
        } catch (s: SecurityException) {
            mediaIsReady = false
            Dog.e(TAG, s.toString(), null, true)
        } catch (e: Exception) {
            NativeBridge.resetVideoSource()
            mediaIsReady = false
            Dog.e(TAG, e.toString(), null, true)
        }
    }

    private suspend fun loadStaticImageFrom(id: Long): Bitmap = withContext(Dispatchers.IO) {
        val context = GlobalState.appContext
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return@withContext ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
    }
}