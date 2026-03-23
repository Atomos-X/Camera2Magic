package com.nothing.camera2magic.hook

import java.lang.ref.WeakReference
import java.util.WeakHashMap

object CameraRegistry {
    private val stateMap = WeakHashMap<Any, CameraState>()
    private var lastActiveRef = WeakReference<Any>(null)

    fun obtain(camera: Any, action: CameraState.() -> Unit = {}): CameraState {
        val state = stateMap.getOrPut(camera) { CameraState() }
        state.action()
        lastActiveRef = WeakReference(camera)
        return state
    }

    fun isActive(camera: Any): Boolean {
        val cachedRef = lastActiveRef.get()
        return cachedRef != null && camera === cachedRef
    }
}