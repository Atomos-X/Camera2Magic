package com.nothing.camera2magic


import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import com.nothing.camera2magic.hook.Camera1Hooker
import com.nothing.camera2magic.hook.Camera2Hooker
import com.nothing.camera2magic.hook.MagicNative
import com.nothing.camera2magic.utils.FloatWindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

object GlobalState {
    @Volatile
    var appContext: Context? = null
    @Volatile
    var packageName: String? = null
}

private const val TAG = "[Entry]"

class MagicEntry(base: XposedInterface, param: ModuleLoadedParam) : XposedModule(base, param) {
    init { System.loadLibrary("camera_magic") }
    class AttachHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                val context = callback.args[0] as Context
                GlobalState.appContext = context
                val application = callback.thisObject as Application
                FloatWindowManager.init(application)
            }
        }
    }
    class ActivityHooker : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun after(callback: XposedInterface.AfterHookCallback) {
                val activity = callback.thisObject as Activity
                MagicNative.updateVideoSource()
                FloatWindowManager.updateFloatWindowVisibility(activity, MagicNative.injectMenuEnabled)
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (!param.isFirstPackage) return
        GlobalState.packageName = param.packageName

        val remotePrefs = getRemotePreferences("camera_magic_config")
        MagicNative.init(remotePrefs)

        val attachMethod = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(attachMethod, AttachHooker::class.java)

        val resumeMethod = Activity::class.java.getDeclaredMethod("onResume")
        hook(resumeMethod, ActivityHooker::class.java)

        Camera1Hooker.initHooks(this, param)
        Camera2Hooker.initHooks(this, param)
    }
}
