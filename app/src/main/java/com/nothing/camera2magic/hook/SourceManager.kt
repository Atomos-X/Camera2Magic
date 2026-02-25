package com.nothing.camera2magic.hook

import android.content.ContentUris
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import com.nothing.camera2magic.GlobalState
import java.io.FileNotFoundException


object SourceManager {
    private const val TAG = "[MediaSource]"
    private const val LOCAL_MEDIA_TYPE_VIDEO = 0x0000
    private const val LOCAL_MEDIA_TYPE_IMAGE = 0x0001
    private const val NETWORK_MEDIA_TYPE_RTSP = 0x0100
    private const val KEY_MODULE_ENABLED = "main_module_enabled"
    private const val KEY_PLAY_SOUND = "main_play_sound"
    private const val KEY_ENABLE_LOG = "main_enable_log"
    private const val KEY_MEDIA_SOURCE = "media_source" // 0: local, 1: network
    private const val KEY_LOCAL_MEDIA_TYPE = "local_media_type" // 0: video, 1: image
    private const val KEY_LOCAL_VIDEO_ID = "local_video_id"
    private const val KEY_LOCAL_IMAGE_ID = "local_image_id"
    private const val KEY_NETWORK_RTSP_URI = "network_rtsp_uri"

    private lateinit var prefs: SharedPreferences

    private var lastMediaFingerprint: String = ""
    @Volatile
    var moduleEnabled: Boolean = true
        private set
    @Volatile
    private var playSound: Boolean = false
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

    @Volatile
    var mediaIsReady: Boolean = false
        private set


    fun init(remotePrefs: SharedPreferences) {
        this.prefs = remotePrefs
        refreshPrefs()
    }

    fun refreshAndDispatch() {
        refreshPrefs()
        if (!moduleEnabled) {
            toastMessage = "模块未启用"
            return
        }
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

            mediaSource = prefs.getInt(KEY_MEDIA_SOURCE, 0)
            mediaType = prefs.getInt(KEY_LOCAL_MEDIA_TYPE, 0)
            selectedMedia = (mediaSource shl 8) or mediaType

            videoId = prefs.getLong(KEY_LOCAL_VIDEO_ID, -1L)
            imageId = prefs.getLong(KEY_LOCAL_IMAGE_ID, -1L)
            rtspUri = prefs.getString(KEY_NETWORK_RTSP_URI, "") ?: ""

            NativeBridge.updateGlobalConfig(playSound, enableLog)

        } catch (e: Exception) { /* Do Nothing */ }
    }

    fun isReadyForHook(): Boolean = moduleEnabled && mediaIsReady

    private fun dispatchMediaSourceToNative() {
        mediaIsReady = false
        when (selectedMedia) {
            LOCAL_MEDIA_TYPE_VIDEO -> { updateVideoSource() }
            LOCAL_MEDIA_TYPE_IMAGE -> { updateImageSource() }
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
        if (videoId == -1L) {
            NativeBridge.resetMediaSource()
            updateState(false, "未设置视频")
            return
        }
        val contentResolver = GlobalState.appContext.contentResolver
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)

        val result = runCatching {
            val afd = contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("无法打开视频：$uri")
            afd.use { it ->
                NativeBridge.processVideo(
                    it.parcelFileDescriptor.fd,
                    it.startOffset,
                    it.length
                )
            }
        }

        result.onSuccess { success ->
            val msg = if (success) "视频已就绪" else "视频接收异常(Native)"
            updateState(success, msg)
        }.onFailure { e ->
            val msg = when(e) {
                is SecurityException -> "无权限读取视频"
                else -> "图片传输异常(Java IO)"
            }
            updateState(false, msg)
        }
    }

    private fun updateImageSource() {
        if (imageId == -1L) {
            NativeBridge.resetMediaSource()
            updateState(false, "未设置图片")
            return
        }

        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)
        val contentResolver = GlobalState.appContext.contentResolver
        val result = runCatching {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, this)
                }
            }

            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            options.inSampleSize = calculateInSampleSize(options, 1080, 1920)

            val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: throw IllegalStateException("无法解码图片")

            try {
                NativeBridge.processBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

        result.onSuccess { success ->
            val msg = if (success) "图片已就绪" else "图片接收失败(Native)"
            updateState(success, msg)
        }.onFailure { e ->
            val msg = when (e) {
                is SecurityException -> "无权限读取图片"
                else -> "图片传输异常(Java IO)"
            }
            updateState(false, msg)
        }
    }

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun updateState(ready: Boolean, message: String) {
        mediaIsReady = ready
        toastMessage = message
    }
}