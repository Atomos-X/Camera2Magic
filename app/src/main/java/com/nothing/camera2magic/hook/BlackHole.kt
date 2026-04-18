package com.nothing.camera2magic.hook

import android.graphics.SurfaceTexture
import android.view.Surface
import java.util.WeakHashMap

object BlackHole {
    data class BH(
        val surface: Surface,
        val surfaceTexture: SurfaceTexture,
        var activeCount: Boolean = false
    ) {
        fun release() {
            surface.release()
            surfaceTexture.release()
        }
    }

    @Volatile
    private var _oab = WeakHashMap<Surface, BH>()

    val oab: WeakHashMap<Surface, BH>
        get() = _oab

    @Volatile
    private var dummyTexId = 0x100

    val Surface.getBlackHole: Surface?
        get() = getBlackHole(this)
    val Surface.gocBlackHole: Surface
        get() = getOrCreateBlackHole(this).surface

    val Surface.gocBlackHoleTexture: SurfaceTexture
        get() = getOrCreateBlackHole(this).surfaceTexture

    val originSurfaces: List<Surface>
        get() = _oab.keys.filter { it != null && it.isValid }


    private val Surface.hashCode: Int
        get() = System.identityHashCode(this)

    private val Surface.isValid: Boolean
        get() = this.isValid



    fun clear() {
        dummyTexId = 0x100
        _oab.values.forEach { bh ->
            bh?.release()
        }
        _oab.clear()
    }

    private fun createBlackHole(): BH {
        val st = SurfaceTexture(dummyTexId)
            .apply {
                setDefaultBufferSize(320, 240)
                detachFromGLContext()
            }
        dummyTexId++
        return BH(Surface(st), st)
    }

    private fun getOrCreateBlackHole(origin: Surface): BH {
        return _oab.getOrPut(origin) { createBlackHole() }
    }

    private fun getBlackHole(origin: Surface): Surface? {
        return _oab[origin]?.surface
    }
}