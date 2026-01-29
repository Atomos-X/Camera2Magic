package com.nothing.camera2magic.hook

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.view.Surface
import android.os.Handler
import android.view.WindowManager
import com.nothing.camera2magic.GlobalState
import com.nothing.camera2magic.MagicEntry
import com.nothing.camera2magic.hook.MagicNative.needStartRenderer
import com.nothing.camera2magic.hook.MagicNative.needStopRenderer
import com.nothing.camera2magic.hook.MagicNative.registerSurfaceIfNew
import com.nothing.camera2magic.hook.MagicNative.releaseLastRegisteredSurface
import com.nothing.camera2magic.utils.Dog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.BeforeHookCallback
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object Camera2Hooker {
    private const val TAG = "[CAM2]"
    private var activeCameraRef: WeakReference<Any>? = null
    private var cameraState = WeakHashMap<CameraDevice, CameraState>()

    private fun getCameraState(camera: CameraDevice): CameraState {
        return synchronized(cameraState) {
            cameraState.getOrPut(camera) { CameraState() }
        }
    }

    @Suppress("DEPRECATION")
    private fun getDisplayOrientation(context: Context): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = windowManager.defaultDisplay.rotation
        return rotation * 90
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

    fun initHooks(module: MagicEntry, param: PackageLoadedParam) {
        val classLoader = param.classLoader
        val cameraDeviceImplClass = classLoader.loadClass("android.hardware.camera2.impl.CameraDeviceImpl")

        val open = cameraDeviceImplClass.getDeclaredMethod("open", CameraDevice.StateCallback::class.java, Handler::class.java)
        module.hook(open, CameraOpen::class.java)

        val close = cameraDeviceImplClass.getDeclaredMethod("close")
        module.hook(close, CameraClose::class.java)

        val createCaptureSessionWithLegacyApi = cameraDeviceImplClass.getDeclaredMethod(
            "createCaptureSession",
            List::class.java,
            CameraCaptureSession.StateCallback::class.java,
            Handler::class.java)

        module.hook(createCaptureSessionWithLegacyApi, CameraCreateCaptureSession::class.java)


        val createCaptureSessionWithModernApi = cameraDeviceImplClass.getDeclaredMethod(
            "createCaptureSession",
            SessionConfiguration::class.java)

        module.hook(createCaptureSessionWithModernApi, CameraCreateCaptureSession::class.java)
    }

    class CameraOpen : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun after(callback: BeforeHookCallback) {
                val context = GlobalState.appContext
                val camera = callback.thisObject as CameraDevice
                activeCameraRef = WeakReference(camera)
                val cameraIdStr = camera.id
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val characteristics = manager.getCameraCharacteristics(cameraIdStr)
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                val frontCamera = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

                val state = getCameraState(camera)
                state.apiLevel = 2
                state.cameraId = cameraIdStr.toInt()
                state.sensorOrientation = sensorOrientation
                state.isFrontCamera = frontCamera
                state.packageName = GlobalState.packageName ?: "UNKNOWN"
            }
        }
    }
    class CameraClose : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                val closingCamera = callback.thisObject as CameraDevice
                val activeCamera = activeCameraRef?.get()
                if (activeCamera != null && closingCamera === activeCamera) {
                    needStopRenderer()
                    releaseLastRegisteredSurface()
                    activeCameraRef = null
                }
            }
        }
    }
    class CameraCreateCaptureSession : XposedInterface.Hooker {
        companion object {
            @JvmStatic
            fun before(callback: BeforeHookCallback) {
                if (!MagicNative.isReadyForHook()) return

                val context = GlobalState.appContext
                val camera = callback.thisObject as CameraDevice
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                @Suppress("DEPRECATION")
                val rotation = windowManager.defaultDisplay.rotation

                val state = getCameraState(camera)
                state.displayOrientation = rotation * 90

                val surfaces = getSurfaceListFrom(callback.args[0])
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
    }
}