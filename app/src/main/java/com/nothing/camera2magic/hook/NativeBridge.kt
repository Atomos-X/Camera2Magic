@file:Suppress("DEPRECATION")

package com.nothing.camera2magic.hook

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.view.Surface
import java.lang.ref.WeakReference
import java.nio.ByteBuffer

object NativeBridge {
    private const val TAG = "[Bridge]"
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
    external fun createOESTexture(): Int
    @JvmStatic
    external fun notifyFrameAvailable()
    @JvmStatic
    external fun setSurfaceTexture(st: SurfaceTexture)
    @JvmStatic
    external fun getSurfaceInfo(surface: Surface): IntArray
    @JvmStatic
    external fun updateCameraBaseData(api: Int, facingFront: Boolean, sensorOri: Int, displayOri: Int, processName: String)

    @JvmStatic
    external fun updateManualRotation(rotation: Int)
    @JvmStatic
    external fun addRenderTarget(surface: Surface, vWidth: Int, vHeight: Int, pWidth: Int, pHeight: Int)
    @JvmStatic
    external fun addRenderTarget(surface: Surface)
    @JvmStatic
    external fun removeRenderTarget(surface: Surface)
    @JvmStatic
    external fun clearTargets()
    @JvmStatic
    external fun updateAlgorithmSize(width: Int, height: Int)
    @JvmStatic
    external fun updateFrameInfo(width: Int, height: Int, rotation: Int)
    @JvmStatic
    external fun overwriteYuvBuffer(originBuffer: ByteArray)

    @JvmStatic
    external fun overwriteYuvBuffer(yBuffer: ByteBuffer, yRowStride: Int, yPixelStride: Int,
                                    uBuffer: ByteBuffer, uRowStride: Int, uPixelStride: Int,
                                    vBuffer: ByteBuffer, vRowStride: Int, vPixelStride: Int)
    @JvmStatic
    external fun overwriteJPEGBytes(quality: Int = 90): ByteArray
}