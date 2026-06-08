package com.nothing.camera2magic.hook

import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.hook.NativeBridge as NB
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.Collections
import java.util.WeakHashMap
class WebRTCHooker(val magic: MagicHook, param: PackageReadyParam) : HookManager {
    override val hookedClasses: MutableSet<Class<*>> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap()))
    companion object {
        private const val TAG = "[WebRTC]"
        private val ROTATION_REGEX = Regex("""(\d+)x(\d+).*rotation\s+(\d+)""")
        private var manualRotation = 0
        private lateinit var nativeLogInterceptor: (Chain) -> Any?
    }

    init {
        nativeLogInterceptor = { chain ->
            val tag = chain.args[1] as String
            val msg = chain.args[2] as String
            if (msg.contains("rotation", ignoreCase = true)) {
                handleMessage(msg)
            }
            if (tag == "Camera2Session" && msg.contains("Stop Camera2 session", ignoreCase = true)) {
                Camera3().stop()
                NB.clearTargets()
                BlackHole.clear()
                manualRotation = 0
                NB.updateManualRotation(0)
            }
            chain.proceed()
        }

        val classLoader = param.classLoader
        classLoader.safeHook("org.webrtc.Logging") {
            hookNativeLog()
        }
    }

    private fun Class<*>.hookNativeLog() {
        val nativeLog = getDeclaredMethod("nativeLog",
            Int::class.java, String::class.java, String::class.java)
        magic.hook(nativeLog).intercept(nativeLogInterceptor)
    }

    private fun handleMessage(msg: String) {
        val matchResult = ROTATION_REGEX.find(msg)
        matchResult?.let {
            val (_, _, r) = it.destructured
            val rotation = r.toInt()
            if (manualRotation != rotation) {
                Dog.i(TAG, "WebRTC set rotation: $rotation", SourceManager.enableLog)
                manualRotation = rotation
                NB.updateManualRotation(rotation)
            }
        }
    }
}