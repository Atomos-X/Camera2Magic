package com.nothing.camera2magic

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.Surface
import com.nothing.camera2magic.hook.FloatWindowManager
import com.nothing.camera2magic.hook.MagicNative
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.WeakHashMap
import java.util.Collections
import com.nothing.camera2magic.hook.MagicNative.logDog as DOG
import com.nothing.camera2magic.hook.magicCamera1
import com.nothing.camera2magic.hook.magicCamera2

object GlobalHookState {
    @Volatile
    var applicationContext: Context? = null
}

class MagicEntry : IXposedHookLoadPackage {

    external fun nativeInit()
    companion object {
        private const val TAG = "[MAGIC]"
        @Volatile
        var lastRegisteredSurface: Surface? = null
        val surfaceLock = Any()

        @get:JvmStatic
        @Volatile
        var lastCameraId: String? = null
            private set

        @JvmStatic
        fun resetLastCameraId() {
            lastCameraId = null
        }


        private var lastCameraSentAt: Long = 0
        private val CAMERA_PARAM_THROTTLE_MS = 250L

        // 缓存 SurfaceTexture -> Surface 的映射
        private val surfaceTextureCache: MutableMap<SurfaceTexture, Surface> =
            Collections.synchronizedMap(WeakHashMap())
    }

    fun registerSurfaceIfNew(surface: Surface, forceRefresh: Boolean = false) {
        synchronized(surfaceLock) {
            // 如果是新的 Surface 或者被指定强制刷新，就传递给 Native
            if (forceRefresh || lastRegisteredSurface != surface) {
                MagicNative.registerSurface(surface)
                lastRegisteredSurface = surface
            }
        }
    }

    fun logAndSendCameraParameters(
        cameraId: String,
        overrideWidth: Int = 0,
        overrideHeight: Int = 0
    ) {

        val now = System.currentTimeMillis()
        val isExplicitUpdate = overrideWidth > 0

        if (!isExplicitUpdate &&
            lastCameraId == cameraId &&
            now - lastCameraSentAt < CAMERA_PARAM_THROTTLE_MS
        ) {
            return
        }

        lastCameraId = cameraId
        lastCameraSentAt = now

        val context = GlobalHookState.applicationContext ?: return
        try {
            val cameraManager = context.getSystemService(CameraManager::class.java)
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            var pictureWidth = overrideWidth
            var pictureHeight = overrideHeight

            MagicNative.updateCameraParameters(
                cameraId,
                sensorOrientation,
                pictureWidth,
                pictureHeight
            )
        } catch (e: Exception) {
            DOG(TAG, "Error getting camera characteristics: ${e.message}", MagicNative.enableLog)
        }
    }


    override fun handleLoadPackage(lpparam: LoadPackageParam) {

        if (lpparam.packageName == MagicNative.MODULE_PACKAGE_NAME) return

        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {

                    val context = param.thisObject as Application
                    GlobalHookState.applicationContext = context
                    val magicEntryInstance = MagicEntry()

                    System.loadLibrary("camera_magic")

                    magicEntryInstance.nativeInit()
                    MagicNative.updateVideoSource()

                    magicEntryInstance.hookActivity(lpparam)
                    magicEntryInstance.hookGLES20(lpparam)

                    magicCamera1(lpparam, magicEntryInstance, surfaceTextureCache)
                    magicCamera2(lpparam, magicEntryInstance, surfaceTextureCache)
                    FloatWindowManager.init(context)
                }
            }
        )
    }

    private fun hookGLES20(lpparam: LoadPackageParam) {
        try {
            val gles20Class = XposedHelpers.findClass("android.opengl.GLES20", lpparam.classLoader)

            XposedBridge.hookAllMethods(
                gles20Class,
                "glUniformMatrix4fv",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size != 5 || param.args[3] !is FloatArray) {
                            return
                        }
                        val matrix = param.args[3] as FloatArray
                        MagicNative.updateExternalMatrix(matrix)
                    }
                })
        } catch (t: Throwable) {
            DOG(TAG, "Couldn't hook GLES20: ${t.message}", MagicNative.enableLog)
        }
    }

    private fun hookActivity(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        MagicNative.updateVideoSource()
                        MagicNative.setNativeLogEnabled(MagicNative.enableLog)
                        val activity = param.thisObject as Activity
                        FloatWindowManager.updateFloatWindowVisibility(activity, MagicNative.injectMenuEnabled)
                    }
                })
        } catch (t: Throwable) {
            DOG(TAG, "Couldn't hook Activity.onResume: ${t.message}", MagicNative.enableLog)
        }
    }
}
