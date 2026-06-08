package com.nothing.camera2magic

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.widget.Toast
import com.nothing.camera2magic.hook.Camera1Hooker
import com.nothing.camera2magic.hook.Camera2Hooker
import com.nothing.camera2magic.hook.SourceManager as SM
import com.nothing.camera2magic.hook.ImageReaderHooker
import com.nothing.camera2magic.hook.WebRTCHooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class MagicHook : XposedModule() {
    companion object {
        private const val TAG = "[MagicHook]"
    }
    init { System.loadLibrary("camera3") }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        GlobalState.processName = Application.getProcessName()
        val remotePrefs = getRemotePreferences("camera_magic_config")
        SM.init(remotePrefs)
        Application::class.java.onCreateHook()
        Camera1Hooker(this, param)
        Camera2Hooker(this, param)
        ImageReaderHooker(this, param)
        WebRTCHooker(this, param)
    }

    private fun Class<*>.onCreateHook() {
        val onCreate = getDeclaredMethod("onCreate")
        hook(onCreate).intercept { chain ->
            val app =  chain.thisObject as Application
            GlobalState.appContext = app.also {
                registerForegroundChecker(it)
            }
            return@intercept chain.proceed()
        }
    }

    private fun registerForegroundChecker(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityCreated(activity: Activity, savedInstance: Bundle?) {}
            override fun onActivityDestroyed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityStarted(activity: Activity) {
                GlobalState.activityCount++
                if (GlobalState.activityCount == 1) {
                    SM.refreshAndDispatch()
                    activity.runOnUiThread {
                        val text = "[✨] " + SM.toastMessage
                        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            override fun onActivityStopped(activity: Activity) { GlobalState.activityCount-- }
        })
    }
}
