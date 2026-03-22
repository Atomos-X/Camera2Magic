@file:Suppress("DEPRECATION")

package com.nothing.camera2magic.hook

import android.graphics.Bitmap
import android.hardware.Camera
import android.view.Surface
import com.nothing.camera2magic.utils.Dog
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object NativeBridge {
    private const val TAG = "[Bridge]"
    @Volatile
    private var lastRegisteredSurface: WeakReference<Surface>? = null

    private val surfaceLock = Any()
    @Volatile
    private var cachedBuffer: ByteArray? = null

    @Volatile
    var currentCamera: WeakReference<Camera>? = null
    @Volatile
    var previewCallback: WeakReference<Camera.PreviewCallback>? = null

    @JvmStatic
    fun ensureBuffer(size: Int): ByteArray {
        if (cachedBuffer != null && cachedBuffer!!.size == size) {
            return cachedBuffer!!
        }
        return ByteArray(size).also { cachedBuffer = it }
    }

    @JvmStatic
    fun frameUpdated(width: Int, height: Int) {
        val buffer = cachedBuffer ?: return
        val expectedSize = width * height * 3 / 2
        if (buffer.size < expectedSize) return
        runCatching {
            val camera = currentCamera?.get() ?: return
            val callback = previewCallback?.get() ?: return
            callback.onPreviewFrame(buffer, camera)
        }
    }
    @JvmStatic
    external fun updateGlobalConfig(playSound: Boolean, enableLog: Boolean)
    @JvmStatic
    external fun registerSurface(cameraState: CameraState)
    @JvmStatic
    external fun updateManualRotation(rotation: Int)
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
    external fun overwriteJPEGBytes(quality: Int = 90): ByteArray

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