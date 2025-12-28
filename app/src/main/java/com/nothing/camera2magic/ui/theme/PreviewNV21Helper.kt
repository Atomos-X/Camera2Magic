package com.nothing.camera2magic.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

object PreviewNV21Helper {
    private const val PREVIEW_INTERVAL_MS = 200L
    private var lastPreviewTime = 0L

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 纯粹的 NV21 -> Bitmap 转换器
     * @param callback 在主线程回调生成的 Bitmap
     */
    fun processFrame(data: ByteArray, width: Int, height: Int, callback: (Bitmap) -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastPreviewTime < PREVIEW_INTERVAL_MS) {
            return
        }
        lastPreviewTime = now

        executor.execute {
            try {

                val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
                val outStream = ByteArrayOutputStream()

                yuvImage.compressToJpeg(Rect(0, 0, width, height), 50, outStream)
                val jpegBytes = outStream.toByteArray()

                val options = BitmapFactory.Options()
                options.inSampleSize = 4

                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)

                if (bitmap != null) {
                    mainHandler.post {
                        callback(bitmap)
                    }
                }
            } catch (e: Exception) {
            }
        }
    }
}