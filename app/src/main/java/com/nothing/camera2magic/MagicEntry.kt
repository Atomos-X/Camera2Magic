package com.nothing.camera2magic

import android.app.Activity
import android.app.Application
import android.content.Context
import com.nothing.camera2magic.utils.FloatWindowManager
import com.nothing.camera2magic.hook.MagicNative
import com.nothing.camera2magic.hook.MagicNative.updateVideoSource
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import com.nothing.camera2magic.hook.camera1Hook
import com.nothing.camera2magic.hook.camera2Hook

object GlobalHookState {
    @Volatile
    var context: Context? = null
}

class MagicEntry : IXposedHookLoadPackage {

    external fun nativeInit()

    companion object {
        private const val TAG = "[MAGIC]"
        private const val MODULE_PACKAGE_NAME = "com.nothing.camera2magic"

        init {
            System.loadLibrary("camera_magic")
        }

    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {

        if (lpparam.packageName == MODULE_PACKAGE_NAME) return
        XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = param.thisObject as Application
                    GlobalHookState.context = context

                    val magicEntryInstance = MagicEntry()
                    magicEntryInstance.nativeInit()
                    magicEntryInstance.hookActivity()
                    camera1Hook(lpparam)
                    camera2Hook(lpparam)
                    FloatWindowManager.init(context)
                }
            })
    }

    private fun hookActivity() {
        XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                updateVideoSource()
                val activity = param.thisObject as Activity
                FloatWindowManager.updateFloatWindowVisibility(activity, MagicNative.injectMenuEnabled)
            }
        })
    }
}
