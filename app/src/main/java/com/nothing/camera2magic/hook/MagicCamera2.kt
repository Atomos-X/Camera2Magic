package com.nothing.camera2magic.hook

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface
import android.widget.Toast
import com.nothing.camera2magic.GlobalHookState
import com.nothing.camera2magic.MagicEntry
import com.nothing.camera2magic.hook.MagicNative.getApiLevel
import com.nothing.camera2magic.hook.MagicNative.logDog as DOG
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam


private const val TAG = "[CAM2]"

fun magicCamera2(lpparam: LoadPackageParam, magicEntry: MagicEntry, surfaceTextureCache: MutableMap<SurfaceTexture, Surface>) {
    try {
        val classLoader = lpparam.classLoader
        // Android 10+ 通常使用带 Executor 的重载，但也可能使用带 Handler 的。建议 Hook 所有 openCamera 方法以防万一。
        val cameraManagerClass = XposedHelpers.findClass("android.hardware.camera2.CameraManager", classLoader)
        XposedBridge.hookAllMethods(cameraManagerClass, "openCamera", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cameraId = param.args[0] as? String
                if (cameraId != null) {
                    getApiLevel(2)
                }
            }
        })

        val cameraDeviceImplClass = XposedHelpers.findClass("android.hardware.camera2.impl.CameraDeviceImpl", classLoader)

        val sessionHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!MagicNative.moduleEnabled) return
                if (!MagicNative.videoSourceIsReady) return
                val surfaces = mutableListOf<Surface>()
                val arg0 = param.args.getOrNull(0)

                if (arg0 is SessionConfiguration) {
                    arg0.outputConfigurations.forEach { it.surface?.let { s -> surfaces.add(s) } }
                } else if (arg0 is List<*>) {
                    arg0.forEach { item ->
                        if (item is Surface) surfaces.add(item)
                        else if (item is OutputConfiguration) item.surface?.let { s -> surfaces.add(s) }
                    }
                }

                var maxW = 0
                var maxH = 0
                var maxArea = 0
                var previewSurface: Surface? = null

                val sb = StringBuilder("Session Surfaces: ")

                surfaces.forEach { surface ->
                    if (surface.isValid) {
                        val info = MagicNative.getSurfaceInfo(surface)
                        val w = info[0]
                        val h = info[1]
                        val fmt = info[2]
                        val area = w * h

                        if (area > maxArea) {
                            maxArea = area
                            maxW = w
                            maxH = h
                        }

                        if (previewSurface == null && fmt == 1 && area < maxArea) {
                            previewSurface = surface
                        }
                    }
                }

                if (previewSurface == null && surfaces.isNotEmpty()) {
                    previewSurface = surfaces.firstOrNull { it.isValid }
                }

                val finalPreview = previewSurface ?: surfaces.firstOrNull { it.isValid }

                finalPreview?.let { surface ->

                    val cameraDevice = param.thisObject as? CameraDevice
                    cameraDevice?.id?.let { cameraId ->

                        MagicEntry.Companion.resetLastCameraId()

                        DOG(TAG, "Setting Capture Size to: $maxW x $maxH", MagicNative.enableLog)
                        magicEntry.logAndSendCameraParameters(cameraId, maxW, maxH)
                    }

                    magicEntry.registerSurfaceIfNew(surface, true)
                }
            }
        }

        XposedHelpers.findAndHookMethod(cameraDeviceImplClass, "createCaptureSession", SessionConfiguration::class.java, sessionHook)

        XposedHelpers.findAndHookMethod(cameraDeviceImplClass, "createCaptureSession", List::class.java, CameraCaptureSession.StateCallback::class.java, Handler::class.java, sessionHook)

        val blockCaptureHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!MagicNative.moduleEnabled) return
                if (!MagicNative.videoSourceIsReady) return
                param.result = 0
            }
        }

        XposedBridge.hookAllMethods(cameraDeviceImplClass, "capture", blockCaptureHook)
        XposedBridge.hookAllMethods(cameraDeviceImplClass, "captureBurst", blockCaptureHook)
        XposedBridge.hookAllMethods(cameraDeviceImplClass, "setRepeatingRequest", blockCaptureHook)
        XposedBridge.hookAllMethods(cameraDeviceImplClass, "setRepeatingBurst", blockCaptureHook)

    } catch (t: Throwable) {
        DOG(TAG, "Couldn't hook Camera2: ${t.message}", MagicNative.enableLog)
    }
}