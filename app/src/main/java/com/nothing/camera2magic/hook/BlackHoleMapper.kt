package com.nothing.camera2magic.hook

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.nothing.camera2magic.utils.Dog
import java.util.WeakHashMap

data class BlackHole(
    val identityId: Int,
    val surface: Surface,
    val reader: ImageReader
)
private const val TAG = "[BlackHole]"
object BlackHoleMapper {
    private val oabMap = WeakHashMap<Surface, BlackHole>()
    private val camera3Thread = HandlerThread("camera3Thread").apply { start() }
    private val camera3Handler = Handler(camera3Thread.looper)
    fun createBlackHole(origin: Surface): Surface {
        return oabMap.getOrPut(origin) {
            val id = 20 + oabMap.size
            val reader = ImageReader.newInstance(1280, 720,
                ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ r ->
                runCatching {
                    val image = r.acquireLatestImage()
                    image?.close()
                }.onFailure { exception ->
                    Dog.e(TAG, "acquireLatestImage Failed: ${exception.message}", exception, true)
                }
            }, camera3Handler)
            BlackHole(id, reader.surface, reader)
        }.surface
    }

    fun getBlackHole(origin: Surface): Surface? {
        return oabMap[origin]?.surface
    }

    fun clearAll() {
        oabMap.values.forEach {
            it.reader.close()
        }
        oabMap.clear()
    }
}