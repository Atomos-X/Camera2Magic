package com.nothing.camera2magic.hook

import android.graphics.ImageFormat
import android.media.ImageReader
import android.view.Surface
import java.util.WeakHashMap

data class BlackHole(
    val identityId: Int,
    val surface: Surface,
    val reader: ImageReader
)

object BlackHoleMapper {
    private val oabMap = WeakHashMap<Surface, BlackHole>()
    fun createBlackHole(origin: Surface): Surface {
        return oabMap.getOrPut(origin) {
            val id = 20 + oabMap.size
            val reader = ImageReader.newInstance(
                2, 2,
                ImageFormat.YUV_420_888, 2)
            reader.setOnImageAvailableListener({ r ->
                runCatching {
                    val image = r.acquireLatestImage()
                    image?.close()
                }
            }, null)
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