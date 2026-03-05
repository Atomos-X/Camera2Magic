@file:Suppress("DEPRECATION")

package com.nothing.camera2magic.hook

import android.graphics.Bitmap
import android.hardware.Camera
import android.view.Surface
import com.nothing.camera2magic.utils.Dog
import java.lang.ref.WeakReference

object NativeBridge {
    private const val TAG = "[Bridge]"
    var lastFrameWidth = 0
    var lastFrameHeight = 0
    var camera1Callback: Camera.PreviewCallback? = null
    var currentCamera1: Camera? = null
    @Volatile
    var hasValidFrame = false
    @Volatile
    private var lastRegisteredSurface: WeakReference<Surface>? = null
    private val surfaceLock = Any()

    private var cachedBuffer: ByteArray? = null
    @JvmStatic
    fun ensureBuffer(size: Int) {
        if (cachedBuffer != null && cachedBuffer!!.size == size) {
            return
        }
        hasValidFrame = false
        cachedBuffer = ByteArray(size)
    }
    @JvmStatic
    fun getCachedBuffer(): ByteArray? = cachedBuffer
    @JvmStatic
    fun onFrameDataUpdated(width: Int, height: Int) {
        lastFrameWidth = width
        lastFrameHeight = height
        hasValidFrame = true
        val buffer = cachedBuffer ?: return
        val expectedSize = width * height * 3 / 2
        if (buffer.size < expectedSize) return


        try {
            if (camera1Callback != null && currentCamera1 != null) {
                camera1Callback?.onPreviewFrame(buffer, currentCamera1)
            }
        } catch (e: Exception) {
            Dog.i(TAG, "Error in Camera1 callback: ${e.message}", SourceManager.enableLog)
        }
    }
    @JvmStatic
    external fun updateGlobalConfig(playSound: Boolean, enableLog: Boolean)
    @JvmStatic
    external fun registerSurface(cameraState: CameraState)
    @JvmStatic
    external fun setDisplayOrientation(orientation: Int)
    @JvmStatic
    external fun getSurfaceInfo(surface: Surface): IntArray
    @JvmStatic
    external fun resetMediaSource()
    @JvmStatic
    external fun processVideo(fd: Int, offset: Long, length: Long): Boolean
    @JvmStatic
    external fun processBitmap(bitmap: Bitmap): Boolean
    @JvmStatic
    external fun needStopRenderer()
    @JvmStatic
    external fun needStartRenderer()
    @JvmStatic
    external fun overwritePreviewBuffer(originBuffer: ByteArray)
    @JvmStatic
    external fun overwriteJPEGBytes(jpegBytes: ByteArray, quality: Int = 90)
    fun registerSurfaceIfNew(state: CameraState, forceRefresh: Boolean = false) {
        synchronized(surfaceLock) {
            val lastSurface = lastRegisteredSurface?.get()
            state.surface?.let { surface ->
                if (forceRefresh || surface != lastSurface) {
                    registerSurface(cameraState = state)
                    lastRegisteredSurface = WeakReference(surface)
                }
            }
        }
    }
    fun releaseLastRegisteredSurface() {
        synchronized(surfaceLock) {
            lastRegisteredSurface = null
        }
    }
}