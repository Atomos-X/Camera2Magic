package com.nothing.camera2magic

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.widget.Toast
import com.nothing.camera2magic.hook.Camera1Hooker
import com.nothing.camera2magic.hook.Camera2Hooker
import com.nothing.camera2magic.hook.SourceManager
import com.nothing.camera2magic.hook.WebRTCHooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class MagicHook : XposedModule() {

    init {
        System.loadLibrary("camera3")
    }

    companion object {
        private const val TAG = "[MagicHook]"
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        GlobalState.packageName = param.packageName
        val remotePrefs = getRemotePreferences("camera_magic_config")
        SourceManager.init(remotePrefs)
        hookAttach()
        hookActivity()
        Camera1Hooker.initHooks(this, param)
        Camera2Hooker.initHooks(this, param)
        WebRTCHooker.initHooks(this, param)
    }

    @SuppressLint("DiscouragedPrivateApi")
    private fun hookAttach() {
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(attach).intercept { chain ->
            GlobalState.appContext = chain.args[0] as Context
            chain.proceed()
        }
    }

    private fun hookActivity() {
        val start = Activity::class.java.getDeclaredMethod("onStart")
        hook(start).intercept { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as Activity
            GlobalState.activityCount ++
            if (GlobalState.activityCount == 1) {
                SourceManager.refreshAndDispatch()
                activity.runOnUiThread {
                    val text = "[✨] " + SourceManager.toastMessage
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
                }
            }
            return@intercept result
        }

        val stop = Activity::class.java.getDeclaredMethod("onStop")
        hook(stop).intercept { chain ->
            chain.proceed()
            GlobalState.activityCount--
        }
    }
}
