package com.nothing.camera2magic.hook

import android.annotation.SuppressLint
import android.os.Handler
import com.nothing.camera2magic.MagicEntry
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedInterface.AfterHookCallback
import io.github.libxposed.service.XposedServiceHelper

object WebRTCHooker {
    private const val TAG = "[WebRTC]"

    @SuppressLint("PrivateApi")
    fun initHooks(module: MagicEntry, param: PackageLoadedParam) {
        val classLoader = param.classLoader
        val helperClass = classLoader.loadClass("org.webrtc.SurfaceTextureHelper")
        val constructors = helperClass.declaredConstructors
        constructors.forEach { method ->
            module.hook(method, SurfaceTextureHelperHooker::class.java)
        }
    }

    class SurfaceTextureHelperHooker: XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun after(callback: AfterHookCallback) {
                val helper = callback.thisObject as Any
                val handle = helper.javaClass.getDeclaredField("handler")
                    .apply { isAccessible = true }
                    .get(helper) as? Handler
                Dog.w(TAG,"${handle?.looper?.thread?.name}", true)
            }
        }
    }
}