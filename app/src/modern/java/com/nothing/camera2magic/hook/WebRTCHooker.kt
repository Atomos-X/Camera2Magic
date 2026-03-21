package com.nothing.camera2magic.hook

import com.nothing.camera2magic.MagicHook
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

object WebRTCHooker {
    private const val TAG = "[WebRTC]"
    private lateinit var magic: MagicHook
    fun initHooks(module: MagicHook, param: PackageReadyParam) {
        magic = module
        val classLoader = param.classLoader
        val webRTCClass = classLoader.loadClass("org.webrtc.Logging")
        webRTCClass.apply {
            hookWebRTCLog()
        }

    }

    private fun Class<*>.hookWebRTCLog() {
        val nativeLog = getDeclaredMethod("nativeLog",
            Int::class.java, String::class.java, String::class.java)
        magic.hook(nativeLog).intercept { chain ->
            val tag = chain.args[1] as String
            val msg = chain.args[2] as String
            Dog.i(TAG, "[$tag] $msg", true)
            chain.proceed()
        }
    }
}