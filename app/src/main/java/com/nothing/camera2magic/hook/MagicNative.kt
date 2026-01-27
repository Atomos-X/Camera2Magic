@file:Suppress("DEPRECATION")

package com.nothing.camera2magic.hook

import android.content.ContentUris
import android.content.SharedPreferences
import android.hardware.Camera
import android.provider.MediaStore
import android.view.Surface
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.utils.Dog
import com.nothing.camera2magic.utils.FloatWindowManager
import com.nothing.camera2magic.utils.PreviewNV21Helper
import java.lang.ref.WeakReference

object MagicNative {
    private const val TAG = "[NATIVE]"
    private const val KEY_VIDEO_ID = "local_video_id"
    private const val KEY_MODULE_ENABLED = "main_module_enabled"
    private const val KEY_PLAY_SOUND = "main_play_sound"
    private const val KEY_ENABLE_LOG = "main_enable_log"
    private const val KEY_INJECT_MENU = "main_inject_menu"
    private const val KEY_MANUALLY_ROTATE = "main_manually_rotate"

    private lateinit var prefs: SharedPreferences

    fun init(remotePrefs: SharedPreferences) {
        this.prefs = remotePrefs
        refreshPrefs()
    }

    private var cachedBuffer: ByteArray? = null

    var lastFrameWidth = 0
    var lastFrameHeight = 0

    var camera1Callback: Camera.PreviewCallback? = null
    var currentCamera1: Camera? = null
    var previewCallback: ((data: ByteArray, width: Int, height: Int) -> Unit)? = null
    @Volatile
    private var videoID: Long = -1L
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
    var injectMenuEnabled: Boolean = false
        private set
    @Volatile
    var manuallyRotate: Boolean = false
        private set
    @Volatile
    var videoSourceIsReady: Boolean = false
        private set
    @Volatile
    var hasValidFrame = false

    @Volatile
    private var lastRegisteredSurface: WeakReference<Surface>? = null
    private val surfaceLock = Any()

    @JvmStatic
    fun ensureBuffer(size: Int) {
        if (cachedBuffer != null && cachedBuffer!!.size == size) {
            return
        }
        hasValidFrame = false
        cachedBuffer = ByteArray(size)
    }
    @JvmStatic
    fun getCachedBuffer(): ByteArray? {
        return cachedBuffer
    }
    @JvmStatic
    fun onFrameDataUpdated(width: Int, height: Int) {
        lastFrameWidth = width
        lastFrameHeight = height
        hasValidFrame = true
        val buffer = cachedBuffer ?: return
        val expectedSize = width * height * 3 / 2
        if (buffer.size < expectedSize) return

        PreviewNV21Helper.processFrame(buffer,width,height) { bitmap ->
            FloatWindowManager.updatePreview(bitmap)
        }

        try {
            if (camera1Callback != null && currentCamera1 != null) {
                camera1Callback?.onPreviewFrame(buffer, currentCamera1)
            }
        } catch (e: Exception) {
            Dog.i("VCX", "Error in Camera1 callback: ${e.message}", enableLog)
        }
        // 分发给 camera2
        previewCallback?.invoke(buffer, width, height)
    }

    @JvmStatic
    external fun updateNativeConfig(playSound: Boolean, enableLog: Boolean, manuallyRotate: Boolean)
    @JvmStatic
    external fun registerSurface(apiLevel: Int, cameraId: String, sensorOrientation: Int, pictureWidth: Int, pictureHeight: Int,
                                 packageName: String, displayOrientation: Int, surface: Surface)
    @JvmStatic
    external fun setDisplayOrientation(orientation: Int)
    @JvmStatic
    external fun getSurfaceInfo(surface: Surface): IntArray
    @JvmStatic
    external fun resetVideoSource()
    @JvmStatic
    external fun processVideo(fd: Int, offset: Long, length: Long): Boolean
    @JvmStatic
    external fun needStopRenderer()
    @JvmStatic
    external fun needStartRenderer()
    @JvmStatic
    external fun nv21ToJpegByteArray(nv21: ByteArray, width: Int, height: Int, quality: Int = 90): ByteArray?

    fun isReadyForHook(): Boolean {
        return moduleEnabled && videoSourceIsReady
    }

    fun registerSurfaceIfNew(state: CameraState, forceRefresh: Boolean = false) {
        synchronized(surfaceLock) {
            val lastSurface = lastRegisteredSurface?.get()
            state.surface?.let {
                if (forceRefresh || it != lastSurface) {
                    registerSurface(state.apiLevel, state.cameraId, state.sensorOrientation, state.pictureWidth, state.pictureHeight,
                        state.packageName, state.displayOrientation,it)
                    lastRegisteredSurface = WeakReference(it)
                }
            }
        }
    }

    fun releaseLastRegisteredSurface() {
        synchronized(surfaceLock) {
            lastRegisteredSurface = null
        }
    }

    fun refreshPrefs() {
        try {
            if (!::prefs.isInitialized) return
            videoID = prefs.getLong(KEY_VIDEO_ID, -1L)
            moduleEnabled = prefs.getBoolean(KEY_MODULE_ENABLED, true)
            playSound = prefs.getBoolean(KEY_PLAY_SOUND, false)
            enableLog = prefs.getBoolean(KEY_ENABLE_LOG, false)
            injectMenuEnabled = prefs.getBoolean(KEY_INJECT_MENU, false)
            manuallyRotate = prefs.getBoolean(KEY_MANUALLY_ROTATE, false)
            updateNativeConfig(playSound, enableLog, manuallyRotate)
        } catch (e: Exception) { /* Do Nothing */ }
    }

    fun updateVideoSource() {
        val context = GlobalState.appContext ?: return

        val oldVideoId = videoID
        refreshPrefs()
        val newVideoId = videoID

        if (newVideoId == -1L) {
            if ( oldVideoId != -1L) resetVideoSource()
            videoSourceIsReady = false
            return
        }
        val shouldProcess = (newVideoId != oldVideoId) || !videoSourceIsReady
        if (!shouldProcess) return

        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, newVideoId)
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                videoSourceIsReady = processVideo(
                    afd.parcelFileDescriptor.fd,
                    afd.startOffset,
                    afd.length
                )
            }
        } catch (_: SecurityException) {
            videoSourceIsReady = false
        } catch (e: Exception) {
            prefs.edit().remove(KEY_VIDEO_ID).apply()
            resetVideoSource()
            videoSourceIsReady = false
        }
    }

    fun getFrameSnapshot(): Triple<ByteArray, Int, Int>? {
        if (!hasValidFrame) return null

        val buffer = cachedBuffer ?: return null
        val w = lastFrameWidth
        val h = lastFrameHeight

        val clone = ByteArray(buffer.size)
        System.arraycopy(buffer, 0, clone, 0, buffer.size)

        return Triple(clone, w, h)
    }
}