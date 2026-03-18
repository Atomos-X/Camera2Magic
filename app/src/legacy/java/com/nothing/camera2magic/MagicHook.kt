package com.nothing.camera2magic

import android.app.Activity
import android.app.Application
import android.content.Context
import android.widget.Toast
import com.nothing.camera2magic.hook.SourceManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class MagicHook : IXposedHookLoadPackage {
    init {
        System.loadLibrary("camera3")
    }
    companion object {
        private const val TAG = "[MagicHook]"
        private const val MODULE_PACKAGE_NAME = "com.nothing.camera2magic"
    }
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == MODULE_PACKAGE_NAME) return
        GlobalState.packageName = lpparam.packageName

        XposedHelpers.findAndHookMethod(Application::class.java,
            "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    GlobalState.appContext = param.thisObject as Context
                    //TODO:
                }
            })

        XposedHelpers.findAndHookMethod(Activity::class.java,
            "onStart", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    GlobalState.activityCount ++
                    if (GlobalState.activityCount == 1) {
                        SourceManager.refreshAndDispatch()
                        activity.runOnUiThread {
                            val text = "[✨] " + SourceManager.toastMessage
                            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })

        XposedHelpers.findAndHookMethod(Activity::class.java,
            "onStop", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    GlobalState.activityCount--
                }
            })
    }

}