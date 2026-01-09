package com.nothing.camera2magic.hook

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface
import android.view.WindowManager
import com.nothing.camera2magic.GlobalHookState
import com.nothing.camera2magic.hook.MagicNative.needStartRenderer
import com.nothing.camera2magic.hook.MagicNative.registerSurfaceIfNew
import com.nothing.camera2magic.hook.MagicNative.releaseLastRegisteredSurface
import com.nothing.camera2magic.utils.Dog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.util.WeakHashMap

private const val TAG = "[CAM2]"
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
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

private fun getSurfaceListFrom(obj: Any?): List<Surface> {
    return when (obj) {
        is SessionConfiguration -> {
            obj.outputConfigurations.mapNotNull { it.surface }
        }
        is List<*> -> {
            val surfaces = obj.filterIsInstance<Surface>()
            if (surfaces.isNotEmpty()) return surfaces
            obj.filterIsInstance<OutputConfiguration>().mapNotNull { it.surface }
        }
        else -> emptyList()
    }
}

private fun getTargetFrom(surfaces: List<Surface>): Surface? {

    var targetSurface: Surface? = null

    val validSurfaces = surfaces.filter { it.isValid }

    // 步骤 A: 找 Format 34 (IMPLEMENTATION_DEFINED)
    val previewCandidates = validSurfaces.filter {
        val fmt = MagicNative.getSurfaceInfo(it)[2]
        fmt == 34 // 0x22
    }

    if (previewCandidates.isNotEmpty()) {
        // 选面积最大的
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
    return targetSurface
}

fun camera2Hook(lpparam: LoadPackageParam) {
    val classLoader = lpparam.classLoader

    val sessionHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!MagicNative.isReadyForHook()) return

            val camera = param.thisObject as CameraDevice
            val state = getCameraState(camera)

            state.apiLevel = 2
            state.cameraId = camera.id

            val context = GlobalHookState.context
            if (context != null) {
                state.displayOrientation = getDisplayOrientation(context)
                state.sensorOrientation = getSensorOrientation(context, camera.id)
            }

            val surfaces = getSurfaceListFrom(param.args[0])
            val targetSurface = getTargetFrom(surfaces)

            targetSurface?.let { surface ->
                val info = MagicNative.getSurfaceInfo(surface)
                state.pictureWidth = info[0]
                state.pictureHeight = info[1]
                state.surface = surface
            }
            registerSurfaceIfNew(state, true)
            needStartRenderer()
        }
    }

    val cameraDeviceImplClass = XposedHelpers.findClass("android.hardware.camera2.impl.CameraDeviceImpl", classLoader)
    XposedHelpers.findAndHookMethod(cameraDeviceImplClass, "close", object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val closingCamera = param.thisObject as CameraDevice
            val activeCamera = activeCameraRef?.get()

            if (activeCamera != null && closingCamera === activeCamera) {
                MagicNative.needStopRenderer()
                releaseLastRegisteredSurface()
                activeCameraRef = null
            }
        }
    })
    XposedHelpers.findAndHookMethod(cameraDeviceImplClass, "createCaptureSession", SessionConfiguration::class.java, sessionHook)
    XposedHelpers.findAndHookMethod(cameraDeviceImplClass, "createCaptureSession", List::class.java, CameraCaptureSession.StateCallback::class.java, Handler::class.java, sessionHook)
}