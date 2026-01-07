package com.nothing.camera2magic.hook

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface
import com.nothing.camera2magic.MagicEntry
import com.nothing.camera2magic.hook.MagicNative.getApiLevel
import com.nothing.camera2magic.utils.Dog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference

private const val TAG = "[CAM2]"
private var activeCameraRef: WeakReference<Any>? = null
fun camera2Hook(lpparam: LoadPackageParam, magicEntry: MagicEntry, surfaceTextureCache: MutableMap<SurfaceTexture, Surface>) {
    val classLoader = lpparam.classLoader
    // Android 10+ 通常使用带 Executor 的重载，但也可能使用带 Handler 的。建议 Hook 所有 openCamera 方法以防万一。
    val cameraManagerClass = XposedHelpers.findClass("android.hardware.camera2.CameraManager", classLoader)
    XposedBridge.hookAllMethods(cameraManagerClass, "openCamera", object : XC_MethodHook() {

        override fun beforeHookedMethod(param: MethodHookParam) {
            val cameraId = param.args[0] as? String
            val callbackClass = param.args[1].javaClass
            if (cameraId != null) {
                getApiLevel(2)
            }

            XposedHelpers.findAndHookMethod(callbackClass, "onOpened", "android.hardware.camera2.CameraDevice", object : XC_MethodHook(){
                override fun afterHookedMethod(param: MethodHookParam) {
                    val cameraDevice = param.args[0]
                    activeCameraRef = WeakReference(cameraDevice)
                    val hash = System.identityHashCode(cameraDevice).toString(16)
                    Dog.i(TAG, "+++ Camera Opened! Hash=@$hash", MagicNative.enableLog)
                }
            })

        }
    })

    val cameraDeviceImplClass = XposedHelpers.findClass("android.hardware.camera2.impl.CameraDeviceImpl", classLoader)

    val sessionHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.moduleEnabled) return
            if (!MagicNative.videoSourceIsReady) return

            // 1. 收集所有 Surface
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

            // 2. 调试：打印所有候选 Surface
            Dog.i(TAG, "Available Surfaces for Session:", MagicNative.enableLog)
            surfaces.forEachIndexed { index, s ->
                if(s.isValid) {
                    val info = MagicNative.getSurfaceInfo(s)
                    // info[0]=w, info[1]=h, info[2]=fmt
                    Dog.i(TAG, "[$index] ${info[0]}x${info[1]} Format=${info[2]} Identity=${System.identityHashCode(s)}", MagicNative.enableLog)
                }
            }

            // 3. 核心修复：智能筛选逻辑
            var targetSurface: Surface? = null

            // 策略：优先找 Format 34 (Preview)，且面积最大的（通常是全屏预览）
            // 如果找不到 34，再找 Format 1 (可能是模拟器或特殊机型)

            val validSurfaces = surfaces.filter { it.isValid }

            // 步骤 A: 找 Format 34 (IMPLEMENTATION_DEFINED)
            val previewCandidates = validSurfaces.filter {
                val fmt = MagicNative.getSurfaceInfo(it)[2]
                fmt == 34 // 0x22
            }

            if (previewCandidates.isNotEmpty()) {
                // 如果有多个 34 (比如 Preview 和 Record)，通常选面积最大的，或者和屏幕比例最接近的
                // 这里简单起见，选面积最大的
                targetSurface = previewCandidates.maxByOrNull {
                    val info = MagicNative.getSurfaceInfo(it)
                    info[0] * info[1]
                }
            }

            // 步骤 B: 没找到 34？那就降级找 Format 1
            if (targetSurface == null) {
                Dog.i(TAG, "No Format-34 surface found! Fallback to Format-1", MagicNative.enableLog)
                targetSurface = validSurfaces.firstOrNull {
                    val fmt = MagicNative.getSurfaceInfo(it)[2]
                    fmt == 1
                }
            }

            // 4. 执行注入
            targetSurface?.let { surface ->
                val info = MagicNative.getSurfaceInfo(surface)
                val w = info[0]
                val h = info[1]
                val fmt = info[2]

                Dog.i(TAG, ">>> SELECTED TARGET: ${w}x${h} Format=${fmt}", MagicNative.enableLog)

                val cameraDevice = param.thisObject as? CameraDevice
                cameraDevice?.id?.let { cameraId ->
                    MagicEntry.resetLastCameraId()
                    magicEntry.logAndSendCameraParameters(cameraId, w, h)
                }
                magicEntry.registerSurfaceIfNew(surface, true)
            }
        }
    }

    XposedHelpers.findAndHookMethod("android.hardware.camera2.impl.CameraDeviceImpl", classLoader, "close", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val closingCamera = param.thisObject
            val activeCamera = activeCameraRef?.get()
            val hash = System.identityHashCode(closingCamera).toString(16)
            if (activeCamera != null && closingCamera === activeCamera) {
                Dog.i(TAG, "+++ Camera Closed! Hash=@$hash", MagicNative.enableLog)
                MagicNative.needStopRenderer()
                activeCameraRef = null
            } else {
                Dog.i(TAG, "Ignored stale release!", MagicNative.enableLog)
            }
        }
    })

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
}