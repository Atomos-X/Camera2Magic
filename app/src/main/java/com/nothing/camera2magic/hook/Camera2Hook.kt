package com.nothing.camera2magic.hook

import android.graphics.SurfaceTexture
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface
import com.nothing.camera2magic.GlobalHookState
import com.nothing.camera2magic.MagicEntry
import com.nothing.camera2magic.hook.MagicNative.getApiLevel
import com.nothing.camera2magic.hook.MagicNative.updateCameraParameters
import com.nothing.camera2magic.utils.Dog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlinx.coroutines.MainScope
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private const val TAG = "[CAM2]"
private var isNativeEnvInitialized = false
private var activeCameraRef: WeakReference<Any>? = null
private var cameraState = WeakHashMap<CameraDevice, CameraState>()

private fun getCameraState(camera: CameraDevice): CameraState {
    return synchronized(cameraState) {
        cameraState.getOrPut(camera) { CameraState() }
    }
}

private fun getSensorOrientation(context: Context, cameraId: String): Int {
    return try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraId)
        characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    } catch (e: Throwable) {
        90 // 默认值
    }
}

@Suppress("DEPRECATION")
private fun getDisplayOrientation(context: Context): Int {
    return try {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val rotation = windowManager.defaultDisplay.rotation
        when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    } catch (e: Throwable) {
        0
    }
}

fun camera2Hook(lpparam: LoadPackageParam, magicEntry: MagicEntry, surfaceTextureCache: MutableMap<SurfaceTexture, Surface>) {
    val classLoader = lpparam.classLoader

    val sessionHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.moduleEnabled) return
            if (!MagicNative.videoSourceIsReady) return

            if (!isNativeEnvInitialized) {
                getApiLevel(2)
                isNativeEnvInitialized = true
            }
            val cameraDevice = param.thisObject as CameraDevice
            activeCameraRef = WeakReference(cameraDevice)
            val state = getCameraState(cameraDevice)
            state.cameraId = cameraDevice.id
            val context = GlobalHookState.applicationContext
            if (context != null) {
                state.sensorOrientation = getSensorOrientation(context, cameraDevice.id)
                state.displayOrientation = getDisplayOrientation(context)
            }

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

                updateCameraParameters(state.cameraId, state.sensorOrientation, w, h)
                magicEntry.registerSurfaceIfNew(surface, state.displayOrientation, true)
                MagicNative.needStartRenderer()
            }
        }
    }

    val cameraDeviceImplClass = XposedHelpers.findClass("android.hardware.camera2.impl.CameraDeviceImpl", classLoader)

    XposedHelpers.findAndHookMethod(cameraDeviceImplClass, "close", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val closingCamera = param.thisObject as CameraDevice
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